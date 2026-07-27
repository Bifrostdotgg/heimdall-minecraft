package com.heimdall.core.mirror;

import java.util.concurrent.TimeUnit;

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
 */
public final class MirrorPolicy {

    private final long windowMs;
    private final long maxExtensionMs;
    private final long saveDebounceMs;

    private MirrorPolicy(Builder builder) {
        this.windowMs = Math.max(0, builder.windowMs);
        this.maxExtensionMs = Math.max(0, builder.maxExtensionMs);
        this.saveDebounceMs = Math.max(0, builder.saveDebounceMs);
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

        public MirrorPolicy build() {
            return new MirrorPolicy(this);
        }
    }
}
