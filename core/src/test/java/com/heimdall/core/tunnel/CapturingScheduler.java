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
        CapturedTask task = new CapturedTask(command, unit.toMillis(delay));
        captured.add(task);
        return task;
    }

    /**
     * Captured too, and never run.
     *
     * <p>A {@link ScheduledThreadPoolExecutor} with a zero core pool still starts a worker for a
     * periodic task, so without this override the heartbeat would tick in the background of tests
     * that are trying to drive it by hand.
     */
    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(
            Runnable command, long initialDelay, long period, TimeUnit unit) {
        CapturedTask task = new CapturedTask(command, unit.toMillis(initialDelay));
        captured.add(task);
        return task;
    }

    /**
     * Runs every task captured <em>so far</em>, cancelled ones excluded, in scheduling order.
     *
     * <p>Snapshots first: a reconnect task opens a socket, which schedules a fresh heartbeat and
     * deadline, and running those in the same call would make it impossible to tell "one reconnect
     * happened" from "one reconnect happened and then everything it set up ran too".
     */
    void runPending() {
        CapturedTask[] due;
        synchronized (captured) {
            due = captured.toArray(new CapturedTask[0]);
            captured.clear();
        }
        for (CapturedTask task : due) {
            if (!task.isCancelled()) {
                task.command.run();
            }
        }
    }

    /** The delays, in milliseconds, of every task captured so far — cancelled ones included. */
    java.util.List<Long> delaysMs() {
        synchronized (captured) {
            java.util.List<Long> delays = new ArrayList<Long>();
            for (CapturedTask task : captured) {
                delays.add(Long.valueOf(task.delayMs));
            }
            return delays;
        }
    }

    /** Forgets everything captured so far, so a test can measure one phase at a time. */
    void clearCaptured() {
        captured.clear();
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
        private final long delayMs;
        private final AtomicBoolean cancelled = new AtomicBoolean();

        CapturedTask(Runnable command, long delayMs) {
            this.command = command;
            this.delayMs = delayMs;
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
