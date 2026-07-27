package com.heimdall.module.rolesync;

import com.heimdall.core.http.model.RoleSyncDirective;
import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.module.ModuleContext;
import com.heimdall.core.platform.LuckPermsBridge;
import com.heimdall.core.util.Registration;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * The one place a group snapshot is turned into a LuckPerms write, whichever path asked for it.
 *
 * <h2>Why both paths land here</h2>
 *
 * <p>There are two triggers — a {@code role_sync} frame the bot broadcasts, and the {@code roleSync}
 * block that rides on a {@code connection-attempt} answer when somebody joins — and v2 implemented
 * them twice, in four files, with different guards. The join path checked the managed list and the
 * WebSocket path did not; the WebSocket path checked LuckPerms availability up front and the join
 * path checked it inside the deferred task. Nothing about "apply this snapshot" differs between the
 * two, so the guards live here once and the two callers differ only in how they got a UUID and
 * whether they wait first.
 *
 * <h2>The guards, and what each is protecting against</h2>
 *
 * <ul>
 *   <li><strong>An empty or missing managed list means change nothing</strong> — never "manage
 *       everything" (departure D46). {@link LuckPermsBridge} says the same thing, so this is belt
 *       and braces; it is here as well because the answer must not depend on which implementation of
 *       the bridge is underneath, and because skipping early keeps the "nothing happened" line
 *       attributable to the module rather than to the platform.
 *   <li><strong>Absent LuckPerms is a soft no-op, logged once.</strong> A server without LuckPerms
 *       is a supported configuration, not a fault, and role-sync frames are broadcast to every
 *       server in a guild — so a per-event warning would put one line in the log for every Discord
 *       role change on the network, forever. The flag resets when LuckPerms does turn up, so a
 *       server where it merely started second gets one line and then heals (issue #796 / MC-10).
 *   <li><strong>Nothing throws at the caller.</strong> One malformed snapshot must not take down the
 *       tunnel's dispatch loop or a login.
 * </ul>
 *
 * <h2>The two-second defer on join, and why it is the server's scheduler</h2>
 *
 * <p>v2 used {@code runTaskLater(plugin, task, 40L)} — forty ticks, two seconds — so the player was
 * fully connected before their groups were rewritten, and this reproduces it through
 * {@link com.heimdall.core.platform.SchedulerBridge#runLater}. That bridge's own javadoc says
 * anything measured in seconds belongs on {@code heimdall-sched}, and the exception is deliberate:
 * what is being waited for is the <em>server</em> finishing a join, so the delay wants to be
 * measured in the server's time. On a box lagging badly enough to stretch two seconds of ticks, the
 * join is taking longer to settle too, and a wall-clock timer would fire into the middle of it.
 *
 * <p>Every deferred task is held so {@link #shutdown()} can cancel it, and the task re-checks
 * {@link #active} on the way in. Cancellation and the flag are both needed: a task already handed to
 * the server's scheduler may be past the point where closing its handle does anything, and a module
 * that was switched off two seconds ago must not still be writing groups.
 *
 * <h2>Threading and ownership</h2>
 *
 * <p>Owned by {@link HeimdallRoleSyncModule}, one instance per enable, discarded on disable — so
 * nothing survives a toggle. Every method is safe from any thread and none of them blocks:
 * {@link #applyFromPush} is called on {@code heimdall-io} by the tunnel's dispatcher,
 * {@link #applyOnJoin} on whatever thread the whitelist module's login answer arrived on, and the
 * deferred body on the server's main thread. The blocking that LuckPerms needs happens inside the
 * bridge, on {@code heimdall-io}; this class only ever attaches a non-async completion to the future
 * it hands back, which therefore runs wherever the bridge completed it.
 */
final class RoleSyncApplier {

    /**
     * v2's forty ticks, and not configurable.
     *
     * <p>A knob was considered and left out. v2 had none, nothing in the dashboard sends one, and a
     * setting the bot never pushes is one an operator cannot actually change — so it would be a
     * field that reads its default forever while looking like a supported option. If a server ever
     * turns up where two seconds is not enough for a join to settle, the number becomes a setting
     * <em>and</em> a dashboard field in the same change, which is the only way it is worth anything.
     */
    private static final long JOIN_DELAY_MS = 2000L;

    private final ModuleContext context;
    private final HeimdallLogger logger;

    /** Whether the absence of LuckPerms has already been reported. Reset when it appears. */
    private final AtomicBoolean absenceLogged = new AtomicBoolean(false);

    /** Deferred join syncs that have not fired yet, so disabling the module can cancel them. */
    /**
     * The deferred join syncs that have not fired yet, at most one per player.
     *
     * <p>Keyed by UUID rather than held in a set, so a second join for the same player <em>replaces</em>
     * the first rather than queueing beside it. Two of these running two seconds apart both call
     * {@code setPlayerGroups}, which is a load-modify-save against LuckPerms storage — so they
     * interleave, and the later save can be computed from a user object read before the earlier one
     * wrote. The result is a sync that silently loses whichever change lost the race.
     *
     * <p>A reconnect inside the defer window is the ordinary way to produce that: leaving and
     * rejoining within two seconds is one click. Last-snapshot-wins is already the stated semantics
     * everywhere else in this module — the bot's most recent word is the truth — so replacing is
     * both safer and the behaviour that was already being described.
     */
    private final ConcurrentHashMap<UUID, PendingJoinSync> pending =
            new ConcurrentHashMap<UUID, PendingJoinSync>();

    /**
     * The last thing the bot said about whether role sync is on, or {@code null} if it never has.
     *
     * <p>R1: a {@code role_sync} FRAME carries no enabled flag — only a login response does, as the
     * tri-state {@code RoleSyncDirective} (departure D2). So a bot that has been switched to driving
     * LuckPerms over RCON and still broadcasts a stale frame would be obeyed by a plugin that only
     * checked the directive on the join path, and the two would fight over the same groups. That is
     * the exact scenario D2 exists to prevent, arriving on the other transport.
     *
     * <p>Remembering the last directive is the only signal available: the frame cannot say, and
     * asking the bot per frame would be an HTTP call inside a tunnel handler. {@code null} means
     * nothing has ever told us, and pushes are honoured in that state — refusing them would break
     * every server that has had a push before its first login, which is most of them after a
     * restart.
     */
    private volatile Boolean lastKnownEnabled;

    private volatile boolean active = true;

    RoleSyncApplier(ModuleContext context) {
        this.context = context;
        this.logger = context.logger();
    }

    /**
     * Applies the {@code roleSync} block from a login answer, after the join settles.
     *
     * <p>The tri-state is read in full and the three answers are kept apart, because two of them
     * mean "change nothing" for entirely different reasons and collapsing them is how a plugin ends
     * up fighting the bot for a group (departure D2). Absent is "there is no snapshot for this
     * player yet"; disabled is "the bot is driving LuckPerms over RCON, keep out". Both are logged
     * distinctly at debug so an operator asking why nothing happened gets the actual reason.
     *
     * @param uuid the joining player; {@code null} is a no-op with a warning
     * @param username used only for log lines
     * @param directive the block the bot sent, or {@link RoleSyncDirective#absent()}
     */
    void applyOnJoin(final UUID uuid, String username, RoleSyncDirective directive) {
        if (!active) {
            return;
        }
        final String label = label(username, uuid);
        if (uuid == null) {
            logger.warn("role sync on join for " + label + " has no UUID; ignoring");
            return;
        }
        if (directive == null || !directive.isPresent()) {
            logger.debug(() -> "no role-sync snapshot for " + label + " yet; leaving groups alone");
            return;
        }
        // Recorded before the early return, so a disabled directive is remembered rather than
        // merely obeyed once. See lastKnownEnabled.
        lastKnownEnabled = Boolean.valueOf(directive.isEnabled());
        if (!directive.isEnabled()) {
            logger.debug(() -> "role sync is disabled for " + label
                    + "; the bot owns LuckPerms for this guild, leaving groups alone");
            return;
        }
        final List<String> target = directive.targetGroups();
        final List<String> managed = directive.managedGroups();
        if (managed == null || managed.isEmpty()) {
            logger.debug(() -> "role sync for " + label
                    + " named no managed groups; changing nothing (an empty managed list is not"
                    + " 'manage everything')");
            return;
        }

        logger.debug(() -> "scheduling role sync for " + label + " in " + JOIN_DELAY_MS
                + "ms — target=" + target + " managed=" + managed);

        PendingJoinSync task = new PendingJoinSync(uuid, label, target, managed);
        PendingJoinSync superseded = pending.put(uuid, task);
        if (superseded != null) {
            // A second join inside the two-second window — one reconnect. Cancelled rather than
            // left to run, so two load-modify-save cycles cannot interleave against LuckPerms
            // storage and lose whichever change finished second.
            logger.debug(() -> "replacing an outstanding deferred role sync for " + label);
            superseded.cancel();
        }
        Registration handle = context.platform().scheduler().runLater(task, JOIN_DELAY_MS);
        // Attached after the call, because an inline scheduler has already run the task by the time
        // this returns. `attach` closes the handle itself if that happened, and closing twice is a
        // no-op, so the ordering is safe either way.
        task.attach(handle);
        if (!active) {
            // Lost the race with disable(): the task was scheduled after shutdown() drained the set.
            task.cancel();
        }
    }

    /**
     * Applies a snapshot the bot pushed over the tunnel, immediately.
     *
     * <p>No defer here, and that is v2's behaviour rather than an omission: a push arrives for a
     * player who is already on the server (or not on it at all), so there is no join to wait for.
     *
     * @param uuid the player, already resolved by {@link RoleSyncPushHandler}
     * @param label what to call them in the log
     */
    void applyFromPush(UUID uuid, String label, List<String> targetGroups, List<String> managedGroups) {
        if (!active) {
            return;
        }
        if (uuid == null) {
            return;
        }
        if (Boolean.FALSE.equals(lastKnownEnabled)) {
            // R1. The last directive said the bot drives LuckPerms itself over RCON, and a pushed
            // frame cannot say otherwise because the wire shape has no flag for it. Obeying it would
            // mean the plugin and the bot both writing the same groups — which is what departure D2
            // is about, and the reason the directive is a tri-state rather than a boolean.
            logger.debug(() -> "ignoring a pushed role sync for " + label
                    + ": the last directive said the bot owns LuckPerms for this guild");
            return;
        }
        if (managedGroups == null || managedGroups.isEmpty()) {
            logger.debug(() -> "pushed role sync for " + label
                    + " named no managed groups; changing nothing");
            return;
        }
        apply(uuid, label, targetGroups, managedGroups, "push");
    }

    /**
     * Stops everything this applier still has outstanding.
     *
     * <p>Called from {@link HeimdallRoleSyncModule#disable()}. The module's tracked registrations
     * are unwound by {@code ModuleManager} either way, but a task sitting in the <em>server's</em>
     * scheduler is not one of those — nothing in {@link ModuleContext} tracks it — so it is
     * cancelled here or it fires into a module that is off.
     */
    void shutdown() {
        active = false;
        for (PendingJoinSync task : pending.values()) {
            task.cancel();
        }
        pending.clear();
    }

    /** How many deferred join syncs are waiting. For tests. */
    int pendingCount() {
        return pending.size();
    }

    // ── Internals ────────────────────────────────────────────────────────────

    /**
     * Hands the snapshot to LuckPerms and reports what happened.
     *
     * <p>The bot's own {@code groupsAdded} / {@code groupsRemoved} lists are deliberately not used
     * to decide what to write. The diff is recomputed by the bridge against what LuckPerms actually
     * reports, because the bot's view is of the <em>Discord</em> side and can disagree with a server
     * whose groups were edited by hand since the last sync. Applying the bot's deltas would then
     * write a change the server did not need and skip one it did.
     */
    private void apply(
            UUID uuid,
            final String label,
            List<String> targetGroups,
            List<String> managedGroups,
            final String source) {
        LuckPermsBridge bridge = luckPerms();
        if (bridge == null) {
            return;
        }
        try {
            CompletableFuture<Boolean> applied =
                    bridge.setPlayerGroups(uuid, targetGroups, managedGroups);
            if (applied == null) {
                logger.warn("role sync (" + source + ") for " + label
                        + ": the LuckPerms bridge returned nothing; treating it as skipped");
                return;
            }
            applied.whenComplete(new BiConsumer<Boolean, Throwable>() {
                @Override
                public void accept(Boolean ok, Throwable failure) {
                    if (failure != null) {
                        logger.error("role sync (" + source + ") for " + label
                                + " failed inside LuckPerms; the player keeps the groups they had",
                                failure);
                        return;
                    }
                    if (Boolean.TRUE.equals(ok)) {
                        logger.info("role sync (" + source + ") applied for " + label);
                    } else {
                        logger.warn("role sync (" + source + ") for " + label
                                + " was skipped by LuckPerms; groups are unchanged");
                    }
                }
            });
        } catch (RuntimeException e) {
            // A bridge that throws rather than failing its future. Contained for the same reason
            // the future's failure is: this runs on heimdall-io under the tunnel's dispatcher, and
            // one bad snapshot must not stop the next frame being handled.
            logger.error("role sync (" + source + ") for " + label
                    + " failed before it reached LuckPerms", e);
        }
    }

    /**
     * The bridge, or {@code null} with the absence reported at most once.
     *
     * <p>Re-resolved on every call rather than cached, which is the whole reason
     * {@link com.heimdall.core.platform.Integrations#luckPerms()} is an {@code Optional} returned
     * from a method instead of a field: a server where LuckPerms started after Heimdall had role
     * sync dead for the entire process in v2 (issue #796 / MC-10).
     */
    private LuckPermsBridge luckPerms() {
        Optional<LuckPermsBridge> found = context.platform().integrations().luckPerms();
        LuckPermsBridge bridge = found.isPresent() ? found.get() : null;
        if (bridge == null || !bridge.isAvailable()) {
            if (absenceLogged.compareAndSet(false, true)) {
                logger.warn("LuckPerms is not available, so role sync will do nothing on this"
                        + " server. This is logged once; it is not repeated per player.");
            }
            return null;
        }
        // Reset so a genuine outage that later heals is reported once per outage rather than once
        // per process. The realistic case is load order, which resolves on the first sync after
        // LuckPerms finishes starting.
        absenceLogged.set(false);
        return bridge;
    }

    /** What to call a player in a log line: their name if we have one, otherwise their UUID. */
    static String label(String username, UUID uuid) {
        if (username != null && !username.isEmpty()) {
            return username;
        }
        return uuid == null ? "an unidentified player" : uuid.toString();
    }

    /**
     * One deferred join sync, holding enough to cancel itself.
     *
     * <p>A class rather than a captured lambda because the handle that cancels it does not exist
     * until after it has been scheduled — and, with an inline scheduler, not until after it has
     * already run.
     */
    private final class PendingJoinSync implements Runnable {

        private final UUID uuid;
        private final String label;
        private final List<String> target;
        private final List<String> managed;
        private final AtomicBoolean done = new AtomicBoolean(false);

        private volatile Registration handle;

        PendingJoinSync(UUID uuid, String label, List<String> target, List<String> managed) {
            this.uuid = uuid;
            this.label = label;
            this.target = target;
            this.managed = managed;
        }

        void attach(Registration registration) {
            this.handle = registration;
            if (done.get() && registration != null) {
                registration.close();
            }
        }

        void cancel() {
            if (!done.compareAndSet(false, true)) {
                return;
            }
            // Two-argument remove: a task that has already been superseded must not evict the
            // replacement that took its place in the map.
            pending.remove(uuid, this);
            Registration registration = handle;
            if (registration != null) {
                registration.close();
            }
        }

        @Override
        public void run() {
            if (!done.compareAndSet(false, true)) {
                return;
            }
            pending.remove(uuid, this);
            if (!active) {
                logger.debug(() -> "dropping the deferred role sync for " + label
                        + "; the module was disabled while it was waiting");
                return;
            }
            apply(uuid, label, target, managed, "join");
        }
    }
}
