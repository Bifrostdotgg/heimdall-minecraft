package com.heimdall.core.session;

import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.platform.PlayerHandle;
import com.heimdall.core.util.Registration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Where a platform adapter reports that a player joined, left or died, and where modules hear about
 * it.
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
 * <h2>Three things, two interfaces</h2>
 *
 * <p>Join and quit share {@link PlayerSessionListener}; death has {@link PlayerDeathListener} of its
 * own, because it carries the server's death message and the other two carry nothing. The
 * <em>machinery</em> is shared regardless — one {@code Subscription} type, one deactivate-then-unlist
 * rule, one containment policy — so a fourth notification is a list and a dispatch call rather than
 * a second copy of the hard part (departure D80).
 *
 * <h2>Handlers never run on the event thread</h2>
 *
 * <p>{@link #join}, {@link #quit} and {@link #death} hand off to {@code heimdall-io} and return
 * immediately. On Bukkit, {@code PlayerJoinEvent} and {@code PlayerDeathEvent} are on the main
 * server thread: a listener that wrote a mirror entry, or worse made a network call, would put that
 * on the tick loop for every join and every death. On Velocity the event thread is one of the
 * proxy's own, with the same argument one step removed.
 *
 * <p>The consequence to know when writing a listener: <strong>the notifications are not ordered
 * relative to each other</strong> once they are off the event thread, and a quit for a player who
 * reconnected immediately can in principle land after the second join. That is why the timestamp is
 * carried on the event rather than read by the listener.
 *
 * <h2>A closed registration cannot fire, even from a task already queued</h2>
 *
 * <p>The same problem {@code SubscriptionRegistry} solves for tunnel frames, solved the same way —
 * deliberately, so there is one pattern here to understand rather than two. Removing a listener from
 * a list only affects <em>future</em> dispatches; a task handed to the executor a microsecond
 * earlier is already beyond the list's reach. So each registration carries an {@link AtomicBoolean},
 * closing it deactivates <em>before</em> it unlists, and the task re-checks that flag
 * <strong>inside the executor</strong> immediately before invoking the listener.
 *
 * <p>Without that re-check the failure is neither theoretical nor small: the whitelist module's
 * window listener would run against a {@code MirrorStore} its own module had already closed, and
 * closing a store flushes it synchronously — so a config flap could leave two stores over one file
 * with a write still pending, which is exactly the hazard departure D57 refuses to reopen. A
 * disabled module still sliding cache expiries is the same class of bug as one still gating logins.
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

    private final CopyOnWriteArrayList<Subscription<PlayerSessionListener>> joinListeners =
            new CopyOnWriteArrayList<Subscription<PlayerSessionListener>>();
    private final CopyOnWriteArrayList<Subscription<PlayerSessionListener>> quitListeners =
            new CopyOnWriteArrayList<Subscription<PlayerSessionListener>>();
    private final CopyOnWriteArrayList<Subscription<PlayerDeathListener>> deathListeners =
            new CopyOnWriteArrayList<Subscription<PlayerDeathListener>>();

    /**
     * @param executor where listeners run; {@code heimdall-io} in production. A same-thread executor
     *     is a legitimate choice in a test and is what makes dispatch assertable without a latch —
     *     note that it also hides the queued-task race this class guards against, so the test for
     *     that one has to use a real pool.
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
     * Subscribes to deaths.
     *
     * <p>Never fires on a proxy: neither Velocity nor BungeeCord has a death event, because a proxy
     * does not see the game state that produces one. See {@link PlayerDeathListener}.
     */
    public Registration onDeath(PlayerDeathListener listener) {
        return register(deathListeners, listener);
    }

    /**
     * Reports a join. Called by the platform adapter, on the platform's event thread.
     *
     * <p>Returns as soon as the hand-off is queued. Never throws.
     */
    public void join(PlayerHandle player, long timestampMs) {
        dispatchSession("join", joinListeners, player, timestampMs);
    }

    /**
     * Reports a quit. Called by the platform adapter, on the platform's event thread.
     *
     * <p>The handle names a player who has already gone; every {@link PlayerHandle} method tolerates
     * that by doing nothing, so a listener does not have to check.
     */
    public void quit(PlayerHandle player, long timestampMs) {
        dispatchSession("quit", quitListeners, player, timestampMs);
    }

    /**
     * Reports a death. Called by the platform adapter, on the platform's event thread.
     *
     * <p>Returns as soon as the hand-off is queued. Never throws.
     *
     * @param deathMessage the server's own message, or {@code null} when it suppressed one. Passed
     *     through untouched — see {@link PlayerDeathListener}
     */
    public void death(final PlayerHandle player, final String deathMessage, final long timestampMs) {
        if (player == null || deathListeners.isEmpty()) {
            return;
        }
        final List<Subscription<PlayerDeathListener>> snapshot =
                new ArrayList<Subscription<PlayerDeathListener>>(deathListeners);
        handOff("death", player, new Runnable() {
            @Override
            public void run() {
                deliver("death", snapshot, player, new Delivery<PlayerDeathListener>() {
                    @Override
                    public void deliver(PlayerDeathListener listener) {
                        listener.onPlayerDeath(player, deathMessage, timestampMs);
                    }
                });
            }
        });
    }

    /** How many join listeners are registered. For tests and diagnostics. */
    public int joinListenerCount() {
        return joinListeners.size();
    }

    /** How many quit listeners are registered. For tests and diagnostics. */
    public int quitListenerCount() {
        return quitListeners.size();
    }

    /** How many death listeners are registered. For tests and diagnostics. */
    public int deathListenerCount() {
        return deathListeners.size();
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private <L> Registration register(
            final CopyOnWriteArrayList<Subscription<L>> into, final L listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener is required");
        }
        final Subscription<L> subscription = new Subscription<L>(listener);
        into.add(subscription);
        return Registration.once(new Runnable() {
            @Override
            public void run() {
                // Deactivate first, then unlist — the order is the whole guarantee. A task already
                // queued re-checks this flag before it runs, so a listener can never fire after its
                // registration was closed; it may only be dropped slightly before.
                subscription.active.set(false);
                into.remove(subscription);
            }
        });
    }

    private void dispatchSession(
            final String what,
            final List<Subscription<PlayerSessionListener>> listeners,
            final PlayerHandle player,
            final long timestampMs) {
        if (player == null || listeners.isEmpty()) {
            return;
        }
        // Snapshotted on the event thread rather than inside the task: the list is copy-on-write, so
        // either would be safe to iterate, but taking the view here means a listener registered
        // between the event and the hand-off does not see an event from before it existed.
        final List<Subscription<PlayerSessionListener>> snapshot =
                new ArrayList<Subscription<PlayerSessionListener>>(listeners);
        handOff(what, player, new Runnable() {
            @Override
            public void run() {
                deliver(what, snapshot, player, new Delivery<PlayerSessionListener>() {
                    @Override
                    public void deliver(PlayerSessionListener listener) {
                        listener.onPlayerSession(player, timestampMs);
                    }
                });
            }
        });
    }

    /** Queues one delivery task, containing everything the executor can do to refuse it. */
    private void handOff(final String what, final PlayerHandle player, Runnable task) {
        try {
            executor.execute(task);
        } catch (RejectedExecutionException shuttingDown) {
            logger.debug(() -> "dropping a player " + what + " notification: the pools are "
                    + "shutting down");
        } catch (RuntimeException broken) {
            // An executor that throws anything else is a bug in the pools, not in the platform
            // adapter that called this — and a join event must never become a server-side error.
            logger.error("could not hand off a player " + what + " notification", broken);
        }
    }

    private <L> void deliver(
            String what, List<Subscription<L>> snapshot, PlayerHandle player, Delivery<L> delivery) {
        boolean reported = false;
        for (Subscription<L> subscription : snapshot) {
            // Re-checked here, inside the executor, and not only when the snapshot was taken. This
            // is the line that stops a disabled module's listener running against collaborators it
            // has already closed. See the class javadoc.
            if (!subscription.active.get()) {
                continue;
            }
            try {
                delivery.deliver(subscription.listener);
            } catch (Throwable broken) {
                // Throwable, not RuntimeException. What is worth being careful about here is a
                // NoSuchMethodError or NoClassDefFoundError from a listener that reached an API
                // which moved between server versions — the class of failure departures D43, D44
                // and D45 are about. Letting one escape onto a shared pool thread would look like
                // the pool's own fault and would take the remaining listeners down with it.
                if (!reported) {
                    reported = true;
                    logger.error("a player " + what + " listener failed for " + player.name(), broken);
                } else {
                    logger.debug(() -> "another player " + what + " listener failed for "
                            + player.name() + ": " + broken);
                }
            }
        }
    }

    /**
     * How one listener is invoked, so the containment loop above is written once.
     *
     * <p>The two listener interfaces take different arguments, and the interesting part —
     * re-checking the active flag, containing a {@code Throwable}, reporting only the first failure
     * — is identical for both. Without this the death dispatch would be a second copy of that loop,
     * which is exactly where the two would drift.
     */
    private interface Delivery<L> {
        void deliver(L listener);
    }

    /**
     * One listener and whether it is still live.
     *
     * <p>Identity-compared, so two modules registering the same lambda stay independent — the same
     * reason {@code SubscriptionRegistry} wraps its handlers rather than storing them bare.
     */
    private static final class Subscription<L> {

        private final L listener;
        private final AtomicBoolean active = new AtomicBoolean(true);

        Subscription(L listener) {
            this.listener = listener;
        }
    }
}
