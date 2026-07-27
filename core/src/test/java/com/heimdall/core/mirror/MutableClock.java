package com.heimdall.core.mirror;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * A clock the tests move by hand.
 *
 * <p>v2's cache tests asserted the ceiling by comparing persisted timestamps against
 * {@code System.currentTimeMillis()} with slack, which meant the interesting case — an entry
 * verified 48 hours ago — had to be faked by writing a doctored file rather than by letting time
 * pass. Driving the clock instead lets the same scenarios be expressed directly, and removes the
 * slack.
 */
final class MutableClock implements LongSupplier {

    /** An arbitrary fixed origin, so failures quote stable numbers. */
    static final long ORIGIN = 1_700_000_000_000L;

    private long now = ORIGIN;

    @Override
    public long getAsLong() {
        return now;
    }

    long now() {
        return now;
    }

    void advance(long millis) {
        now += millis;
    }

    void advanceMinutes(long minutes) {
        advance(TimeUnit.MINUTES.toMillis(minutes));
    }

    void advanceHours(long hours) {
        advance(TimeUnit.HOURS.toMillis(hours));
    }
}
