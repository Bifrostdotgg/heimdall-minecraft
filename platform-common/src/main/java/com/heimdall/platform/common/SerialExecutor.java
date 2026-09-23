package com.heimdall.platform.common;

import com.heimdall.core.log.HeimdallLogger;
import java.util.ArrayDeque;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Runs tasks one at a time, in submission order, on a borrowed executor.
 *
 * <p>Borrowed rather than a thread of its own: the conformance rules want every thread owned and
 * named by {@code HeimdallExecutors}, and a trickle of group reads does not justify one. At most
 * one task from this queue occupies a {@code heimdall-io} thread at a time, so a burst of
 * LuckPerms events (a bulk {@code /lp} edit) cannot take over the pool the login path uses.
 *
 * <p>Its own file rather than nested in {@link LuckPermsIntegration} so it can be tested without
 * LuckPerms on the classpath: that class names LuckPerms types and cannot be linked without them.
 *
 * <p>A task that throws is logged and the queue carries on. A rejected hand-off to the delegate
 * (the pools shutting down) drops everything queued and rethrows, so the caller sees it and nothing
 * is left marked as draining against an executor that will never run it.
 */
final class SerialExecutor implements Executor {

    private final Executor delegate;
    private final HeimdallLogger logger;
    private final ArrayDeque<Runnable> queue = new ArrayDeque<Runnable>();
    private boolean draining;

    SerialExecutor(Executor delegate, HeimdallLogger logger) {
        this.delegate = delegate;
        this.logger = logger;
    }

    @Override
    public void execute(Runnable task) {
        synchronized (queue) {
            queue.addLast(task);
            if (draining) {
                return;
            }
            draining = true;
        }
        try {
            delegate.execute(new Runnable() {
                @Override
                public void run() {
                    drain();
                }
            });
        } catch (RejectedExecutionException e) {
            synchronized (queue) {
                queue.clear();
                draining = false;
            }
            throw e;
        }
    }

    private void drain() {
        while (true) {
            Runnable next;
            synchronized (queue) {
                next = queue.pollFirst();
                if (next == null) {
                    draining = false;
                    return;
                }
            }
            try {
                next.run();
            } catch (RuntimeException e) {
                logger.warn("a queued LuckPerms task failed: " + e.getMessage());
            }
        }
    }
}
