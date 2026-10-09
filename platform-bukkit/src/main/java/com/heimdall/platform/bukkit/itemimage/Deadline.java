package com.heimdall.platform.bukkit.itemimage;

/**
 * A render's time budget, checked cooperatively between steps.
 *
 * <p>A render runs on Heimdall's single render thread, and a pathological pack (a model chain that
 * loops through a thousand files, an enormous texture) must not hold that thread for everyone else.
 * There is no safe way to stop a thread from outside, so the steps that can repeat (model hops,
 * elements, faces, text lines) call {@link #check()}, and an overrun unwinds as
 * {@link Exceeded}, which the renderer turns into "no image".
 */
final class Deadline {

    /** Thrown by {@link #check()} once the budget is spent. */
    static final class Exceeded extends RuntimeException {

        private static final long serialVersionUID = 1L;

        Exceeded() {
            super("render time budget exceeded", null, false, false);
        }
    }

    private final long endNanos;

    private Deadline(long endNanos) {
        this.endNanos = endNanos;
    }

    static Deadline in(long millis) {
        return new Deadline(System.nanoTime() + millis * 1_000_000L);
    }

    /** A deadline that never passes, for tests that are not about time. */
    static Deadline none() {
        return new Deadline(Long.MAX_VALUE);
    }

    void check() {
        if (endNanos != Long.MAX_VALUE && System.nanoTime() - endNanos > 0) {
            throw new Exceeded();
        }
    }
}
