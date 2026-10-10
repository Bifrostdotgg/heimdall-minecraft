package com.heimdall.shell.hotswap;

import com.heimdall.shell.contract.CoreIdentity;

/**
 * How a swap ended. Immutable.
 *
 * <p>The four kinds are the four things an operator needs to tell apart, because what they do next
 * differs: nothing ({@link Kind#SWAPPED}), nothing but read why ({@link Kind#REFUSED}, where the
 * running core was never touched), investigate ({@link Kind#ROLLED_BACK}, where the previous core is
 * running again), or restart ({@link Kind#NO_CORE}).
 */
public final class SwapOutcome {

    /** The four ways a swap can end. */
    public enum Kind {
        /** The new core is running. */
        SWAPPED,
        /** Nothing was changed: the new core was never started. */
        REFUSED,
        /** The new core failed to start and the previous one is running again. */
        ROLLED_BACK,
        /** The new core failed and so did the rollback: no core is running. */
        NO_CORE
    }

    private final Kind kind;
    private final String message;
    private final CoreIdentity running;

    SwapOutcome(Kind kind, String message, CoreIdentity running) {
        this.kind = kind;
        this.message = message == null ? "" : message;
        this.running = running;
    }

    public Kind kind() {
        return kind;
    }

    /** One operator-facing sentence, without colour codes. */
    public String message() {
        return message;
    }

    /** The core running after the swap, or {@code null} for {@link Kind#NO_CORE}. */
    public CoreIdentity running() {
        return running;
    }

    /** Whether the requested core is now the running one. */
    public boolean succeeded() {
        return kind == Kind.SWAPPED;
    }

    @Override
    public String toString() {
        return kind + ": " + message;
    }
}
