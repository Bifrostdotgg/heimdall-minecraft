package com.heimdall.core.platform;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Reading and writing a player's LuckPerms groups, without core knowing what LuckPerms is.
 *
 * <h2>Only managed groups are ever touched</h2>
 *
 * <p>{@link #setPlayerGroups} takes a <em>managed</em> list as well as a target list, and the two
 * are not interchangeable. The managed list is the set of groups the dashboard is allowed to have
 * an opinion about; everything else a player holds — a rank they bought, a staff group, a group
 * another plugin owns — is out of scope and must survive a sync untouched.
 *
 * <p>Concretely: a group is removed only if it is currently held, is in the managed list, and is
 * not in the target list. A group is added only if it is in the target list, is in the managed
 * list, and is not already held. An empty managed list means the bot has not told this server what
 * it owns, and the correct response is to change nothing at all — not to interpret it as "manage
 * everything", which would strip every group on the server.
 *
 * <h2>Availability is asked, not assumed</h2>
 *
 * <p>LuckPerms registers its service on its own schedule and there is no load-order guarantee, so
 * an implementation that resolved once at construction and cached a failure would leave role sync
 * dead for the lifetime of the process — issue #796 / MC-10, which is exactly what v2's Bukkit
 * implementation did. Every method here re-resolves, so a server where LuckPerms started second
 * heals itself.
 *
 * <h2>Threading</h2>
 *
 * <p>Every method returns a future and none of them blocks the caller. The blocking that LuckPerms
 * itself requires — loading a user from storage, saving one back — happens on {@code heimdall-io},
 * never on a server thread and never on the socket's reading thread.
 */
public interface LuckPermsBridge {

    /** Whether LuckPerms is present and its API has been resolved. Re-checked on every call. */
    boolean isAvailable();

    /**
     * The groups a player currently inherits, including ones they are not online to hold.
     *
     * <p>Falls back to loading the user from storage when they are not in LuckPerms' cache — which
     * is the normal state during a pre-login check, since the player is not on the server yet.
     * Reporting an empty list there is issue #796 / MC-11: the bot then diffs against nothing and
     * concludes every managed group needs adding.
     *
     * @return the group names; empty if LuckPerms is absent or the user could not be loaded
     */
    CompletableFuture<List<String>> getPlayerGroups(UUID playerUuid);

    /**
     * Applies the bot's group snapshot, touching only what {@code managedGroups} covers.
     *
     * <p>A group in {@code targetGroups} that does not exist in LuckPerms is skipped with a
     * warning rather than created: inventing a group because the dashboard named one that was
     * deleted would produce a permission set nobody configured.
     *
     * <p>The user is saved before the future completes, so a caller that waits for it can rely on
     * the change having reached storage rather than only the in-memory model.
     *
     * @param targetGroups the groups the player should hold; {@code null} is treated as empty
     * @param managedGroups the groups the dashboard owns; {@code null} or empty means change nothing
     * @return {@code true} if the sync ran to completion, {@code false} if it was skipped or failed
     */
    CompletableFuture<Boolean> setPlayerGroups(
            UUID playerUuid, List<String> targetGroups, List<String> managedGroups);
}
