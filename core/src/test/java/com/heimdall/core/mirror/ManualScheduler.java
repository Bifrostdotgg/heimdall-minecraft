package com.heimdall.core.mirror;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * A scheduler that never runs anything until a test tells it to.
 *
 * <p>The debounce tests used to work by scheduling a real 120–150 ms window and then sleeping past
 * it, which makes them slow, makes them flake on a loaded CI runner, and — worse — makes a genuine
 * regression look like a timing blip. Here "the debounce elapsed" is a method call.
 *
 * <p>Extends {@link ScheduledThreadPoolExecutor} rather than implementing the fifteen-method
 * interface by hand; only {@link #schedule(Runnable, long, TimeUnit)} is overridden, which is the
 * one method {@code DebouncedWriter} uses. A zero core pool size means nothing it does not capture
 * can run either.
 */
final class ManualScheduler extends ScheduledThreadPoolExecutor {

    private final Deque<Runnable> pending = new ArrayDeque<Runnable>();

    ManualScheduler() {
        super(0);
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        synchronized (pending) {
            pending.add(command);
        }
        // DebouncedWriter discards the handle; returning null keeps this fixture to the one method
        // it actually has to model. Anything that starts using the handle will fail loudly here
        // rather than quietly getting a fake.
        return null;
    }

    /** How many scheduled tasks are waiting. The coalescing assertion. */
    int pendingCount() {
        synchronized (pending) {
            return pending.size();
        }
    }

    /**
     * Runs the tasks scheduled <em>so far</em>, as if their delays had elapsed.
     *
     * <p>Takes a snapshot first rather than draining in a loop. A write that schedules another
     * write — which is what a mutation arriving mid-flush does — would otherwise be run by the same
     * call, and the test could not tell "the follow-up was scheduled" from "it never happened".
     */
    void runPending() {
        Runnable[] due;
        synchronized (pending) {
            due = pending.toArray(new Runnable[0]);
            pending.clear();
        }
        for (Runnable task : due) {
            task.run();
        }
    }
}
