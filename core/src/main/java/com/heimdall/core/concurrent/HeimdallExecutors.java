package com.heimdall.core.concurrent;

import com.heimdall.core.log.HeimdallLogger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The plugin's two thread pools, owned together so they can be shut down together.
 *
 * <ul>
 *   <li>{@link #io()} — a fixed pool for blocking network work. Fixed rather than cached because
 *       the bound is the point: a server being mass-joined must queue its API calls, not open a
 *       thread per player. Four by default, tunable at construction for a proxy fronting a large
 *       network.
 *   <li>{@link #scheduler()} — one thread for periodic work: the whitelist poll, mirror cleanup,
 *       debounced saves. Single-threaded on purpose, so two periodic tasks can never interleave
 *       and there is exactly one answer to "what is Heimdall doing on a timer?".
 * </ul>
 *
 * <p>A third pool, {@code heimdall-ws}, arrives with the tunnel in phase 1b. It is not created
 * speculatively here: an idle named pool is still a thread an operator has to account for.
 *
 * <p>Both are daemon-threaded and named — see {@link NamedThreadFactory}.
 */
public final class HeimdallExecutors implements AutoCloseable {

    /** Enough to keep a busy login path moving without letting a join storm open a thread per player. */
    public static final int DEFAULT_IO_THREADS = 4;

    /** How long shutdown waits for in-flight work before interrupting it. */
    public static final long DEFAULT_SHUTDOWN_TIMEOUT_MS = 5000;

    private final HeimdallLogger logger;
    private final ExecutorService io;
    private final ScheduledExecutorService scheduler;
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
        this.io = Executors.newFixedThreadPool(Math.max(1, ioThreads), NamedThreadFactory.numbered("heimdall-io"));
        this.scheduler = Executors.newSingleThreadScheduledExecutor(NamedThreadFactory.single("heimdall-sched"));
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

    /** Whether {@link #shutdown()} has been called. */
    public boolean isShutdown() {
        return shutdown.get();
    }

    /** Stops both pools, waiting up to {@link #DEFAULT_SHUTDOWN_TIMEOUT_MS} for in-flight work. */
    public void shutdown() {
        shutdown(DEFAULT_SHUTDOWN_TIMEOUT_MS);
    }

    /**
     * Stops both pools, waiting up to {@code timeoutMs} <em>each</em> for in-flight work to finish
     * before interrupting it.
     *
     * <p>Idempotent. Stragglers are logged at severe rather than passed over: a task still running
     * after the grace period is holding a socket or a file handle open past the point the plugin
     * claims to have stopped, and that is worth a line in the server log naming which pool.
     */
    public void shutdown(long timeoutMs) {
        if (!shutdown.compareAndSet(false, true)) {
            return;
        }
        // Scheduler first: otherwise a periodic task can enqueue new IO work while the IO pool is
        // draining, and the drain never finishes.
        drain("heimdall-sched", scheduler, timeoutMs);
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
