package com.heimdall.core.util;

/**
 * A handle that undoes one registration, and nothing else.
 *
 * <p>Every registry in v3 — tunnel subscriptions, pipeline interceptors, config listeners,
 * scheduled module tasks — hands one of these back instead of exposing a matching
 * {@code removeX(handler)}. Two reasons, both learned from v2:
 *
 * <ul>
 *   <li><strong>Unregistering by identity does not work for lambdas.</strong> A caller that
 *       registered {@code msg -> handle(msg)} has nothing to pass to a remove method; v2's answer
 *       was to keep handlers in fields purely so they could be removed, or more often to never
 *       remove them at all.
 *   <li><strong>Ownership becomes trackable.</strong> {@code ModuleManager} can collect the
 *       registrations a module made without knowing what any of them were, which is what makes
 *       {@code disable(id)} able to unwind a module that forgot to clean up after itself.
 * </ul>
 *
 * <p><strong>Implementations must be idempotent and thread-safe.</strong> Closing twice is a
 * no-op, not an error — a module that closes its own registration and is then disabled would
 * otherwise fail on the second close, during teardown, where it is least useful.
 *
 * <p>Extends {@link AutoCloseable} with a {@code close()} that declares no checked exception, so it
 * can be used in try-with-resources without a pointless catch.
 */
public interface Registration extends AutoCloseable {

    /** A registration that has nothing to undo. */
    Registration NONE = new Registration() {
        @Override
        public void close() {
        }
    };

    /** Undoes the registration. Idempotent. */
    @Override
    void close();

    /**
     * Wraps a {@link Runnable} as a registration that runs it at most once.
     *
     * <p>The at-most-once part is the whole value: registries hand out the same registration to a
     * caller that may close it and to {@code ModuleManager}, which will close it again on teardown.
     */
    static Registration once(final Runnable undo) {
        if (undo == null) {
            throw new IllegalArgumentException("undo is required");
        }
        return new OnceRegistration(undo);
    }
}
