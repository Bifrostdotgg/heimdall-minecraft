package com.heimdall.core.tunnel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A scheduler that hands its tasks to the test instead of running them.
 *
 * <p>The negotiation deadline is the only thing in the tunnel whose <em>interleaving</em> with
 * another thread is the behaviour under test, and an interleaving cannot be asserted on if the two
 * sides are both driven by wall-clock timers. Here "the deadline fired" is a method call, so a test
 * can put it exactly where it wants relative to an inbound ack.
 *
 * <p>Extends {@link ScheduledThreadPoolExecutor} with a zero core pool rather than implementing the
 * interface by hand, so nothing it does not capture can run either. Only
 * {@link #schedule(Runnable, long, TimeUnit)} is overridden — the one method the negotiator uses —
 * and it returns a real cancellable handle, because the negotiator cancels it.
 */
final class CapturingScheduler extends ScheduledThreadPoolExecutor {

    private final List<CapturedTask> captured =
            Collections.synchronizedList(new ArrayList<CapturedTask>());

    CapturingScheduler() {
        super(0);
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        CapturedTask task = new CapturedTask(command);
        captured.add(task);
        return task;
    }

    /** How many tasks have been scheduled, cancelled or not. */
    int scheduledCount() {
        return captured.size();
    }

    /** Whether the most recently scheduled task is still armed. */
    boolean latestIsArmed() {
        synchronized (captured) {
            return !captured.isEmpty() && !captured.get(captured.size() - 1).isCancelled();
        }
    }

    /**
     * Runs the most recently scheduled task, cancelled or not.
     *
     * <p>Ignoring cancellation is the point: a real {@code ScheduledFuture} that has already begun
     * running cannot be cancelled, and "the deadline was in flight when the ack landed" is exactly
     * the case worth testing.
     */
    void runLatest() {
        CapturedTask task;
        synchronized (captured) {
            if (captured.isEmpty()) {
                throw new IllegalStateException("nothing has been scheduled");
            }
            task = captured.get(captured.size() - 1);
        }
        task.command.run();
    }

    /** One captured task, and a handle the negotiator can cancel. */
    private static final class CapturedTask implements ScheduledFuture<Object> {

        private final Runnable command;
        private final AtomicBoolean cancelled = new AtomicBoolean();

        CapturedTask(Runnable command) {
            this.command = command;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return 0;
        }

        @Override
        public int compareTo(Delayed other) {
            return 0;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return cancelled.compareAndSet(false, true);
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }

        @Override
        public boolean isDone() {
            return cancelled.get();
        }

        @Override
        public Object get() {
            return null;
        }

        @Override
        public Object get(long timeout, TimeUnit unit) {
            return null;
        }
    }
}
