package com.heimdall.core.concurrent;

import com.heimdall.core.log.HeimdallLogger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The plugin's three thread pools, owned together so they can be shut down together.
 *
 * <ul>
 *   <li>{@link #io()} — a fixed pool for blocking network work. Fixed rather than cached because
 *       the bound is the point: a server being mass-joined must queue its API calls, not open a
 *       thread per player. Four by default, tunable at construction for a proxy fronting a large
 *       network.
 *   <li>{@link #scheduler()} — one thread for periodic work: the whitelist poll, mirror cleanup,
 *       debounced saves. Single-threaded on purpose, so two periodic tasks can never interleave
 *       and there is exactly one answer to "what is Heimdall doing on a timer?".
 *   <li>{@link #ws()} — one thread for the tunnel's own timing: the heartbeat tick, the backoff
 *       reconnect, request timeouts, the capability-negotiation deadline.
 * </ul>
 *
 * <p><strong>Why the tunnel does not share {@link #scheduler()}.</strong> Both are single-threaded,
 * so sharing would put the heartbeat behind whatever periodic work happened to be running. A
 * whitelist poll that blocks for its full retry budget is tens of seconds, and a heartbeat tick
 * that arrives that late is indistinguishable — to the client's own timeout check and to the bot's
 * liveness sweep — from a dead link. The tunnel would then abort a perfectly healthy socket
 * because a poll was slow. One extra idle daemon thread is the cheaper side of that trade.
 *
 * <p>All three are daemon-threaded and named — see {@link NamedThreadFactory}.
 *
 * <p><strong>Thread-safe.</strong> The accessors may be called from any thread and always return
 * the same three executors; {@link #shutdown()} is idempotent and safe to race. <strong>This class
 * owns all three pools</strong> — it is the only thing that should shut them down, and everything
 * handed one (the API client, the mirrors, the tunnel) borrows it and must not.
 */
public final class HeimdallExecutors implements AutoCloseable {

    /** Enough to keep a busy login path moving without letting a join storm open a thread per player. */
    public static final int DEFAULT_IO_THREADS = 4;

    /** How long shutdown waits for in-flight work before interrupting it. */
    public static final long DEFAULT_SHUTDOWN_TIMEOUT_MS = 5000;

    private final HeimdallLogger logger;
    private final ExecutorService io;
    private final ScheduledExecutorService scheduler;
    private final ScheduledExecutorService ws;
    private final AtomicBoolean shutdown = new AtomicBoolean();

    /** Creates the pools with {@link #DEFAULT_IO_THREADS} IO threads. */
    public HeimdallExecutors(HeimdallLogger logger) {
        this(logger, DEFAULT_IO_THREADS);
    }

    /**
     * @param ioThreads size of the IO pool; clamped to at least 1
     */
    public HeimdallExecutors(HeimdallLogger logger, int ioThreads) {
        if (logger == null) {
            throw new IllegalArgumentException("logger is required");
        }
        this.logger = logger;
        this.io = Executors.newFixedThreadPool(
                Math.max(1, ioThreads), NamedThreadFactory.numbered("heimdall-io"));

        // Constructed directly rather than via Executors.newSingleThreadScheduledExecutor, whose
        // return value is wrapped in an unconfigurable delegate — these two policies need the real
        // type. Without the first, a debounced mirror save scheduled seconds ago still runs after
        // shutdown() and burns the whole grace period waiting for it, for a write that close()
        // already performed synchronously. Without the second, a periodic poll keeps firing during
        // shutdown and feeds work to an IO pool that is trying to drain.
        this.scheduler = singleThreadScheduler("heimdall-sched");
        // The same three policies matter here for the same reasons, and one more specific to the
        // tunnel: setRemoveOnCancelPolicy keeps a cancelled sendAndWait timeout from sitting in the
        // queue for its whole (potentially minute-long) delay. Under a burst of correlated requests
        // that is the difference between a queue that drains and one that grows.
        this.ws = singleThreadScheduler("heimdall-ws");
    }

    private static ScheduledExecutorService singleThreadScheduler(String name) {
        ScheduledThreadPoolExecutor scheduled =
                new ScheduledThreadPoolExecutor(1, NamedThreadFactory.single(name));
        scheduled.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduled.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        // Cancelled tasks are removed from the queue immediately rather than lingering until their
        // delay elapses, so a long-interval task that gets cancelled is not retained for hours.
        scheduled.setRemoveOnCancelPolicy(true);
        return scheduled;
    }

    /**
     * The pool every blocking call runs on.
     *
     * <p>Hand this to {@link java.util.concurrent.CompletableFuture} explicitly — the executor-less
     * {@code *Async} overloads are banned by the conformance rules.
     */
    public ExecutorService io() {
        return io;
    }

    /** The single-threaded scheduler for periodic and delayed work. */
    public ScheduledExecutorService scheduler() {
        return scheduler;
    }

    /**
     * The tunnel's own single-threaded scheduler: heartbeat, reconnect backoff, request timeouts,
     * negotiation deadline.
     *
     * <p>Nothing else should schedule here. The point of a separate pool is that the tunnel's sense
     * of time is not affected by anything else the plugin does on a timer.
     */
    public ScheduledExecutorService ws() {
        return ws;
    }

    /** Whether {@link #shutdown()} has been called. */
    public boolean isShutdown() {
        return shutdown.get();
    }

    /** Stops all three pools, waiting up to {@link #DEFAULT_SHUTDOWN_TIMEOUT_MS} for in-flight work. */
    public void shutdown() {
        shutdown(DEFAULT_SHUTDOWN_TIMEOUT_MS);
    }

    /**
     * Stops all three pools, waiting up to {@code timeoutMs} <em>each</em> for in-flight work to
     * finish before interrupting it.
     *
     * <p>Idempotent. Stragglers are logged at severe rather than passed over: a task still running
     * after the grace period is holding a socket or a file handle open past the point the plugin
     * claims to have stopped, and that is worth a line in the server log naming which pool.
     */
    public void shutdown(long timeoutMs) {
        if (!shutdown.compareAndSet(false, true)) {
            return;
        }
        // Both schedulers first: otherwise a periodic task can enqueue new IO work while the IO
        // pool is draining, and the drain never finishes. The tunnel's heartbeat is exactly such a
        // task — it can hand a health snapshot to the IO pool on every tick.
        drain("heimdall-sched", scheduler, timeoutMs);
        drain("heimdall-ws", ws, timeoutMs);
        drain("heimdall-io", io, timeoutMs);
    }

    /** Same as {@link #shutdown()}, so this can be used in a try-with-resources. */
    @Override
    public void close() {
        shutdown();
    }

    private void drain(String name, ExecutorService executor, long timeoutMs) {
        executor.shutdown();
        try {
            if (executor.awaitTermination(Math.max(0, timeoutMs), TimeUnit.MILLISECONDS)) {
                return;
            }
            logger.severe(name + " did not finish within " + timeoutMs + "ms — interrupting it");
            executor.shutdownNow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
            logger.warn("Interrupted while shutting down " + name);
        }
    }
}
