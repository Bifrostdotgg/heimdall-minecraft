package com.heimdall.module.rolesync;

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
import com.heimdall.core.util.Registration;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

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
 * differ from that record intersected with the watched list. A player with no record is compared
 * against the empty set, so any watched group they hold is reported. A watched group being
 * <strong>removed</strong> counts as a difference exactly like one being added; that is how a refund
 * reaches Discord. (An expiry reaches it too if LuckPerms signals one while the player is online;
 * the API does not promise that it does, and the join-time reconcile is what covers it if not.)
 *
 * <p><strong>A record exists only for what the bot was verifiably told.</strong> It is written when a
 * frame is sent, and when a join-time call from <em>this</em> server succeeded carrying
 * {@code currentGroups} from this server (the whitelist's {@code connection-attempt}, or this module's
 * {@code role-sync/snapshot}), reported through {@link #delivered}. It is never taken from reading
 * LuckPerms locally at join: when this server made no call (a backend that defers to its gatekeeper,
 * whose LuckPerms may be elsewhere), when the call failed, or when the bot was unusable, a local read
 * would describe what the bot has <em>not</em> seen and suppress the one report that would have told
 * it. Leaving the player unrecorded costs at worst one redundant frame, and the bot compares sets, so
 * that frame is a no-op on its side.
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
 * <h2>Offline players are not reported</h2>
 *
 * <p>A change to a player who is not online on this server (a console edit, a store command run for
 * an offline buyer) is ignored. The join-time call carries {@code currentGroups} and the bot reconciles
 * from it, so the change reaches Discord at their next login. Reporting offline changes would need a
 * record per offline player to compare against, a cache with no bound and no owner.
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
 * <p>The timer lives on {@code heimdall-sched}, and the comparison and send are handed straight to
 * {@code heimdall-io}: the scheduler is single-threaded and shared with every poll the plugin runs.
 * The handles are owned here rather than registered through the context, for the reason
 * {@code WhitelistChangeNudge} records: the context's tracking bag is unbounded and only emptied on
 * disable, and this would add an entry per rank change for the life of the module.
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
 * LuckPerms that started after this module is picked up. While subscribed, a retry costs a field read.
 *
 * <h2>Threading</h2>
 *
 * <p>{@link #start} and {@link #close} run on the module manager's thread. The LuckPerms callback
 * arrives on {@code heimdall-io} (the bridge hops there), config and mode changes on the socket's
 * reading thread, joins and quits on {@code heimdall-io}, deliveries on whichever thread completed the
 * join call. Subscribing and unsubscribing are serialised on {@link #lock}; record writes and clears
 * on {@link #records}; the debounce's hand-off between a callback and the firing timer is one atomic
 * {@code compute}/{@code remove} per player.
 */
final class GroupChangeReporter {

    /** The frame type the bot handles. Plugin to bot, a notification: nothing comes back. */
    static final String FRAME_TYPE = "rolesync.groups";

    /** The setting the bot fills from the dashboard's Minecraft-to-Discord mappings. */
    static final String WATCHED_GROUPS = "watchedGroups";

    /** How long one burst of LuckPerms events is collapsed over. */
    static final long DEBOUNCE_MS = 500L;

    /** How long a delivery waits for its player to join before it is dropped. */
    static final long PARKED_DELIVERY_TTL_MS = 120_000L;

    /** One player's armed report: the newest groups seen, and the timer that will read them. */
    private static final class Pending {
        volatile List<String> groups;
        volatile ScheduledFuture<?> timer;
    }

    /** A delivery for a player who has not joined yet. */
    private static final class Parked {
        final Set<String> watched;
        final long atMs;

        Parked(Set<String> watched, long atMs) {
            this.watched = watched;
            this.atMs = atMs;
        }
    }

    private final ModuleContext ctx;
    private final HeimdallLogger logger;
    private final long debounceMs;

    /** Guards {@link #subscription}: subscribe and unsubscribe must not interleave. */
    private final Object lock = new Object();

    /** The live LuckPerms subscription, or {@code null} while nothing is watched or it failed. */
    private Registration subscription;

    /**
     * Guards {@link #reported}, {@link #parked} and {@link #generation} together, so a write that
     * checks the generation cannot land after a clear it did not see.
     */
    private final Object records = new Object();

    /** Per online player: the watched groups the bot is known to have been told about. */
    private final Map<UUID, Set<String>> reported = new ConcurrentHashMap<UUID, Set<String>>();

    /** Deliveries waiting for their player's join. */
    private final Map<UUID, Parked> parked = new ConcurrentHashMap<UUID, Parked>();

    /** Bumped on every clear. A write carrying an older value is stale and discarded. */
    private long generation;

    private final ConcurrentHashMap<UUID, Pending> pending = new ConcurrentHashMap<UUID, Pending>();

    private volatile boolean closed;

    GroupChangeReporter(ModuleContext ctx) {
        this(ctx, DEBOUNCE_MS);
    }

    /** The window is injectable so a test can say "inside the window" without racing a clock. */
    GroupChangeReporter(ModuleContext ctx, long debounceMs) {
        this.ctx = ctx;
        this.logger = ctx.logger();
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
        ctx.onPlayerQuit(new PlayerSessionListener() {
            @Override
            public void onPlayerSession(PlayerHandle player, long timestampMs) {
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
            if (subscription != null) {
                return;
            }
            LuckPermsBridge bridge = bridge();
            if (bridge == null) {
                return;
            }
            Registration made;
            try {
                made = bridge.onGroupsChanged(new GroupsChangedListener() {
                    @Override
                    public void onGroupsChanged(UUID uuid, List<String> currentGroups) {
                        changed(uuid, currentGroups);
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
     * online, parked until their join if not.
     */
    void delivered(UUID uuid, List<String> groups) {
        if (closed || !isSubscribed()) {
            // Not subscribed means nothing is watched, and an absent record already compares as
            // empty, so there is nothing a record could change.
            return;
        }
        Set<String> watched = intersect(groups, watched());
        synchronized (records) {
            // Read under the lock: a join that adopts parked deliveries takes the same lock, so a
            // delivery cannot be parked just after the join looked and then wait out its TTL.
            if (isOnline(uuid)) {
                reported.put(uuid, watched);
                parked.remove(uuid);
                return;
            }
            long now = System.currentTimeMillis();
            pruneParked(now);
            parked.put(uuid, new Parked(watched, now));
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
                    && System.currentTimeMillis() - waiting.atMs <= PARKED_DELIVERY_TTL_MS) {
                reported.put(uuid, waiting.watched);
            }
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

    /** The LuckPerms callback, on {@code heimdall-io}. Arms or feeds this player's debounce. */
    void changed(final UUID uuid, final List<String> currentGroups) {
        if (closed || uuid == null || currentGroups == null || !isOnline(uuid)) {
            return;
        }
        pending.compute(uuid, (key, armed) -> {
            if (armed == null) {
                armed = new Pending();
                armed.timer = schedule(key);
                if (armed.timer == null) {
                    return null;
                }
            }
            armed.groups = currentGroups;
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
        if (armed == null || closed) {
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
        Set<String> before = reported.get(uuid);
        final Set<String> was =
                before == null ? Collections.<String>emptySet() : intersect(before, watched);
        if (now.equals(was)) {
            return;
        }
        if (!ctx.tunnel().isConnected()) {
            // The record is left alone, so the next change reports this difference as well.
            logger.debug(() -> "tunnel down; not reporting the rank change for " + uuid);
            return;
        }
        final Optional<PlayerHandle> online = ctx.platform().players().byUuid(uuid);
        if (!online.isPresent()) {
            return;
        }
        ctx.tunnel().send(FRAME_TYPE, Payload.builder()
                .put("uuid", uuid.toString())
                .put("username", online.get().name())
                .putStrings("groups", groups)
                .put("at", System.currentTimeMillis())
                .build());
        synchronized (records) {
            if (generation == epoch && !closed) {
                reported.put(uuid, now);
            }
        }
        logger.debug(() -> "reported rank change for " + online.get().name() + ": watched " + was
                + " -> " + now);
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
