package com.heimdall.platform.common;

import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.platform.GroupsChangedListener;
import com.heimdall.core.platform.LuckPermsBridge;
import com.heimdall.core.util.Registration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.event.EventBus;
import net.luckperms.api.event.EventSubscription;
import net.luckperms.api.event.node.NodeAddEvent;
import net.luckperms.api.event.node.NodeClearEvent;
import net.luckperms.api.event.node.NodeMutateEvent;
import net.luckperms.api.event.node.NodeRemoveEvent;
import net.luckperms.api.event.user.UserDataRecalculateEvent;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.types.InheritanceNode;
import net.luckperms.api.query.QueryOptions;

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

    /**
     * Resolved lazily, re-resolved while null, and <strong>dropped again whenever a call fails</strong>.
     *
     * <p>The negative case is issue #796 / MC-10: a bridge resolved once at construction caches a
     * permanent failure on a server where LuckPerms simply started second, and role sync is dead for
     * the life of the process. That is why it is retried while null.
     *
     * <p>The positive case is the mirror image and was the surviving gap. LuckPerms can be replaced
     * under a running server — a hot reload through PlugMan or the like — and the handle held here
     * then points at a shut-down instance while {@link #isAvailable()} keeps answering {@code true}.
     * Every call would fail, forever, and nothing in the plugin would ever look again. So a failed
     * call clears it: the next call re-resolves and picks up whatever instance is live now.
     *
     * <p>Clearing on <em>any</em> failure rather than trying to recognise a dead API is deliberate.
     * {@code LuckPermsProvider.get()} is a field read against a live singleton, so a needless
     * re-resolve after an ordinary storage error costs nothing — and a check that tried to tell the
     * two apart would be a guess about another plugin's exception types.
     */
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

    /**
     * Fails, rather than answering an empty list, whenever the groups are not actually known: no
     * LuckPerms, no uuid, a user LuckPerms could not load, or a read that threw.
     *
     * <p>An empty list is a real answer ("holds no groups") and the bot acts on it: it diffs against
     * nothing (issue #796 / MC-11), and for reverse role sync it would read as every mapped rank being
     * gone and strip the Discord roles. Every caller already has a path for "unknown" (the join calls
     * leave {@code currentGroups} out), so a failed future is what sends them down it.
     *
     * <p>Reads {@linkplain #ownedGroupsOf the groups the user owns in any context}, not the ones that
     * apply under this server's context.
     */
    @Override
    public CompletableFuture<List<String>> getPlayerGroups(final UUID playerUuid) {
        final LuckPerms api = resolve();
        if (api == null || playerUuid == null) {
            CompletableFuture<List<String>> unknown = new CompletableFuture<List<String>>();
            unknown.completeExceptionally(new IllegalStateException(api == null
                    ? "LuckPerms is not available" : "no player uuid"));
            return unknown;
        }
        return CompletableFuture.supplyAsync(new java.util.function.Supplier<List<String>>() {
            @Override
            public List<String> get() {
                User user;
                try {
                    user = loadUser(api, playerUuid);
                } catch (RuntimeException e) {
                    invalidate(api);
                    throw e;
                }
                if (user == null) {
                    throw new IllegalStateException("LuckPerms could not load " + playerUuid);
                }
                return ownedGroupsOf(user);
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
            invalidate(api);
            logger.error("role sync failed for " + playerUuid, e);
            return Boolean.FALSE;
        }
    }

    /**
     * Subscribes to the LuckPerms events that can change a user's inheritance groups.
     *
     * <h2>Which events, and why these</h2>
     *
     * <ul>
     *   <li>{@link NodeAddEvent} and {@link NodeRemoveEvent}, only when the target is a user and the
     *       node is an {@link InheritanceNode}. That is {@code /lp user X parent add|remove}, and what
     *       store plugins (Tebex included) run when a rank is bought or refunded. A permission or
     *       meta node changes nothing about groups, and a node on a <em>group</em> is not a user
     *       change.
     *   <li>{@link NodeClearEvent} on a user, when any cleared node was an inheritance node:
     *       {@code parent clear} goes through it rather than through a remove.
     *   <li>{@link UserDataRecalculateEvent}, unfiltered, as the catch-all for any path that changes
     *       a user's groups without one of the node events above. The 5.4 API documents it as firing
     *       when a user's cached data is recalculated; it does not document which changes lead to a
     *       recalculation. In particular this class does <strong>not</strong> assume that an expiring
     *       temporary rank, or an edit to a group a user inherits, reaches it: that has not been
     *       verified against LuckPerms itself, and the join-time {@code currentGroups} is what is
     *       relied on for those. It also fires for plenty that changes no group, which is why the
     *       listener gets the full list and decides for itself whether anything moved (see
     *       {@link GroupsChangedListener}).
     * </ul>
     *
     * <h2>Threads</h2>
     *
     * <p>LuckPerms posts these on its own async event executor. The handlers here do nothing on that
     * thread but pick out the {@link User} and queue the rest: reading the inherited groups resolves
     * the whole inheritance graph, which is not work to do on LuckPerms' dispatch thread, and the
     * listener is Heimdall code with no business running there either.
     *
     * <p>The queue is {@link GroupReads}: coalesced per user (at most one pending read each, which
     * reads the latest state when it runs) and drained one read at a time, in order, by a
     * {@link SerialExecutor} over {@code heimdall-io}. The ordering is what guarantees the last call a
     * listener sees for a user carries the newest state; on a plain multi-thread pool two reads for
     * one user could finish in either order, and a listener that keeps "the latest call wins" would
     * then keep the older one.
     *
     * <p>The {@link User} carried by the event is read rather than looking the uuid up again, because
     * a user edited while offline is loaded only for the duration of the edit: a fresh lookup could
     * find nothing, or load a second copy from storage.
     *
     * <p>Subscribed against the API instance resolved now. If LuckPerms is hot-reloaded underneath a
     * running server the old event bus dies with it and this subscription goes quiet; that is the same
     * edge {@link #luckPerms} documents for every other call, and it heals the next time the consuming
     * module subscribes.
     */
    @Override
    public Registration onGroupsChanged(final GroupsChangedListener listener) {
        final LuckPerms api = resolve();
        if (api == null || listener == null) {
            return Registration.NONE;
        }
        final GroupReads reads = new GroupReads(new SerialExecutor(executor, logger), listener);
        final List<EventSubscription<?>> subscriptions = new ArrayList<EventSubscription<?>>();
        try {
            EventBus bus = api.getEventBus();
            subscriptions.add(bus.subscribe(NodeAddEvent.class, new Consumer<NodeAddEvent>() {
                @Override
                public void accept(NodeAddEvent event) {
                    if (event.getNode() instanceof InheritanceNode) {
                        onUserMutated(event, reads);
                    }
                }
            }));
            subscriptions.add(bus.subscribe(NodeRemoveEvent.class, new Consumer<NodeRemoveEvent>() {
                @Override
                public void accept(NodeRemoveEvent event) {
                    if (event.getNode() instanceof InheritanceNode) {
                        onUserMutated(event, reads);
                    }
                }
            }));
            subscriptions.add(bus.subscribe(NodeClearEvent.class, new Consumer<NodeClearEvent>() {
                @Override
                public void accept(NodeClearEvent event) {
                    if (anyInheritance(event.getNodes())) {
                        onUserMutated(event, reads);
                    }
                }
            }));
            subscriptions.add(bus.subscribe(UserDataRecalculateEvent.class,
                    new Consumer<UserDataRecalculateEvent>() {
                        @Override
                        public void accept(UserDataRecalculateEvent event) {
                            reads.request(event.getUser());
                        }
                    }));
        } catch (RuntimeException e) {
            closeAll(subscriptions);
            invalidate(api);
            logger.warn("could not subscribe to LuckPerms group changes: " + e.getMessage());
            return Registration.NONE;
        }
        return Registration.once(new Runnable() {
            @Override
            public void run() {
                closeAll(subscriptions);
            }
        });
    }

    private static void onUserMutated(NodeMutateEvent event, GroupReads reads) {
        if (event.isUser() && event.getTarget() instanceof User) {
            reads.request((User) event.getTarget());
        }
    }

    /**
     * The read queue behind one subscription: at most one pending read per user, run in order.
     *
     * <p>Every node change fires twice over (the node event, then the recalculation), and a bulk edit
     * fires for every user it touches, so an uncoalesced queue grows with the event rate rather than
     * with the number of users changing. Here a user with a read already queued is not queued again:
     * the newest {@link User} object replaces the queued one, and the read, when it runs, sees the
     * newest state anyway. The pending entry is removed <em>before</em> the read starts, so an event
     * that lands mid-read queues a fresh one rather than being absorbed by a read that may already
     * have looked. The queue is therefore bounded by the number of distinct users with a change
     * outstanding.
     *
     * <p>The {@link SerialExecutor} underneath runs reads one at a time in queue order. With at most
     * one queued read per user, that is what guarantees the last call a listener sees for a user
     * carries a state no older than any earlier call's.
     */
    final class GroupReads {

        private final Executor serial;
        private final GroupsChangedListener listener;
        private final ConcurrentHashMap<UUID, User> queued = new ConcurrentHashMap<UUID, User>();

        GroupReads(Executor serial, GroupsChangedListener listener) {
            this.serial = serial;
            this.listener = listener;
        }

        /**
         * Queues a read-and-notify for a user unless one is already queued. Runs on the LuckPerms
         * event thread, so it is cheap and never throws back into LuckPerms: an exception here would
         * be logged as a failure of LuckPerms' own event.
         */
        void request(User user) {
            if (user == null) {
                return;
            }
            final UUID uuid = user.getUniqueId();
            if (queued.put(uuid, user) != null) {
                return;
            }
            try {
                serial.execute(new Runnable() {
                    @Override
                    public void run() {
                        read(uuid);
                    }
                });
            } catch (RejectedExecutionException shuttingDown) {
                // The plugin's pools are going down; there is nobody left to tell.
                queued.remove(uuid);
            }
        }

        private void read(final UUID uuid) {
            User user = queued.remove(uuid);
            if (user == null) {
                return;
            }
            List<String> groups;
            try {
                groups = ownedGroupsOf(user);
            } catch (RuntimeException e) {
                // Not reported as "no groups": an empty list would read as every watched group
                // having been removed. Skipping costs one change, which the next event or the
                // next join reconciles.
                final String reason = e.getMessage();
                logger.debug(() -> "could not read LuckPerms groups for " + uuid
                        + " after a change: " + reason);
                return;
            }
            try {
                listener.onGroupsChanged(uuid, Collections.unmodifiableList(groups));
            } catch (RuntimeException e) {
                logger.warn("a LuckPerms group-change listener failed for " + uuid + ": "
                        + e.getMessage());
            }
        }
    }

    private static boolean anyInheritance(Collection<Node> nodes) {
        if (nodes == null) {
            return false;
        }
        for (Node node : nodes) {
            if (node instanceof InheritanceNode) {
                return true;
            }
        }
        return false;
    }

    private static void closeAll(List<EventSubscription<?>> subscriptions) {
        for (EventSubscription<?> subscription : subscriptions) {
            try {
                subscription.close();
            } catch (RuntimeException ignored) {
                // A LuckPerms that has already shut down has already dropped it.
            }
        }
        subscriptions.clear();
    }

    /**
     * Forgets a resolved API that has just failed, so the next call resolves again.
     *
     * <p>Compare-and-set against the handle the failing call actually used: another thread may have
     * re-resolved a good one in the meantime, and clearing that would only cost an extra lookup —
     * but doing it under a condition keeps this from being a write that fires on every concurrent
     * failure.
     */
    private synchronized void invalidate(LuckPerms failed) {
        if (luckPerms == failed) {
            luckPerms = null;
        }
    }

    private User loadUser(LuckPerms api, UUID playerUuid) {
        User cached = api.getUserManager().getUser(playerUuid);
        return cached != null ? cached : api.getUserManager().loadUser(playerUuid).join();
    }

    /**
     * The groups a user holds, <em>including</em> ones inherited through another group.
     *
     * <p>{@code getInheritedGroups} rather than the directly-held nodes. That is v2's behaviour and
     * is kept deliberately: non-departure <strong>N7</strong> in {@code docs/v2-departures.md} is
     * about this exact line, and it records the two consequences. An inherited managed group is
     * never *added* — correct, since granting it would change nothing. The same group, when the
     * dashboard drops it from the target set, <em>is</em> listed for removal, and removing the
     * direct node does nothing because the player still inherits it, so the sync logs a removal that
     * did not take effect.
     *
     * <p>N7 also says not to "fix" this without the bot side in the room: diffing against
     * directly-held nodes changes which groups get written on every sync for every server that uses
     * group inheritance, which is most of them.
     */
    private static List<String> groupsOf(User user) {
        List<String> names = new ArrayList<String>();
        for (Group group : user.getInheritedGroups(user.getQueryOptions())) {
            names.add(group.getName());
        }
        return names;
    }

    /**
     * The groups a user <em>owns</em>, in any context: what reverse role sync and the join-time
     * {@code currentGroups} report.
     *
     * <p>{@link QueryOptions#nonContextual()} rather than the user's own query options. "Does this
     * player have the VIP rank" is a question about ownership, and the contextual answer depends on
     * which server is asking: a parent granted with {@code server=lobby} is invisible to the survival
     * server's contextual query, so survival would report the rank as absent and the bot would take
     * the Discord role away. The non-contextual query counts parent nodes whatever their context,
     * and still resolves groups inherited through other groups.
     *
     * <p>Deliberately <strong>not</strong> used by {@link #setPlayerGroups}'s diff, which stays on
     * {@link #groupsOf}: departure N7 records that changing what the forward diff reads changes which
     * groups are written on every sync, and that is not a change to make as a side effect.
     */
    static List<String> ownedGroupsOf(User user) {
        List<String> names = new ArrayList<String>();
        for (Group group : user.getInheritedGroups(QueryOptions.nonContextual())) {
            names.add(group.getName());
        }
        return names;
    }

    private static boolean groupExists(LuckPerms api, String group) {
        return api.getGroupManager().isLoaded(group)
                || api.getGroupManager().loadGroup(group).join().isPresent();
    }
}
