package com.heimdall.core.util;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The {@link Registration#once(Runnable)} implementation: runs its undo action on the first
 * {@code close()} and ignores every later one.
 *
 * <p>The {@link AtomicBoolean} rather than a plain flag because the two closers genuinely race — a
 * module closing its own handle on one thread while {@code ModuleManager} unwinds the same handle
 * on the scheduler is the ordinary case, not a corner one.
 */
final class OnceRegistration implements Registration {

    private final Runnable undo;
    private final AtomicBoolean closed = new AtomicBoolean();

    OnceRegistration(Runnable undo) {
        this.undo = undo;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            undo.run();
        }
    }
}
