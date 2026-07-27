package com.heimdall.core.module;

/** Where a registered module currently stands. */
public enum ModuleState {

    /** Registered, not running. The state a module returns to when it is toggled off. */
    STOPPED,

    /** Running. */
    ENABLED,

    /**
     * {@link HeimdallModule#enable} threw. Its partial registrations were unwound.
     *
     * <p><strong>Not retried while it stays in the desired set.</strong> A module that fails to
     * start will fail again for the same reason, and retrying on every config push turns one severe
     * log line into a flood that buries the cause. Toggling it off and on in the dashboard clears
     * the state and tries again, which is what an operator does after fixing the underlying problem
     * anyway.
     */
    FAILED,

    /**
     * Excluded by {@link HeimdallModule#roles()} — this instance is the wrong kind of server.
     *
     * <p>Reported rather than hidden, and logged once, so "why is the whitelist not running on this
     * backend?" has an answer in the log instead of being a mystery about the dashboard toggle.
     */
    INELIGIBLE
}
