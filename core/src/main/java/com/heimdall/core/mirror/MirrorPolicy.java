package com.heimdall.core.mirror;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * How long a mirrored value may be trusted, and how often the mirror is allowed to hit the disk.
 *
 * <p>Immutable. The two time bounds answer different questions and both are needed:
 *
 * <ul>
 *   <li>{@link #windowMs()} — how long a freshly verified value is good for.
 *   <li>{@link #maxExtensionMs()} — the hard ceiling on how far past the <em>last real
 *       verification</em> activity may push that, whatever the window says. Set it to 0 to disable
 *       the bound entirely, which restores v2's pre-#771 unbounded behaviour.
 * </ul>
 *
 * <p>The clock lives here too. Every question this class answers is "has enough time passed?", so
 * what "now" means is part of the policy rather than a separate constructor argument on the store —
 * which is what it was, and it was the reason {@code MirrorStore} needed two overlapping factory
 * methods.
 */
public final class MirrorPolicy {

    /** The system clock, used unless a caller supplies its own. */
    private static final LongSupplier SYSTEM_CLOCK = new LongSupplier() {
        @Override
        public long getAsLong() {
            return System.currentTimeMillis();
        }
    };

    private final long windowMs;
    private final long maxExtensionMs;
    private final long saveDebounceMs;
    private final LongSupplier clock;

    private MirrorPolicy(Builder builder) {
        this.windowMs = Math.max(0, builder.windowMs);
        this.maxExtensionMs = Math.max(0, builder.maxExtensionMs);
        this.saveDebounceMs = Math.max(0, builder.saveDebounceMs);
        this.clock = builder.clock == null ? SYSTEM_CLOCK : builder.clock;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** How long a value is good for after a real verification. */
    public long windowMs() {
        return windowMs;
    }

    /** The extension ceiling past {@code lastVerified}; 0 disables the bound. */
    public long maxExtensionMs() {
        return maxExtensionMs;
    }

    /** Whether the extension ceiling is in force. */
    public boolean isExtensionBounded() {
        return maxExtensionMs > 0;
    }

    /**
     * How long to coalesce mutations before writing to disk; 0 writes synchronously.
     *
     * <p>Non-zero is strongly preferred. v2 rewrote its whole cache file, synchronously, on nearly
     * every mutation — including from the login thread, on every join and every leave.
     */
    public long saveDebounceMs() {
        return saveDebounceMs;
    }

    /** The current time in epoch millis, as this policy reckons it. */
    public long now() {
        return clock.getAsLong();
    }

    /**
     * When an entry really stops being trustworthy: its own expiry, clamped by the ceiling.
     *
     * <p>The bound lives here rather than in {@link MirrorStore} so there is exactly one expression
     * of it, applied both when a new expiry is written ({@link #cap}) and every time a value is
     * read. The read-side check is not redundant: it is what stops an entry written by an older
     * version, or edited on disk, from being served past the ceiling.
     *
     * @param cacheExpiry the entry's own expiry
     * @param lastVerified when the bot last confirmed the entry
     */
    public long effectiveExpiry(long cacheExpiry, long lastVerified) {
        if (!isExtensionBounded()) {
            return cacheExpiry;
        }
        return Math.min(cacheExpiry, saturatingAdd(lastVerified, maxExtensionMs));
    }

    /**
     * Clamps a proposed new expiry to the ceiling, so nothing persisted ever claims a longer
     * lifetime than the bound allows.
     */
    public long cap(long proposedExpiry, long lastVerified) {
        if (!isExtensionBounded()) {
            return proposedExpiry;
        }
        return Math.min(proposedExpiry, saturatingAdd(lastVerified, maxExtensionMs));
    }

    /**
     * {@code a + b}, clamped to {@link Long#MAX_VALUE} instead of wrapping.
     *
     * <p>A ceiling of, say, a hundred years in milliseconds overflows a signed long once added to a
     * current timestamp, and the result is negative — so {@code min(cacheExpiry, ceiling)} returns
     * the negative number and <em>everything</em> is expired. That is the exact opposite of what
     * someone setting an enormous ceiling was asking for, and it fails silently.
     */
    private static long saturatingAdd(long a, long b) {
        long sum = a + b;
        // Overflow iff the operands share a sign and the result does not.
        if (((a ^ sum) & (b ^ sum)) < 0) {
            return Long.MAX_VALUE;
        }
        return sum;
    }

    @Override
    public String toString() {
        return "MirrorPolicy{windowMs=" + windowMs
                + ", maxExtensionMs=" + (isExtensionBounded() ? String.valueOf(maxExtensionMs) : "unbounded")
                + ", saveDebounceMs=" + saveDebounceMs + "}";
    }

    /** Mutable writer. Every value is clamped to be non-negative on build. */
    public static final class Builder {

        private long windowMs = TimeUnit.MINUTES.toMillis(60);
        private long maxExtensionMs = TimeUnit.HOURS.toMillis(24);
        private long saveDebounceMs = TimeUnit.SECONDS.toMillis(5);
        private LongSupplier clock;

        private Builder() {
        }

        public Builder windowMs(long value) {
            this.windowMs = value;
            return this;
        }

        public Builder windowMinutes(long value) {
            return windowMs(TimeUnit.MINUTES.toMillis(value));
        }

        /** 0 disables the ceiling. */
        public Builder maxExtensionMs(long value) {
            this.maxExtensionMs = value;
            return this;
        }

        /** 0 disables the ceiling. */
        public Builder maxExtensionHours(long value) {
            return maxExtensionMs(value <= 0 ? 0 : TimeUnit.HOURS.toMillis(value));
        }

        /** 0 makes every mutation write synchronously — for tests, not for a login path. */
        public Builder saveDebounceMs(long value) {
            this.saveDebounceMs = value;
            return this;
        }

        /**
         * Replaces the system clock.
         *
         * <p>For tests, which need to move time by hours without waiting for them. Nothing in
         * production should call this.
         */
        public Builder clock(LongSupplier value) {
            this.clock = value;
            return this;
        }

        public MirrorPolicy build() {
            return new MirrorPolicy(this);
        }
    }
}
