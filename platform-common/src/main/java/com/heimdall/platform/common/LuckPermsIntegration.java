package com.heimdall.platform.common;

import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.platform.GroupsChangedListener;
import com.heimdall.core.platform.LuckPermsBridge;
import com.heimdall.core.util.Registration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.event.EventBus;
import net.luckperms.api.event.EventSubscription;
import net.luckperms.api.event.node.NodeAddEvent;
import net.luckperms.api.event.node.NodeClearEvent;
import net.luckperms.api.event.node.NodeMutateEvent;
import net.luckperms.api.event.node.NodeRemoveEvent;
import net.luckperms.api.event.user.UserDataRecalculateEvent;
import net.luckperms.api.model.data.DataMutateResult;
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

    /** The node events {@link #setPlayerGroups} is about to cause; see {@link OwnWrites}. */
    private final OwnWrites ownWrites = new OwnWrites();

    /**
     * How many {@link #onGroupsChanged} subscriptions are open. Expectations are only recorded while
     * one is, because with nobody listening there is no event to match them against.
     */
    private final AtomicInteger listening = new AtomicInteger();

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
     * The LuckPerms instance registered right now, read from the provider rather than the cached
     * handle, and adopted as the cached handle when it differs: a hot reload replaced it, and the
     * old one is dead. Used by {@link #onGroupsChanged} and {@link #instanceToken}, because a
     * subscription made against the cached handle after a reload would be one on a dead event bus.
     */
    private LuckPerms current() {
        LuckPerms live;
        try {
            live = LuckPermsProvider.get();
        } catch (IllegalStateException notReady) {
            return null;
        }
        if (luckPerms != live) {
            luckPerms = live;
        }
        return live;
    }

    /** The registered LuckPerms instance itself; a reload replaces it. */
    @Override
    public Object instanceToken() {
        return current();
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

            // What the player owns in any context, the same read reverse role sync reports: see
            // ownedGroupsOf for why a contextual read left a server=lobby rank behind on survival.
            GroupDiff diff = GroupDiff.compute(ownedGroupsOf(user), targetGroups, managedGroups);
            if (diff.isEmpty()) {
                logger.debug(() -> "no managed group changes needed for " + playerUuid);
                return Boolean.TRUE;
            }

            List<String> removed = new ArrayList<String>();
            for (String group : diff.toRemove()) {
                // Every node that grants the group, not the one permanent global node: see
                // grantsGroup for why removing only that left a temporary or contextual rank behind.
                Predicate<Node> granting = grantsGroup(group);
                // Expected before the write, because LuckPerms posts the event on its own thread
                // and it can arrive before clear() returns. Only when a node will really go:
                // LuckPerms fires nothing for a clear that removes nothing.
                OwnWrites.Expected echo = holdsAny(user, granting)
                        ? expectOwnWrite(playerUuid, group, false) : null;
                try {
                    user.data().clear(granting);
                } catch (RuntimeException e) {
                    ownWrites.withdraw(playerUuid, echo);
                    throw e;
                }
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
                OwnWrites.Expected echo = expectOwnWrite(playerUuid, group, true);
                DataMutateResult result;
                try {
                    result = user.data().add(InheritanceNode.builder(group).build());
                } catch (RuntimeException e) {
                    ownWrites.withdraw(playerUuid, echo);
                    throw e;
                }
                if (result != null && !result.wasSuccessful()) {
                    // Already there in exactly that form: no change, so no event will come.
                    ownWrites.withdraw(playerUuid, echo);
                }
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
     * What a forward removal of {@code group} deletes: every inheritance node granting it, in any
     * context and with any expiry.
     *
     * <p>Removing {@code InheritanceNode.builder(group).build()} matched only the permanent node with
     * no context, so a rank given with {@code parent addtemp} or with {@code server=X} was logged as
     * removed and stayed. The diff lists a group because the player holds it, so what has to go is
     * whatever node makes them hold it. A negated node ({@code zeal=false}) is left alone: it grants
     * nothing, and deleting it could only widen what the player inherits. The name is compared
     * ignoring case, like {@link GroupDiff}.
     */
    static Predicate<Node> grantsGroup(final String group) {
        return new Predicate<Node>() {
            @Override
            public boolean test(Node node) {
                return node instanceof InheritanceNode
                        && !node.isNegated()
                        && group.equalsIgnoreCase(((InheritanceNode) node).getGroupName());
            }
        };
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
     *       {@link GroupsChangedListener}). It fires every time LuckPerms loads a user as well, so it
     *       is passed on as {@link GroupsChangedListener.Cause#RECALCULATION} and the node events as
     *       {@link GroupsChangedListener.Cause#INHERITANCE_NODE}: only the latter says somebody
     *       edited a rank, which is what decides whether an offline player is reported.
     *   <li>A node event this bridge caused itself, by applying a forward snapshot in
     *       {@link #setPlayerGroups}, is passed on as
     *       {@link GroupsChangedListener.Cause#ROLE_SYNC_WRITE} instead. It is the bot's own push
     *       coming back, not news; see {@link OwnWrites} for how it is recognised.
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
     * <p>Subscribed against the API instance registered now, read fresh from the provider. If
     * LuckPerms is hot-reloaded underneath a running server the old event bus dies with it and this
     * subscription goes quiet; {@link #instanceToken} changes when that happens, and the consuming
     * module subscribes again when it sees it change.
     */
    @Override
    public Registration onGroupsChanged(final GroupsChangedListener listener) {
        final LuckPerms api = current();
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
                        onUserMutated(event, Collections.singletonList(event.getNode()), true, reads);
                    }
                }
            }));
            subscriptions.add(bus.subscribe(NodeRemoveEvent.class, new Consumer<NodeRemoveEvent>() {
                @Override
                public void accept(NodeRemoveEvent event) {
                    if (event.getNode() instanceof InheritanceNode) {
                        onUserMutated(
                                event, Collections.singletonList(event.getNode()), false, reads);
                    }
                }
            }));
            subscriptions.add(bus.subscribe(NodeClearEvent.class, new Consumer<NodeClearEvent>() {
                @Override
                public void accept(NodeClearEvent event) {
                    if (anyInheritance(event.getNodes())) {
                        onUserMutated(event, event.getNodes(), false, reads);
                    }
                }
            }));
            subscriptions.add(bus.subscribe(UserDataRecalculateEvent.class,
                    new Consumer<UserDataRecalculateEvent>() {
                        @Override
                        public void accept(UserDataRecalculateEvent event) {
                            reads.request(event.getUser(), GroupsChangedListener.Cause.RECALCULATION);
                        }
                    }));
        } catch (RuntimeException e) {
            closeAll(subscriptions);
            invalidate(api);
            logger.warn("could not subscribe to LuckPerms group changes: " + e.getMessage());
            return Registration.NONE;
        }
        listening.incrementAndGet();
        return Registration.once(new Runnable() {
            @Override
            public void run() {
                listening.decrementAndGet();
                closeAll(subscriptions);
            }
        });
    }

    /**
     * A user's inheritance node was added, removed or cleared. Tagged as this bridge's own write
     * when it is one {@link #setPlayerGroups} said it was about to make, otherwise as an edit.
     */
    private void onUserMutated(
            NodeMutateEvent event, Collection<? extends Node> nodes, boolean added, GroupReads reads) {
        if (!event.isUser() || !(event.getTarget() instanceof User)) {
            return;
        }
        User user = (User) event.getTarget();
        GroupsChangedListener.Cause cause;
        try {
            cause = ownWrites.consume(user.getUniqueId(), inheritedNames(nodes), added)
                    ? GroupsChangedListener.Cause.ROLE_SYNC_WRITE
                    : GroupsChangedListener.Cause.INHERITANCE_NODE;
        } catch (RuntimeException e) {
            // Never thrown back into LuckPerms' dispatch. Unsure counts as an edit: reporting a
            // write of ours costs a redundant frame, missing an edit costs a stale Discord role.
            cause = GroupsChangedListener.Cause.INHERITANCE_NODE;
        }
        reads.request(user, cause);
    }

    /** Called before a write: records the event it will cause, if anybody is listening for one. */
    private OwnWrites.Expected expectOwnWrite(UUID uuid, String group, boolean added) {
        return listening.get() > 0 ? ownWrites.expect(uuid, group, added) : null;
    }

    private static boolean holdsAny(User user, Predicate<Node> test) {
        Collection<Node> nodes = user.data().toCollection();
        if (nodes == null) {
            return false;
        }
        for (Node node : nodes) {
            if (test.test(node)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> inheritedNames(Collection<? extends Node> nodes) {
        List<String> names = new ArrayList<String>();
        if (nodes != null) {
            for (Node node : nodes) {
                if (node instanceof InheritanceNode) {
                    String name = ((InheritanceNode) node).getGroupName();
                    if (name != null) {
                        names.add(name.toLowerCase(Locale.ROOT));
                    }
                }
            }
        }
        return names;
    }

    /** How long the event of our own write is waited for before the expectation is dropped. */
    static final long OWN_WRITE_TTL_MS = 10_000L;

    /**
     * The node events this bridge's own forward writes are about to cause, so they can be told apart
     * from somebody else's edit when they arrive.
     *
     * <p>Why it matters: {@code data().clear} and {@code data().add} in {@link #setPlayerGroups} fire
     * the same {@link NodeClearEvent} and {@link NodeAddEvent} a {@code /lp user X parent ...} does.
     * Passed on as {@link GroupsChangedListener.Cause#INHERITANCE_NODE}, reverse role sync took each
     * one as a rank edit and reported it even for an offline player, so every server that applied a
     * push for an offline player sent the bot a {@code rolesync.groups} frame describing the push it
     * had just sent.
     *
     * <p>Why not a flag held "while writing": LuckPerms posts node events on its own async executor,
     * so the event of a write can arrive after the write, and the save, have returned. So the write
     * says what it expects (a removal of group {@code g} for this user, or an addition of it)
     * <em>before</em> it mutates, and the first matching event consumes the expectation. Only for a
     * change that will really happen: a clear only when a granting node is there, and an add is
     * withdrawn when LuckPerms says the node was already present. LuckPerms fires nothing for a no-op,
     * and an expectation left waiting could later swallow a real edit.
     *
     * <p>Matched on the user, the direction and the group name (lower-cased): a removal or clear
     * whose inheritance nodes are all for the expected group, or an add of it. A real edit of the
     * same rank on the same user in the moment between our write and its event is the only thing
     * that can be taken for ours, and an expectation lives at most {@value #OWN_WRITE_TTL_MS}ms, so
     * an event that never comes cannot hide an edit for longer than that. Bounded by the writes of
     * that window, pruned whenever one is added.
     */
    static final class OwnWrites {

        static final class Expected {
            final String group;
            final boolean added;
            final long expiresAtMs;

            Expected(String group, boolean added, long expiresAtMs) {
                this.group = group;
                this.added = added;
                this.expiresAtMs = expiresAtMs;
            }
        }

        private final Map<UUID, List<Expected>> expected = new HashMap<UUID, List<Expected>>();

        synchronized Expected expect(UUID uuid, String group, boolean added) {
            long now = System.currentTimeMillis();
            prune(now);
            Expected next = new Expected(
                    group.toLowerCase(Locale.ROOT), added, now + OWN_WRITE_TTL_MS);
            List<Expected> forUser = expected.get(uuid);
            if (forUser == null) {
                forUser = new ArrayList<Expected>();
                expected.put(uuid, forUser);
            }
            forUser.add(next);
            return next;
        }

        /** A write that changed nothing, or failed: no event is coming for it. */
        synchronized void withdraw(UUID uuid, Expected which) {
            if (which == null) {
                return;
            }
            List<Expected> forUser = expected.get(uuid);
            if (forUser != null && forUser.remove(which) && forUser.isEmpty()) {
                expected.remove(uuid);
            }
        }

        /** Whether this event is one we were expecting; consumes the expectation if so. */
        synchronized boolean consume(UUID uuid, List<String> groups, boolean added) {
            if (uuid == null || groups.isEmpty()) {
                return false;
            }
            List<Expected> forUser = expected.get(uuid);
            if (forUser == null) {
                return false;
            }
            long now = System.currentTimeMillis();
            boolean matched = false;
            Iterator<Expected> it = forUser.iterator();
            while (it.hasNext()) {
                Expected candidate = it.next();
                if (candidate.expiresAtMs < now) {
                    it.remove();
                } else if (!matched && candidate.added == added && allAre(groups, candidate.group)) {
                    it.remove();
                    matched = true;
                }
            }
            if (forUser.isEmpty()) {
                expected.remove(uuid);
            }
            return matched;
        }

        synchronized int size() {
            int n = 0;
            for (List<Expected> forUser : expected.values()) {
                n += forUser.size();
            }
            return n;
        }

        private void prune(long now) {
            Iterator<List<Expected>> users = expected.values().iterator();
            while (users.hasNext()) {
                List<Expected> forUser = users.next();
                Iterator<Expected> it = forUser.iterator();
                while (it.hasNext()) {
                    if (it.next().expiresAtMs < now) {
                        it.remove();
                    }
                }
                if (forUser.isEmpty()) {
                    users.remove();
                }
            }
        }

        private static boolean allAre(List<String> groups, String group) {
            for (String name : groups) {
                if (!group.equals(name)) {
                    return false;
                }
            }
            return true;
        }
    }

    /** How many expected echoes of our own writes are outstanding. For tests. */
    int ownWritesOutstanding() {
        return ownWrites.size();
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
     *
     * <p>Coalescing keeps the strongest {@link GroupsChangedListener.Cause}: a node event followed by
     * the recalculation it causes is one read reported as a node event. Reverse role sync reports an
     * offline player only on a node event, because LuckPerms recalculates every user it loads, so
     * letting the recalculation overwrite the cause would drop exactly the offline edits it is meant
     * to report. Somebody's edit outranks this bridge's own write, which outranks a recalculation:
     * a real edit coalesced with one of ours is still an edit.
     */
    final class GroupReads {

        private final Executor serial;
        private final GroupsChangedListener listener;
        private final ConcurrentHashMap<UUID, Queued> queued = new ConcurrentHashMap<UUID, Queued>();

        GroupReads(Executor serial, GroupsChangedListener listener) {
            this.serial = serial;
            this.listener = listener;
        }

        /**
         * Queues a read-and-notify for a user unless one is already queued. Runs on the LuckPerms
         * event thread, so it is cheap and never throws back into LuckPerms: an exception here would
         * be logged as a failure of LuckPerms' own event.
         */
        void request(final User user, final GroupsChangedListener.Cause cause) {
            if (user == null) {
                return;
            }
            final UUID uuid = user.getUniqueId();
            final boolean[] fresh = new boolean[1];
            queued.compute(uuid, (key, waiting) -> {
                if (waiting == null) {
                    fresh[0] = true;
                    return new Queued(user, cause);
                }
                return waiting.merge(user, cause);
            });
            if (!fresh[0]) {
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
            Queued waiting = queued.remove(uuid);
            if (waiting == null) {
                return;
            }
            User user = waiting.user;
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
            String username;
            try {
                username = user.getUsername();
            } catch (RuntimeException e) {
                username = null;
            }
            try {
                listener.onGroupsChanged(
                        uuid, username, Collections.unmodifiableList(groups), waiting.cause);
            } catch (RuntimeException e) {
                logger.warn("a LuckPerms group-change listener failed for " + uuid + ": "
                        + e.getMessage());
            }
        }
    }

    /** One user's queued read: the newest {@link User} seen, and the strongest cause behind it. */
    private static final class Queued {
        final User user;
        final GroupsChangedListener.Cause cause;

        Queued(User user, GroupsChangedListener.Cause cause) {
            this.user = user;
            this.cause = cause;
        }

        Queued merge(User newer, GroupsChangedListener.Cause also) {
            return new Queued(newer, rank(also) > rank(cause) ? also : cause);
        }

        /** An edit over our own write over a recalculation; see {@link GroupReads}. */
        private static int rank(GroupsChangedListener.Cause cause) {
            if (cause == GroupsChangedListener.Cause.INHERITANCE_NODE) {
                return 2;
            }
            return cause == GroupsChangedListener.Cause.ROLE_SYNC_WRITE ? 1 : 0;
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
     * The groups a user <em>owns</em>, in any context: what reverse role sync and the join-time
     * {@code currentGroups} report, and what the forward diff in {@link #setPlayerGroups} reads.
     *
     * <p>{@link QueryOptions#nonContextual()} rather than the user's own query options. "Does this
     * player have the VIP rank" is a question about ownership, and the contextual answer depends on
     * which server is asking: a parent granted with {@code server=lobby} is invisible to the survival
     * server's contextual query, so survival would report the rank as absent and the bot would take
     * the Discord role away. The non-contextual query counts parent nodes whatever their context,
     * and still resolves groups inherited through other groups.
     *
     * <p>The forward diff reads the same thing, because the two disagreeing was a bug in both
     * directions. With {@code zeal} held as {@code server=lobby} and a push dropping it applied on
     * survival, a contextual diff did not see zeal as held, so it was never listed for removal and
     * stayed; survival's reverse frames then kept reporting it, and the bot kept being told about a
     * rank it had just removed. Read non-contextually, a managed group held in any context is held:
     * a removal clears it in every context ({@link #grantsGroup}), and a target that names it does
     * not add a second, global node beside the contextual one. Negated nodes are left alone, as
     * {@link #grantsGroup} says.
     *
     * <p>Still {@code getInheritedGroups}, not the directly-held nodes, which is v2's behaviour and
     * what non-departure <strong>N7</strong> in {@code docs/v2-departures.md} is about. Its two
     * consequences stand: an inherited managed group is never <em>added</em> (correct, since
     * granting it would change nothing), and the same group, when the dashboard drops it from the
     * target set, <em>is</em> listed for removal, which does nothing because the player still
     * inherits it, so the sync logs a removal that did not take effect. N7 also says not to "fix"
     * that without the bot side in the room: diffing against directly-held nodes changes which groups
     * get written on every sync for every server that uses group inheritance. Counting every context
     * is a narrower change: it only differs from the contextual read for a parent granted in a
     * context this server is not in.
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
