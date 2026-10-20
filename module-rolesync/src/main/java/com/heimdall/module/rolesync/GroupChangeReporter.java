package com.heimdall.module.rolesync;

import com.heimdall.core.json.Envelope;
import com.heimdall.core.json.Payload;
import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.module.ModuleContext;
import com.heimdall.core.platform.GroupsChangedListener;
import com.heimdall.core.platform.LuckPermsBridge;
import com.heimdall.core.platform.PlayerHandle;
import com.heimdall.core.remoteconfig.ModuleConfig;
import com.heimdall.core.remoteconfig.ModuleConfigListener;
import com.heimdall.core.session.PlayerSessionListener;
import com.heimdall.core.tunnel.ProtocolMode;
import com.heimdall.core.tunnel.ProtocolModeListener;
import com.heimdall.core.tunnel.TunnelMessageHandler;
import com.heimdall.core.util.Registration;
import com.heimdall.core.util.Strings;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * Reverse role sync: tells the bot when a player's in-game rank changes, so it can grant or remove
 * the Discord role mapped to it.
 *
 * <p>The forward direction (Discord role to LuckPerms group) is {@link RoleSyncApplier}'s. This is
 * the other one, and it is deliberately the thinner half: the plugin reports, the bot decides. The
 * plugin never learns which Discord role a group maps to, and never acts on its own report.
 *
 * <h2>What is watched, and where that comes from</h2>
 *
 * <p>The module's {@code watchedGroups} setting, a list of LuckPerms group names, computed by the bot
 * from the dashboard's Minecraft-to-Discord mappings. Absent or empty means report nothing, and in
 * that state this class does not even subscribe to LuckPerms: most guilds never map a rank to a role,
 * and they should pay nothing for the feature.
 *
 * <p>Read through {@link ModuleContext#settings()} at the point of use, and re-evaluated on every
 * change to this module's config, because a settings change does not re-enable a module. The
 * listener is what turns the subscription on and off when the list goes from empty to non-empty and
 * back; the per-use read is what makes an edited list apply to the very next change without a
 * restart.
 *
 * <h2>When a frame is sent</h2>
 *
 * <p>Per online player, this keeps a record of the watched groups the bot is known to have been told
 * about. A change is reported when the player's current groups, intersected with the watched list,
 * differ from that record intersected with the watched list. A watched group being
 * <strong>removed</strong> counts as a difference exactly like one being added; that is how a refund
 * reaches Discord. (An expiry reaches it too if LuckPerms signals one while the player is online;
 * the API does not promise that it does, and the join-time reconcile is what covers it if not.)
 *
 * <p><strong>No record means unknown, not empty.</strong> A player with no record is reported on
 * any LuckPerms change at all, without a comparison. This used to compare them against the empty
 * set, and that hid removals: a player who joined holding a watched rank, with no record, and then
 * lost it compared {@code {}} with {@code {}} and nothing was sent, so Discord kept the role. The
 * bot compares sets, so a frame that tells it nothing new is a no-op on its side, and once one has
 * been sent the record exists and later changes are compared as usual.
 *
 * <p><strong>A record exists only for what the bot was verifiably told.</strong> It is written when a
 * frame is sent, and when a join-time call from <em>this</em> server succeeded carrying
 * {@code currentGroups} from this server (the whitelist's {@code connection-attempt}, or this module's
 * {@code role-sync/snapshot}), reported through {@link #delivered}. It is never taken from reading
 * LuckPerms locally at join: when this server made no call (a backend that defers to its gatekeeper,
 * whose LuckPerms may be elsewhere), when the call failed, or when the bot was unusable, a local read
 * would describe what the bot has <em>not</em> seen and suppress the one report that would have told
 * it. An unrecorded player is reported on their next change of any kind (see above), so the cost of
 * leaving one unrecorded is frames the bot may already agree with, never a frame withheld.
 *
 * <p><strong>A delivery older than a frame does not overwrite it.</strong> A join call reads the
 * groups, and its answer can arrive after a rank change was already reported in a frame. Recording
 * the call's older list then would describe what the frame has since corrected, and the next change
 * would be compared against the wrong record. So each call is stamped from a shared sequence
 * ({@link com.heimdall.core.roles.RoleSyncSink#groupsRequestStarting}) before it reads the groups,
 * each frame sent takes the next value for its player, and a delivery (or a parked one, when the
 * join adopts it) whose stamp is older than its player's last frame is dropped. Those per-player
 * frame marks are kept for {@value #PARKED_DELIVERY_TTL_MS}ms, far longer than any join call takes,
 * and pruned as frames are sent, so they are bounded by the frames of the last two minutes.
 *
 * <p>The record is intersected with the watched list <em>at the time it is written</em>, and both
 * sides are intersected with the current list at comparison, so a group added to the list after the
 * join (a new dashboard mapping the bot did not apply at join) is reported at the player's next
 * change, and a group dropped from it is not reported as a removal.
 *
 * <p>Every record is dropped when the tunnel's protocol mode changes, that is on every disconnect and
 * reconnect. A frame sent into a socket that was already dying is dropped without an error, so after a
 * reconnect nothing here can be sure what reached the bot. Dropping and clearing bump a generation
 * counter, and a write that started before the bump (a frame in flight, a delivery racing a
 * disconnect) is discarded rather than repopulating the cleared state.
 *
 * <p>The frame carries the player's <em>full</em> current group list, not the intersection and not a
 * delta, so the bot can answer for mappings this server's settings have not caught up with yet.
 *
 * <p>Its {@code at} is when that list was read from LuckPerms, not when the frame was sent. A
 * debounced report goes out up to {@value #DEBOUNCE_MS}ms after its newest read, and the bot orders
 * a player's frames by {@code at} and weighs them against its own pushes; stamping the send time
 * would make a list look newer than it is, and could let it win over a Discord-side change made in
 * between. A resync answer's {@code at} is when its read completed.
 *
 * <h2>Offline players: real rank edits only</h2>
 *
 * <p>A change to a player who is not online on this server (a console edit, a store command run for
 * an offline buyer) is reported too, but only when it was an actual edit: LuckPerms signalled an
 * inheritance node being added, removed or cleared
 * ({@link GroupsChangedListener.Cause#INHERITANCE_NODE}). A bare recalculation is never enough for
 * an offline player, because LuckPerms recalculates every user it loads: a login still in progress,
 * another plugin looking somebody up, a storage sync. Taking those as changes would send a frame for
 * every user LuckPerms touches. With shared LuckPerms storage only the server that ran the edit fires
 * the node event, so a network sends one frame for it, not one per server. Waiting for the next login
 * instead, as this once did, left a removed rank's Discord role in place for as long as the player
 * stayed away.
 *
 * <p>A node event caused by this plugin's own forward write ({@code role_sync} or a join snapshot
 * applied through {@link LuckPermsBridge#setPlayerGroups}) arrives as
 * {@link GroupsChangedListener.Cause#ROLE_SYNC_WRITE} and is not an edit either: it is the bot's own
 * push coming back. Taken as one, every server that applied a push for an offline player answered it
 * with a frame. For an online player it arms a report as any callback does, and the bot's set
 * comparison makes that echo a no-op.
 *
 * <p>An offline report carries the full group list, its read time as {@code at}, and the name
 * LuckPerms has stored for the user, because the bot needs a username; a user LuckPerms has no name
 * for is not reported. There is no record for an offline player and no comparison: a record per offline player
 * would be a cache with no bound and no owner, and the bot comparing sets makes a redundant frame
 * harmless. A recalculation for an offline player whose report is already armed (an edit's own
 * recalculation, arriving separately) only refreshes the groups that report will carry.
 *
 * <p>A delivery for a player not online yet (the blocking login path answers before the join) is
 * parked until their join moves it into the record, and dropped after
 * {@value #PARKED_DELIVERY_TTL_MS}ms if they never arrive, so a refused or abandoned login cannot grow
 * it.
 *
 * <h2>Debounce</h2>
 *
 * <p>A rank purchase is rarely one event: a store removes the old rank and adds the new one, and
 * LuckPerms then recalculates the user. The first callback for a player arms a one-shot
 * {@value #DEBOUNCE_MS}ms out; every callback behind it only replaces the groups the armed one will
 * read. The comparison runs once, when it fires, against the newest state, so a purchase is at most
 * one frame. A fixed window from the first callback rather than one that restarts on every callback,
 * so a player whose groups change continuously still gets a report every half second rather than
 * never.
 *
 * <p><strong>A quit flushes, it does not cancel.</strong> A player who quits or is kicked inside the
 * window has their armed report sent at once, under the name they had, compared against their record
 * before it is dropped. Cancelling it, as this once did, lost the report for good: "remove the rank,
 * then ban" took the rank away in game and left the Discord role, because the ban's kick landed inside
 * the window and nothing would ever report the change again.
 *
 * <p>The timer lives on {@code heimdall-sched}, and the comparison and send are handed straight to
 * {@code heimdall-io}: the scheduler is single-threaded and shared with every poll the plugin runs.
 * The handles are owned here rather than registered through the context, for the reason
 * {@code WhitelistChangeNudge} records: the context's tracking bag is unbounded and only emptied on
 * disable, and this would add an entry per rank change for the life of the module.
 *
 * <h2>Resync on request</h2>
 *
 * <p>The bot sends {@code rolesync.resync} ({@code {uuid, username?}}, a notification with no reply
 * frame of its own) to a client that declared {@code rolesyncresync@1}, when it needs to hear a
 * player's ranks without waiting for them to change: chiefly right after a player links, because a
 * rank they already held was never news to anybody. The answer is an ordinary {@code rolesync.groups}
 * frame with the full current list, read time as {@code at}, sent unconditionally (no comparison with
 * the record: the bot asked because it does not know), and then written as the record, exactly as
 * a reported change is. Only when the player is online here and LuckPerms is available; otherwise
 * the notification is ignored, because a frame from a server the player is not on could describe a
 * LuckPerms that is not theirs. Answered whatever is watched, since the bot is the one that knows
 * which mappings it wants.
 *
 * <h2>Fire and forget</h2>
 *
 * <p>Sent with {@code TunnelBus.send}: no outbox and no retry. When the tunnel is down at the moment
 * a report would go out, nothing is sent and the record is left as it was, so the player's next
 * change reports the difference again; failing that, their next join reconciles it.
 *
 * <h2>No LuckPerms, or a LuckPerms that will not take a listener</h2>
 *
 * <p>Silent. The module already says once that LuckPerms is missing, on the apply path (see
 * {@link RoleSyncApplier}). A subscription that comes back as {@link Registration#NONE} is treated as
 * no subscription at all, so the next player join or config change tries again; that is also how a
 * LuckPerms that started after this module is picked up. While subscribed, a retry costs two field
 * reads: the bridge's {@link LuckPermsBridge#instanceToken} is compared with the one subscribed
 * against, and a LuckPerms hot reload, which silently kills every listener on the old instance's
 * event bus, is answered by subscribing to the new one at the next join or config change. Checking
 * only whether a subscription existed, as this once did, left reverse role sync deaf for the life of
 * the process after a reload.
 *
 * <h2>Threading</h2>
 *
 * <p>{@link #start} and {@link #close} run on the module manager's thread. The LuckPerms callback
 * arrives on {@code heimdall-io} (the bridge hops there), config and mode changes on the socket's
 * reading thread, joins, quits and {@code rolesync.resync} on {@code heimdall-io} (a resync's answer
 * on whichever thread completed the LuckPerms read), deliveries on whichever thread completed the
 * join call. Subscribing and unsubscribing are serialised on {@link #lock}; record writes and clears
 * on {@link #records}; the debounce's hand-off between a callback and the firing timer is one atomic
 * {@code compute}/{@code remove} per player.
 */
final class GroupChangeReporter {

    /** The frame type the bot handles. Plugin to bot, a notification: nothing comes back. */
    static final String FRAME_TYPE = "rolesync.groups";

    /**
     * The frame type the bot sends to ask for a player's groups now. Bot to plugin, a notification:
     * the answer is a {@link #FRAME_TYPE} frame, never a reply.
     */
    static final String RESYNC_FRAME_TYPE = "rolesync.resync";

    /** The setting the bot fills from the dashboard's Minecraft-to-Discord mappings. */
    static final String WATCHED_GROUPS = "watchedGroups";

    /** How long one burst of LuckPerms events is collapsed over. */
    static final long DEBOUNCE_MS = 500L;

    /** How long a delivery waits for its player to join before it is dropped. */
    static final long PARKED_DELIVERY_TTL_MS = 120_000L;

    /**
     * One player's armed report: the newest groups seen, the name to send them under, and the timer
     * that will read them.
     *
     * <p>Only armed while the player is online or by a real rank edit (see {@link #changed}), so
     * whatever is armed is reportable whether or not the player is still here when it fires.
     */
    private static final class Pending {
        volatile List<String> groups;
        /** When {@link #groups} was read from LuckPerms: what the frame's {@code at} says. */
        volatile long readAtMs;
        /** The online name while the player was here, otherwise the one LuckPerms stored. */
        volatile String username;
        volatile ScheduledFuture<?> timer;
    }

    /** A delivery for a player who has not joined yet. */
    private static final class Parked {
        final Set<String> watched;
        final long atMs;
        final long stamp;

        Parked(Set<String> watched, long atMs, long stamp) {
            this.watched = watched;
            this.atMs = atMs;
            this.stamp = stamp;
        }
    }

    /** When a frame last went out for a player: its place in the sequence, and the wall clock. */
    private static final class FrameMark {
        final long seq;
        final long atMs;

        FrameMark(long seq, long atMs) {
            this.seq = seq;
            this.atMs = atMs;
        }
    }

    private final ModuleContext ctx;
    private final HeimdallLogger logger;
    private final long debounceMs;

    /** Shared with the module's join-call stamps; see the class javadoc. */
    private final java.util.concurrent.atomic.AtomicLong sequence;

    /** Guards {@link #subscription}: subscribe and unsubscribe must not interleave. */
    private final Object lock = new Object();

    /** The live LuckPerms subscription, or {@code null} while nothing is watched or it failed. */
    private Registration subscription;

    /** The bridge and the LuckPerms instance {@link #subscription} was made against. */
    private LuckPermsBridge subscribedBridge;
    private Object subscribedInstance;

    /**
     * Guards {@link #reported}, {@link #parked}, {@link #lastFrame} and {@link #generation}
     * together, so a write that checks the generation cannot land after a clear it did not see, and a
     * delivery cannot be recorded between a frame's mark and its send.
     */
    private final Object records = new Object();

    /** Per online player: the watched groups the bot is known to have been told about. */
    private final Map<UUID, Set<String>> reported = new ConcurrentHashMap<UUID, Set<String>>();

    /** Deliveries waiting for their player's join. */
    private final Map<UUID, Parked> parked = new ConcurrentHashMap<UUID, Parked>();

    /** Per player, the last frame sent. Guarded by {@link #records}; pruned by age. */
    private final Map<UUID, FrameMark> lastFrame = new java.util.HashMap<UUID, FrameMark>();

    /** Bumped on every clear. A write carrying an older value is stale and discarded. */
    private long generation;

    private final ConcurrentHashMap<UUID, Pending> pending = new ConcurrentHashMap<UUID, Pending>();

    private volatile boolean closed;

    GroupChangeReporter(ModuleContext ctx, java.util.concurrent.atomic.AtomicLong sequence) {
        this(ctx, sequence, DEBOUNCE_MS);
    }

    /** The window is injectable so a test can say "inside the window" without racing a clock. */
    GroupChangeReporter(
            ModuleContext ctx, java.util.concurrent.atomic.AtomicLong sequence, long debounceMs) {
        this.ctx = ctx;
        this.logger = ctx.logger();
        this.sequence = sequence;
        this.debounceMs = Math.max(0L, debounceMs);
    }

    /**
     * Registers the config, mode, join and quit listeners through the context (so the manager unwinds
     * them) and subscribes to LuckPerms if anything is watched.
     */
    void start() {
        ctx.onConfigChanged(new ModuleConfigListener() {
            @Override
            public void onModuleConfigChanged(
                    String moduleId, ModuleConfig previous, ModuleConfig current) {
                refresh();
            }
        });
        ctx.tunnel().onModeChange(new ProtocolModeListener() {
            @Override
            public void onModeChanged(ProtocolMode previous, ProtocolMode current) {
                if (previous != current) {
                    clearRecords();
                    logger.debug(() -> "tunnel " + previous + " -> " + current
                            + "; forgetting what the bot was told about ranks");
                }
            }
        });
        ctx.onPlayerJoin(new PlayerSessionListener() {
            @Override
            public void onPlayerSession(PlayerHandle player, long timestampMs) {
                // Also the retry for a LuckPerms that started late or refused a listener.
                refresh();
                adoptParked(player.uuid());
            }
        });
        // Tracked by the context, so the manager unwinds it. Dispatched on heimdall-io.
        ctx.tunnel().subscribe(RESYNC_FRAME_TYPE, new TunnelMessageHandler() {
            @Override
            public void onMessage(Envelope envelope) {
                try {
                    Payload payload = envelope.payload();
                    resync(payload.string("uuid", null), payload.string("username", null));
                } catch (RuntimeException e) {
                    logger.warn("rolesync.resync could not be handled; the tunnel is unaffected: "
                            + e);
                }
            }
        });
        ctx.onPlayerQuit(new PlayerSessionListener() {
            @Override
            public void onPlayerSession(PlayerHandle player, long timestampMs) {
                // Flushed before the record is dropped, so the report compares against it.
                flush(player);
                forget(player.uuid());
            }
        });
        refresh();
    }

    /** Stops listening and forgets every player. Idempotent; safe after a failed {@link #start}. */
    void close() {
        closed = true;
        synchronized (lock) {
            unsubscribe();
        }
    }

    /**
     * Brings the LuckPerms subscription in line with the settings: on while something is watched and
     * LuckPerms takes a listener, off otherwise.
     */
    void refresh() {
        if (closed) {
            return;
        }
        final Set<String> watched = watched();
        synchronized (lock) {
            if (closed) {
                return;
            }
            if (watched.isEmpty()) {
                unsubscribe();
                return;
            }
            LuckPermsBridge bridge = bridge();
            if (subscription != null) {
                if (bridge == null || (bridge == subscribedBridge
                        && bridge.instanceToken() == subscribedInstance)) {
                    return;
                }
                // LuckPerms was reloaded (or the bridge replaced): the old event bus is dead and
                // this subscription will never fire again. Records and armed reports stay; only
                // the listener moves to the live instance.
                logger.debug("LuckPerms was reloaded; subscribing to the new instance");
                Registration stale = subscription;
                subscription = null;
                stale.close();
            }
            if (bridge == null) {
                return;
            }
            Object instance = bridge.instanceToken();
            Registration made;
            try {
                made = bridge.onGroupsChanged(new GroupsChangedListener() {
                    @Override
                    public void onGroupsChanged(UUID uuid, List<String> currentGroups) {
                        changed(uuid, null, currentGroups,
                                GroupsChangedListener.Cause.RECALCULATION);
                    }

                    @Override
                    public void onGroupsChanged(UUID uuid, String username,
                            List<String> currentGroups, GroupsChangedListener.Cause cause) {
                        changed(uuid, username, currentGroups, cause);
                    }
                });
            } catch (RuntimeException | LinkageError broken) {
                // A LuckPerms whose API shape moved must not fail the module: the forward direction
                // does not depend on this. Retried on the next join or config change.
                logger.warn("could not listen for LuckPerms rank changes: " + broken);
                return;
            }
            if (made == null || made == Registration.NONE) {
                // Nothing is listening, so this must not look subscribed: storing NONE here would
                // stop every later refresh from trying again, for the life of the module.
                logger.debug("LuckPerms did not take a rank-change listener; will retry");
                return;
            }
            subscription = made;
            subscribedBridge = bridge;
            subscribedInstance = instance;
            logger.debug(() -> "reporting in-game rank changes for " + watched);
        }
    }

    /** Called with {@link #lock} held. Drops the subscription and every per-player record. */
    private void unsubscribe() {
        Registration live = subscription;
        subscription = null;
        if (live != null) {
            live.close();
        }
        for (Pending armed : pending.values()) {
            ScheduledFuture<?> timer = armed.timer;
            if (timer != null) {
                timer.cancel(false);
            }
        }
        pending.clear();
        clearRecords();
    }

    private void clearRecords() {
        synchronized (records) {
            generation++;
            reported.clear();
            parked.clear();
        }
    }

    private long generation() {
        synchronized (records) {
            return generation;
        }
    }

    /**
     * A join-time call from this server reached the bot carrying {@code groups}; see
     * {@link com.heimdall.core.roles.RoleSyncSink#groupsDelivered}. Recorded now if the player is
     * online, parked until their join if not, and dropped if a frame went out for the player after
     * the call was stamped.
     *
     * @param stamp the call's stamp, or {@link com.heimdall.core.roles.RoleSyncSink#UNSTAMPED}
     */
    void delivered(final UUID uuid, List<String> groups, long stamp) {
        if (closed || !isSubscribed()) {
            // Not subscribed means nothing is watched and nothing is reported, so there is
            // nothing a record could change.
            return;
        }
        Set<String> watched = intersect(groups, watched());
        synchronized (records) {
            if (olderThanLastFrame(uuid, stamp)) {
                logger.debug(() -> "a join call for " + uuid + " started before the last rank "
                        + "report was sent; keeping that report's record");
                return;
            }
            // Read under the lock: a join that adopts parked deliveries takes the same lock, so a
            // delivery cannot be parked just after the join looked and then wait out its TTL.
            if (isOnline(uuid)) {
                reported.put(uuid, watched);
                parked.remove(uuid);
                return;
            }
            long now = System.currentTimeMillis();
            pruneParked(now);
            parked.put(uuid, new Parked(watched, now, stamp));
        }
    }

    /** Called with {@link #records} held. */
    private void pruneParked(long nowMs) {
        Iterator<Parked> it = parked.values().iterator();
        while (it.hasNext()) {
            if (nowMs - it.next().atMs > PARKED_DELIVERY_TTL_MS) {
                it.remove();
            }
        }
    }

    private void adoptParked(UUID uuid) {
        synchronized (records) {
            Parked waiting = parked.remove(uuid);
            if (waiting != null
                    && System.currentTimeMillis() - waiting.atMs <= PARKED_DELIVERY_TTL_MS
                    && !olderThanLastFrame(uuid, waiting.stamp)) {
                reported.put(uuid, waiting.watched);
            }
        }
    }

    /** Called with {@link #records} held. Whether a frame went out for {@code uuid} after {@code stamp}. */
    private boolean olderThanLastFrame(UUID uuid, long stamp) {
        FrameMark mark = lastFrame.get(uuid);
        return mark != null && mark.seq > stamp;
    }

    /** Called just before a frame is sent: takes its place in the sequence, and prunes old marks. */
    private void markFrame(UUID uuid) {
        long now = System.currentTimeMillis();
        synchronized (records) {
            Iterator<FrameMark> it = lastFrame.values().iterator();
            while (it.hasNext()) {
                if (now - it.next().atMs > PARKED_DELIVERY_TTL_MS) {
                    it.remove();
                }
            }
            lastFrame.put(uuid, new FrameMark(sequence.incrementAndGet(), now));
        }
    }

    private void forget(UUID uuid) {
        synchronized (records) {
            reported.remove(uuid);
            parked.remove(uuid);
        }
        Pending armed = pending.remove(uuid);
        if (armed != null && armed.timer != null) {
            armed.timer.cancel(false);
        }
    }

    /**
     * The LuckPerms callback, on {@code heimdall-io}. Arms or feeds this player's debounce.
     *
     * <p>An online player's report is armed by any callback, as it always was. An offline player's
     * is armed only by a real rank edit ({@link GroupsChangedListener.Cause#INHERITANCE_NODE}); a
     * bare recalculation for them only refreshes a report that is already armed. See the class
     * javadoc for why a recalculation is not a change for somebody who is not here.
     *
     * @param storedName the name LuckPerms has stored for the user, or {@code null}
     */
    void changed(final UUID uuid, String storedName, final List<String> currentGroups,
            GroupsChangedListener.Cause cause) {
        if (closed || uuid == null || currentGroups == null) {
            return;
        }
        Optional<PlayerHandle> online = ctx.platform().players().byUuid(uuid);
        final boolean here = online.isPresent();
        final String name = here ? online.get().name() : storedName;
        // Not ROLE_SYNC_WRITE for somebody offline: that is the bot's own push, applied here.
        final boolean mayArm = here || cause == GroupsChangedListener.Cause.INHERITANCE_NODE;
        final long readAtMs = System.currentTimeMillis();
        pending.compute(uuid, (key, armed) -> {
            if (armed == null) {
                if (!mayArm) {
                    return null;
                }
                armed = new Pending();
                armed.timer = schedule(key);
                if (armed.timer == null) {
                    return null;
                }
            }
            armed.groups = currentGroups;
            // The bridge calls back straight after its read, so this is when the list was true.
            armed.readAtMs = readAtMs;
            // The online name wins over a stored one, which LuckPerms may have kept differently.
            if (!Strings.isBlank(name) && (here || Strings.isBlank(armed.username))) {
                armed.username = name;
            }
            return armed;
        });
    }

    private ScheduledFuture<?> schedule(final UUID uuid) {
        try {
            return ctx.executors().scheduler().schedule(new Runnable() {
                @Override
                public void run() {
                    try {
                        ctx.executors().io().execute(new Runnable() {
                            @Override
                            public void run() {
                                fire(uuid);
                            }
                        });
                    } catch (RejectedExecutionException shuttingDown) {
                        pending.remove(uuid);
                    }
                }
            }, debounceMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException shuttingDown) {
            return null;
        }
    }

    /** The debounce expiring, on {@code heimdall-io}: compare once, send at most one frame. */
    private void fire(UUID uuid) {
        Pending armed = pending.remove(uuid);
        if (armed == null) {
            return;
        }
        report(uuid, armed, null);
    }

    /**
     * A quit, on {@code heimdall-io}: sends this player's armed report now rather than dropping it.
     * The atomic {@code remove} means this and a timer firing at the same moment send it once.
     */
    private void flush(PlayerHandle player) {
        UUID uuid = player.uuid();
        if (uuid == null) {
            return;
        }
        Pending armed = pending.remove(uuid);
        if (armed == null) {
            return;
        }
        ScheduledFuture<?> timer = armed.timer;
        if (timer != null) {
            timer.cancel(false);
        }
        report(uuid, armed, player);
    }

    /**
     * Compares and sends one armed report.
     *
     * @param quitting the player whose quit is flushing this, or {@code null} when the timer fired;
     *     a quitting player is still treated as online here, under the name they quit with
     */
    private void report(final UUID uuid, Pending armed, PlayerHandle quitting) {
        if (closed) {
            return;
        }
        List<String> groups = armed.groups;
        Set<String> watched = watched();
        if (groups == null || watched.isEmpty()) {
            return;
        }
        // Captured before the comparison: a clear that lands between here and the write below means
        // the record this decision was based on is gone, and the write must not bring it back.
        long epoch = generation();
        final Set<String> now = intersect(groups, watched);
        PlayerHandle here = quitting;
        if (here == null) {
            Optional<PlayerHandle> online = ctx.platform().players().byUuid(uuid);
            here = online.isPresent() ? online.get() : null;
        }
        final String name;
        final Set<String> was;
        if (here != null) {
            name = Strings.isBlank(here.name()) ? armed.username : here.name();
            Set<String> before = reported.get(uuid);
            // No record is unknown, not empty: send without comparing. See the class javadoc.
            was = before == null ? null : intersect(before, watched);
            if (was != null && now.equals(was)) {
                return;
            }
        } else {
            // Offline: only a real rank edit, or a report armed while they were here, gets this far.
            name = armed.username;
            was = null;
        }
        if (Strings.isBlank(name)) {
            logger.debug(() -> "no name is known for " + uuid + "; not reporting their rank change");
            return;
        }
        if (!ctx.tunnel().isConnected()) {
            // The record is left alone, so the next change reports this difference as well.
            logger.debug(() -> "tunnel down; not reporting the rank change for " + uuid);
            return;
        }
        sendGroups(uuid, name, groups, armed.readAtMs);
        if (here != null && quitting == null) {
            synchronized (records) {
                if (generation == epoch && !closed) {
                    reported.put(uuid, now);
                }
            }
        }
        final String where = here == null ? " (offline)" : "";
        logger.debug(() -> "reported rank change for " + name + where + ": watched "
                + (was == null ? "unknown" : was.toString()) + " -> " + now);
    }

    /**
     * The bot's {@code rolesync.resync}, on {@code heimdall-io}: read the player's groups now and send
     * them without comparing. See the class javadoc.
     *
     * @param rawUuid the player, as the frame carried it
     * @param username used only when the uuid is missing or unparseable, against online players
     */
    void resync(String rawUuid, String username) {
        if (closed) {
            return;
        }
        UUID parsed = null;
        if (!Strings.isBlank(rawUuid)) {
            try {
                parsed = UUID.fromString(rawUuid.trim());
            } catch (IllegalArgumentException malformed) {
                parsed = null;
            }
        }
        Optional<PlayerHandle> online = parsed != null
                ? ctx.platform().players().byUuid(parsed)
                : Strings.isBlank(username)
                        ? Optional.<PlayerHandle>empty()
                        : ctx.platform().players().byName(username);
        if (!online.isPresent()) {
            // The common case on a network: the bot asks every capable server, and the player is
            // on one of them at most.
            logger.debug(() -> "rolesync.resync for " + rawUuid + " (" + username
                    + "): not online here; ignoring it");
            return;
        }
        final PlayerHandle player = online.get();
        final UUID uuid = player.uuid();
        LuckPermsBridge bridge = bridge();
        if (bridge == null || uuid == null) {
            logger.debug(() -> "rolesync.resync for " + player.name()
                    + ": LuckPerms is not available here; ignoring it");
            return;
        }
        // Captured before the read, like a report's: a clear while it is in flight wins.
        final long epoch = generation();
        CompletableFuture<List<String>> read;
        try {
            read = bridge.getPlayerGroups(uuid);
        } catch (RuntimeException refused) {
            logger.debug(() -> "rolesync.resync for " + player.name()
                    + ": the LuckPerms read was refused: " + refused);
            return;
        }
        if (read == null) {
            return;
        }
        read.whenComplete(new BiConsumer<List<String>, Throwable>() {
            @Override
            public void accept(List<String> groups, Throwable failure) {
                if (failure != null || groups == null) {
                    // Unknown is not empty: sending [] would read as every mapped rank gone.
                    logger.debug(() -> "rolesync.resync for " + player.name()
                            + ": could not read their LuckPerms groups; ignoring it: " + failure);
                    return;
                }
                answerResync(uuid, player.name(), groups, System.currentTimeMillis(), epoch);
            }
        });
    }

    private void answerResync(
            UUID uuid, String name, List<String> groups, long readAtMs, long epoch) {
        if (closed || !ctx.tunnel().isConnected() || !isOnline(uuid)) {
            return;
        }
        sendGroups(uuid, name, groups, readAtMs);
        if (isSubscribed()) {
            Set<String> now = intersect(groups, watched());
            synchronized (records) {
                if (generation == epoch && !closed && isOnline(uuid)) {
                    reported.put(uuid, now);
                }
            }
        }
        logger.debug(() -> "answered rolesync.resync for " + name + " with " + groups);
    }

    /**
     * The one place a {@link #FRAME_TYPE} frame is built: the full list, and {@code at}.
     *
     * @param readAtMs when {@code groups} was read from LuckPerms, which is what {@code at} carries
     */
    private void sendGroups(UUID uuid, String name, List<String> groups, long readAtMs) {
        // Marked before the send: a join call stamped after this point read its groups after this
        // frame's were read, so it is at least as new and is allowed to record.
        markFrame(uuid);
        ctx.tunnel().send(FRAME_TYPE, Payload.builder()
                .put("uuid", uuid.toString())
                .put("username", name)
                .putStrings("groups", groups)
                .put("at", readAtMs)
                .build());
    }

    private boolean isSubscribed() {
        synchronized (lock) {
            return subscription != null;
        }
    }

    private boolean isOnline(UUID uuid) {
        return ctx.platform().players().byUuid(uuid).isPresent();
    }

    /** The watched list, read now: see the class javadoc for why it is never cached. */
    private Set<String> watched() {
        List<String> names = ctx.settings().strings(WATCHED_GROUPS);
        if (names.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> out = new LinkedHashSet<String>();
        for (String name : names) {
            if (name != null && !name.trim().isEmpty()) {
                out.add(normalise(name));
            }
        }
        return out;
    }

    private static Set<String> intersect(Iterable<String> groups, Set<String> watched) {
        Set<String> out = new LinkedHashSet<String>();
        for (String group : groups) {
            if (group != null && watched.contains(normalise(group))) {
                out.add(normalise(group));
            }
        }
        return out;
    }

    /**
     * LuckPerms stores group names lower-cased, and the dashboard may not. Comparing both sides
     * lower-cased means a mapping typed as {@code VIP} still watches {@code vip}.
     */
    private static String normalise(String group) {
        return group.trim().toLowerCase(Locale.ROOT);
    }

    /** The bridge if LuckPerms is there right now, otherwise {@code null}. Quiet on purpose. */
    private LuckPermsBridge bridge() {
        Optional<LuckPermsBridge> found = ctx.platform().integrations().luckPerms();
        LuckPermsBridge bridge = found.isPresent() ? found.get() : null;
        return bridge != null && bridge.isAvailable() ? bridge : null;
    }
}
