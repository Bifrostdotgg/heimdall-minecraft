package com.heimdall.core.session;

import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.platform.PlayerHandle;
import com.heimdall.core.util.Registration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Where a platform adapter reports that a player joined or left, and where modules hear about it.
 *
 * <h2>Why this is not a pipeline and not a facade method</h2>
 *
 * <p>Both alternatives were considered in phase 1c and written down as departure S1. A
 * {@code Pipeline} exists to arbitrate a decision, and there is none left to arbitrate once somebody
 * is already on the server. A {@code PlatformFacade} method would have the arrow pointing the wrong
 * way — the facade is core asking the platform questions, this is the platform telling core
 * something — and every platform would then have to implement a listener registry as well as a set
 * of accessors.
 *
 * <h2>Handlers never run on the event thread</h2>
 *
 * <p>{@link #join} and {@link #quit} hand off to {@code heimdall-io} and return immediately. On
 * Bukkit, {@code PlayerJoinEvent} is on the main server thread: a listener that wrote a mirror
 * entry, or worse made a network call, would put that on the tick loop for every join. On Velocity
 * the event thread is one of the proxy's own, with the same argument one step removed.
 *
 * <p>The consequence to know when writing a listener: <strong>join and quit are not ordered
 * relative to each other</strong> once they are off the event thread, and a quit for a player who
 * reconnected immediately can in principle land after the second join. That is why the timestamp is
 * carried on the event rather than read by the listener, and why the mirror's window rule
 * ({@code extendOnEvent}) is idempotent in the value it writes.
 *
 * <p>If the executor refuses the task — the pools are shutting down — the event is dropped with a
 * debug line. Shutdown is the one time a missed cache extension costs nothing.
 *
 * <h2>Ownership</h2>
 *
 * <p>The executor is borrowed and never shut down here. Registrations are handed out as
 * {@link Registration} handles, and a module's are tracked by its {@code ModuleContext} so they are
 * unwound when it is disabled — which is the whole reason 1c shipped no join listeners at all
 * rather than dead ones.
 *
 * <p>Thread-safe.
 */
public final class PlayerSessionEvents {

    private final HeimdallLogger logger;
    private final Executor executor;

    private final CopyOnWriteArrayList<PlayerSessionListener> joinListeners =
            new CopyOnWriteArrayList<PlayerSessionListener>();
    private final CopyOnWriteArrayList<PlayerSessionListener> quitListeners =
            new CopyOnWriteArrayList<PlayerSessionListener>();

    /**
     * @param executor where listeners run; {@code heimdall-io} in production. A same-thread executor
     *     is a legitimate choice in a test and is what makes the dispatch assertable without a
     *     latch.
     */
    public PlayerSessionEvents(HeimdallLogger logger, Executor executor) {
        if (logger == null || executor == null) {
            throw new IllegalArgumentException("logger and executor are required");
        }
        this.logger = logger;
        this.executor = executor;
    }

    /** Subscribes to joins. */
    public Registration onJoin(PlayerSessionListener listener) {
        return register(joinListeners, listener);
    }

    /** Subscribes to quits. */
    public Registration onQuit(PlayerSessionListener listener) {
        return register(quitListeners, listener);
    }

    /**
     * Reports a join. Called by the platform adapter, on the platform's event thread.
     *
     * <p>Returns as soon as the hand-off is queued. Never throws.
     */
    public void join(PlayerHandle player, long timestampMs) {
        dispatch("join", joinListeners, player, timestampMs);
    }

    /**
     * Reports a quit. Called by the platform adapter, on the platform's event thread.
     *
     * <p>The handle names a player who has already gone; every {@link PlayerHandle} method tolerates
     * that by doing nothing, so a listener does not have to check.
     */
    public void quit(PlayerHandle player, long timestampMs) {
        dispatch("quit", quitListeners, player, timestampMs);
    }

    /** How many join listeners are registered. For tests and diagnostics. */
    public int joinListenerCount() {
        return joinListeners.size();
    }

    /** How many quit listeners are registered. For tests and diagnostics. */
    public int quitListenerCount() {
        return quitListeners.size();
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private Registration register(
            final CopyOnWriteArrayList<PlayerSessionListener> into,
            final PlayerSessionListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener is required");
        }
        into.add(listener);
        return Registration.once(new Runnable() {
            @Override
            public void run() {
                into.remove(listener);
            }
        });
    }

    private void dispatch(
            final String what,
            final List<PlayerSessionListener> listeners,
            final PlayerHandle player,
            final long timestampMs) {
        if (player == null || listeners.isEmpty()) {
            return;
        }
        // Snapshotted here rather than inside the task: the list is copy-on-write, so iterating it
        // later would be safe either way, but taking the view on the event thread means a listener
        // registered between the event and the hand-off does not see an event from before it
        // existed.
        final List<PlayerSessionListener> snapshot =
                new java.util.ArrayList<PlayerSessionListener>(listeners);
        try {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    deliver(what, snapshot, player, timestampMs);
                }
            });
        } catch (RejectedExecutionException shuttingDown) {
            logger.debug(() -> "dropping a player " + what + " notification: the pools are "
                    + "shutting down");
        } catch (RuntimeException broken) {
            // An executor that throws anything else is a bug in the pools, not in the platform
            // adapter that called this — and a join event must never become a server-side error.
            logger.error("could not hand off a player " + what + " notification", broken);
        }
    }

    private void deliver(
            String what, List<PlayerSessionListener> snapshot, PlayerHandle player, long timestampMs) {
        // Guarded per listener: one module's broken handler must not stop the mirror of another
        // from being extended, and there is nothing the caller could do about it anyway — the
        // player has already joined.
        AtomicBoolean reported = new AtomicBoolean();
        for (PlayerSessionListener listener : snapshot) {
            try {
                listener.onPlayerSession(player, timestampMs);
            } catch (RuntimeException broken) {
                if (reported.compareAndSet(false, true)) {
                    logger.error("a player " + what + " listener failed for " + player.name(), broken);
                } else {
                    logger.debug(() -> "another player " + what + " listener failed for "
                            + player.name() + ": " + broken);
                }
            }
        }
    }
}
