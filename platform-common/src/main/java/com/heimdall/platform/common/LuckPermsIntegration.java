package com.heimdall.platform.common;

import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.platform.LuckPermsBridge;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.types.InheritanceNode;

/**
 * One LuckPerms bridge for both platforms.
 *
 * <p>v2 had two, and they had drifted apart in ways that mattered: the Velocity one checked a group
 * existed before granting it and awaited the save; the Bukkit one skipped the existence check on the
 * Velocity side's terms, did not await the save, and resolved the API once at construction so a
 * server where LuckPerms started second had role sync disabled for the whole session (#796 / MC-10).
 * {@code net.luckperms:api} is the same artifact on both families, so there was never a reason for
 * two files — only an opportunity for them to disagree.
 *
 * <p><strong>Never construct this without {@link LuckPermsSupport#isPresent()} first.</strong> This
 * class names LuckPerms types, so linking it on a server without LuckPerms throws.
 *
 * <h2>Where the blocking happens</h2>
 *
 * <p>LuckPerms' storage calls are futures, and this bridge joins them — loading a user, checking a
 * group exists, saving a user back. Every one of those joins runs inside a task submitted to the
 * executor this was built with ({@code heimdall-io} in production), so nothing here ever blocks a
 * server thread, the socket's reading thread, or a login.
 *
 * <p>{@link #getPlayerGroups} falls back to loading the user from storage when they are not cached,
 * which is the normal state during a pre-login check because the player is not on the server yet.
 * Reporting an empty list there is issue #796 / MC-11: the bot diffs against nothing and concludes
 * every managed group needs granting.
 */
final class LuckPermsIntegration implements LuckPermsBridge {

    private final HeimdallLogger logger;
    private final Executor executor;

    /** Resolved lazily and re-resolved while null; never cached as a permanent failure. */
    private volatile LuckPerms luckPerms;

    /** So "LuckPerms integration enabled" is said once, not once per lookup. */
    private volatile boolean announced;

    private LuckPermsIntegration(HeimdallLogger logger, Executor executor) {
        this.logger = logger;
        this.executor = executor;
    }

    /**
     * Builds one, or returns {@code null} if LuckPerms has not registered its service yet.
     *
     * <p>Package-private and reached only through {@link LuckPermsSupport}, which owns the
     * classpath probe that makes touching this class safe.
     */
    static LuckPermsIntegration tryCreate(HeimdallLogger logger, Executor executor) {
        LuckPermsIntegration integration = new LuckPermsIntegration(logger, executor);
        return integration.resolve() == null ? null : integration;
    }

    /** The live API, or {@code null} if LuckPerms has not started yet. Retried on every call. */
    private LuckPerms resolve() {
        LuckPerms resolved = luckPerms;
        if (resolved != null) {
            return resolved;
        }
        try {
            resolved = LuckPermsProvider.get();
        } catch (IllegalStateException notReadyYet) {
            return null;
        }
        luckPerms = resolved;
        if (!announced) {
            announced = true;
            logger.info("LuckPerms integration enabled");
        }
        return resolved;
    }

    @Override
    public boolean isAvailable() {
        return resolve() != null;
    }

    @Override
    public CompletableFuture<List<String>> getPlayerGroups(final UUID playerUuid) {
        final LuckPerms api = resolve();
        if (api == null || playerUuid == null) {
            return CompletableFuture.completedFuture(Collections.<String>emptyList());
        }
        return CompletableFuture.supplyAsync(new java.util.function.Supplier<List<String>>() {
            @Override
            public List<String> get() {
                try {
                    User user = loadUser(api, playerUuid);
                    return user == null ? Collections.<String>emptyList() : groupsOf(user);
                } catch (RuntimeException e) {
                    logger.warn("could not read LuckPerms groups for " + playerUuid + ": "
                            + e.getMessage());
                    return Collections.<String>emptyList();
                }
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Boolean> setPlayerGroups(
            final UUID playerUuid, final List<String> targetGroups, final List<String> managedGroups) {
        final LuckPerms api = resolve();
        if (api == null || playerUuid == null) {
            return CompletableFuture.completedFuture(Boolean.FALSE);
        }
        if (managedGroups == null || managedGroups.isEmpty()) {
            // Not an error, and deliberately not treated as "manage everything" — see GroupDiff.
            logger.debug(() -> "no managed groups for " + playerUuid + "; leaving permissions alone");
            return CompletableFuture.completedFuture(Boolean.FALSE);
        }
        return CompletableFuture.supplyAsync(new java.util.function.Supplier<Boolean>() {
            @Override
            public Boolean get() {
                return apply(api, playerUuid, targetGroups, managedGroups);
            }
        }, executor);
    }

    private Boolean apply(
            LuckPerms api, UUID playerUuid, List<String> targetGroups, List<String> managedGroups) {
        try {
            // loadUser rather than getUser: setPlayerGroups is called for offline players too, and
            // mutating a user that was never loaded silently writes nothing.
            User user = api.getUserManager().loadUser(playerUuid).join();
            if (user == null) {
                logger.warn("could not load " + playerUuid + " from LuckPerms for role sync");
                return Boolean.FALSE;
            }

            GroupDiff diff = GroupDiff.compute(groupsOf(user), targetGroups, managedGroups);
            if (diff.isEmpty()) {
                logger.debug(() -> "no managed group changes needed for " + playerUuid);
                return Boolean.TRUE;
            }

            List<String> removed = new ArrayList<String>();
            for (String group : diff.toRemove()) {
                user.data().remove(InheritanceNode.builder(group).build());
                removed.add(group);
            }

            List<String> added = new ArrayList<String>();
            for (String group : diff.toAdd()) {
                if (!groupExists(api, group)) {
                    // Granting a group that does not exist would produce a permission set nobody
                    // configured, so it is skipped and said out loud.
                    logger.warn("group '" + group + "' does not exist in LuckPerms — not granting it "
                            + "to " + playerUuid);
                    continue;
                }
                user.data().add(InheritanceNode.builder(group).build());
                added.add(group);
            }

            if (added.isEmpty() && removed.isEmpty()) {
                return Boolean.TRUE;
            }
            // Awaited: a caller that waits on this future is entitled to assume the change reached
            // storage rather than only the in-memory model. v2's Bukkit path did not await, so a
            // server stopped in the seconds after a sync lost it.
            api.getUserManager().saveUser(user).join();
            logger.info("role sync for " + playerUuid + " — added " + added + ", removed " + removed);
            return Boolean.TRUE;
        } catch (RuntimeException e) {
            logger.error("role sync failed for " + playerUuid, e);
            return Boolean.FALSE;
        }
    }

    private User loadUser(LuckPerms api, UUID playerUuid) {
        User cached = api.getUserManager().getUser(playerUuid);
        return cached != null ? cached : api.getUserManager().loadUser(playerUuid).join();
    }

    private static List<String> groupsOf(User user) {
        List<String> names = new ArrayList<String>();
        for (Group group : user.getInheritedGroups(user.getQueryOptions())) {
            names.add(group.getName());
        }
        return names;
    }

    private static boolean groupExists(LuckPerms api, String group) {
        return api.getGroupManager().isLoaded(group)
                || api.getGroupManager().loadGroup(group).join().isPresent();
    }
}
