package com.heimdall.module.whitelist;

import com.heimdall.core.log.HeimdallLogger;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Turns the bot's {@code whitelist_changed} notification into at most one mirror sync every couple
 * of seconds.
 *
 * <p>Departure D73.
 *
 * <h2>The window this closes</h2>
 *
 * <p>The mirror is refreshed by a pre-warm poll on an interval — five minutes by default, and v2's
 * interval before that. So a player moved back to <em>pending</em> on the dashboard stayed admitted
 * for up to a full poll period, and the person who just revoked them watched them keep playing. The
 * bot now pushes a notification the moment the whitelist changes, and this class is what turns that
 * into a pull: the plugin still asks the bot for the list, because the bot is the source of truth
 * and a notification carrying a diff would be a second, weaker copy of the sync endpoint.
 *
 * <h2>Why it is debounced</h2>
 *
 * <p>A bulk import fires one notification per row. Without a debounce a fifty-player import would be
 * fifty full whitelist syncs, back to back, on the single {@code heimdall-sched} thread — which is
 * also where the pre-warm poll and the expiry sweep run. So the first nudge in a burst arms a
 * one-shot {@value #DEBOUNCE_MS}ms out and every nudge behind it lands on the armed one and does
 * nothing.
 *
 * <p>Over-nudging is cheap rather than free: the sync sends the mirror's ETag, so a change that turns
 * out not to affect this server is a 304 and no reconcile at all. Cheap is not a reason to skip the
 * bound — 304s are still round trips, and fifty of them in a second is fifty of them in a second.
 *
 * <h2>Disarm before syncing, not after</h2>
 *
 * <p>{@link #fire} clears the armed flag <em>before</em> calling the sync, so a change that lands
 * while a sync is already in flight arms a fresh one rather than being swallowed by the run that
 * started before it happened. The alternative loses exactly the revocation this whole mechanism
 * exists to deliver promptly. The cost is a ceiling of one sync per debounce window under continuous
 * change, which is the bound that was wanted anyway.
 *
 * <h2>Threading</h2>
 *
 * <p>{@link #nudge} is called from the tunnel subscription on {@code heimdall-io} and does nothing
 * but a compare-and-set and a {@code schedule}. The sync itself runs on {@code heimdall-sched},
 * which is where {@code WhitelistMirrorService.syncNow()} is documented to run and is the only place
 * it may: it blocks on an HTTP call, and running it on the socket's reading thread — or on the IO
 * pool the handler arrived on — is how a healthy tunnel gets reaped for not reading.
 *
 * <p>This class owns its scheduled handle rather than registering it through {@code ModuleContext},
 * for the same reason {@code HeimdallConsoleModule} owns its log tap: the context's tracking bag is
 * unbounded and only emptied on disable, so a handle per nudge would accumulate one entry every two
 * seconds for as long as the whitelist keeps changing. {@link #close()} is therefore load-bearing and
 * is called from the module's {@code disable()}.
 */
final class WhitelistChangeNudge {

    /** The wire type the bot pushes. A notification: no reply is expected and none is sent. */
    static final String FRAME_TYPE = "whitelist_changed";

    /** How long a burst is allowed to collapse over. */
    static final long DEBOUNCE_MS = 2000L;

    private final HeimdallLogger logger;
    private final ScheduledExecutorService scheduler;
    private final Runnable sync;
    private final long debounceMs;

    /** Whether a sync is already scheduled. The whole of the debounce. */
    private final AtomicBoolean armed = new AtomicBoolean();

    private final AtomicReference<ScheduledFuture<?>> pending =
            new AtomicReference<ScheduledFuture<?>>();

    private volatile boolean closed;

    WhitelistChangeNudge(HeimdallLogger logger, ScheduledExecutorService scheduler, Runnable sync) {
        this(logger, scheduler, sync, DEBOUNCE_MS);
    }

    /** The delay is injectable so a test can assert the collapsing without waiting two seconds. */
    WhitelistChangeNudge(
            HeimdallLogger logger,
            ScheduledExecutorService scheduler,
            Runnable sync,
            long debounceMs) {
        this.logger = logger;
        this.scheduler = scheduler;
        this.sync = sync;
        this.debounceMs = Math.max(0L, debounceMs);
    }

    /**
     * Records that the whitelist changed, scheduling a sync unless one is already pending.
     *
     * <p>Cheap and non-blocking by contract: it runs on the executor the tunnel subscription named,
     * and everything expensive is what it schedules rather than what it does.
     */
    void nudge() {
        if (closed) {
            return;
        }
        if (!armed.compareAndSet(false, true)) {
            // Already scheduled. This is the debounce, and it is the common case during an import.
            return;
        }
        try {
            // The CAS above and this store are two steps, not one, so a close() landing between them
            // can publish a handle it has already tried to cancel. That is tolerated rather than
            // fixed, and the reasons are worth stating because "make it atomic" looks free:
            //
            //   * the window is the few microseconds of a schedule() call, against a debounce
            //     measured in seconds;
            //   * fire() re-reads the volatile `closed` flag before touching the sync, so the worst
            //     outcome is a timer that wakes up and does nothing; and
            //   * closing it properly would mean a lock around schedule(), on the path a tunnel
            //     handler runs — the one place this class promises not to block.
            //
            // The stale handle is dropped on the next close(), which getAndSet(null)s it regardless.
            pending.set(scheduler.schedule(new Runnable() {
                @Override
                public void run() {
                    fire();
                }
            }, debounceMs, TimeUnit.MILLISECONDS));
        } catch (RejectedExecutionException shuttingDown) {
            // The pools are going down with the plugin. Disarm so this is not left permanently
            // armed against a scheduler that will never run it — which would silently swallow every
            // later nudge if the module were somehow re-enabled against the same pool.
            armed.set(false);
            logger.debug("not scheduling a whitelist sync: the scheduler is shutting down");
        }
    }

    /** Cancels anything pending. Idempotent, and safe to call from a module's {@code disable()}. */
    void close() {
        closed = true;
        ScheduledFuture<?> scheduled = pending.getAndSet(null);
        if (scheduled != null) {
            scheduled.cancel(false);
        }
        armed.set(false);
    }

    /** Whether a sync is currently scheduled. Visible for testing only. */
    boolean isArmed() {
        return armed.get();
    }

    private void fire() {
        // Disarmed first: see the class javadoc. A change arriving during the sync below must arm a
        // fresh one rather than be absorbed by a run that started before it happened.
        armed.set(false);
        if (closed) {
            return;
        }
        try {
            sync.run();
        } catch (RuntimeException failed) {
            // syncNow is documented never to throw, so this is a bug rather than a bot outage — but
            // an exception escaping here would cancel nothing and be reported nowhere, since this
            // runs as a one-shot rather than through the context's guarded scheduler.
            logger.warn("a whitelist-change sync failed: " + failed);
        }
    }
}
