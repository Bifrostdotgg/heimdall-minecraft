package com.heimdall.core.tunnel;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * When to reconnect, and — more importantly — how to make sure only one reconnect happens.
 *
 * <h2>The single-flight gate</h2>
 *
 * <p>Four things can notice the same dead connection: the close callback, the error callback, the
 * connect attempt failing outright, and the heartbeat deciding it has waited long enough. They can
 * and do fire together. Without a gate, each one schedules a reconnect, four {@code doConnect}
 * chains run in parallel, and the server ends up with several live sockets to the same bot — which
 * presents as duplicated role syncs and commands running twice, not as a connection error.
 *
 * <p>{@link #tryClaim()} is that gate: a compare-and-set that exactly one caller wins. The winner
 * schedules the attempt and calls {@link #release()} when the attempt actually starts, reopening
 * the gate for the next failure. v2 had this and its comment is the reason it is here verbatim.
 *
 * <h2>The backoff</h2>
 *
 * <p>Doubling from {@code reconnectDelayMs} to {@code maxReconnectDelayMs}, reset on a successful
 * open. A bot that is redeploying should be retried quickly; a bot whose DNS is wrong should not be
 * hammered every five seconds for a week.
 *
 * <p><strong>Departure from v2:</strong> the current delay is an {@link AtomicLong} rather than a
 * plain {@code long}. v2 wrote it from whichever thread noticed the failure and read it from
 * another, unsynchronised — a benign-looking race that on a 32-bit JVM (which the oldest supported
 * hosts still run) can tear a {@code long} read outright.
 *
 * <p>Thread-safe.
 */
final class ReconnectPolicy {

    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final AtomicLong currentDelayMs;
    private volatile long baseDelayMs;
    private volatile long maxDelayMs;

    ReconnectPolicy(long baseDelayMs, long maxDelayMs) {
        this.baseDelayMs = baseDelayMs;
        this.maxDelayMs = Math.max(baseDelayMs, maxDelayMs);
        this.currentDelayMs = new AtomicLong(baseDelayMs);
    }

    /** Re-reads the bounds after a settings swap, without disturbing the current delay. */
    void updateBounds(long newBaseDelayMs, long newMaxDelayMs) {
        this.baseDelayMs = newBaseDelayMs;
        this.maxDelayMs = Math.max(newBaseDelayMs, newMaxDelayMs);
    }

    /**
     * Claims the right to schedule the next reconnect.
     *
     * @return {@code true} for exactly one caller among any number racing on the same failure
     */
    boolean tryClaim() {
        return scheduled.compareAndSet(false, true);
    }

    /** Reopens the gate. Called when the claimed attempt begins, and on every teardown. */
    void release() {
        scheduled.set(false);
    }

    /**
     * The delay to use for this attempt, doubling the delay that the <em>next</em> one will use.
     *
     * <p>Read-then-advance rather than advance-then-read, so the first retry after a working
     * connection waits the base delay rather than twice it.
     */
    long nextDelayMs() {
        long delay = currentDelayMs.get();
        long doubled = delay > maxDelayMs / 2 ? maxDelayMs : Math.min(delay * 2, maxDelayMs);
        currentDelayMs.set(doubled);
        return delay;
    }

    /** Back to the base delay. Called on a successful open and on a manual reconnect. */
    void reset() {
        currentDelayMs.set(baseDelayMs);
    }
}
