package com.heimdall.module.offenses;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * Turns a future's failure into the one sentence an operator should be shown.
 *
 * <p>Exists because a {@link java.util.concurrent.CompletableFuture} hands its callback a
 * {@link CompletionException} wrapping the real cause, and printing that wrapper tells whoever ran
 * {@code /offend} "java.util.concurrent.CompletionException: com.heimdall.core.http.ApiError:
 * UNKNOWN_OFFENSE …" — the useful half buried behind two class names. v2 had the same helper
 * ({@code extractThrowableMessage}) for the same reason, and it is needed in two places here: the
 * command reports the failure to the sender, and the cache logs it.
 *
 * <p>Stateless; safe from any thread.
 */
final class Failures {

    private Failures() {
    }

    /**
     * The root cause's message, or its type name when it carries none.
     *
     * <p>A {@link java.io.UncheckedIOException} from a connect failure often has a message that is
     * only the address, and a bare exception type with no message at all is possible — so the class
     * name is the fallback rather than the empty string, which would render as "Failed to record
     * offense: " and say nothing.
     */
    static String describe(Throwable failure) {
        Throwable root = unwrap(failure);
        if (root == null) {
            return "unknown error";
        }
        String message = root.getMessage();
        return message == null || message.trim().isEmpty()
                ? root.getClass().getSimpleName()
                : message.trim();
    }

    /** Peels the {@code CompletionException} / {@code ExecutionException} wrappers off a cause. */
    static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        // Bounded rather than `while (true)`: a self-referential cause chain is possible (a
        // throwable whose cause is itself is legal), and an unbounded unwrap would hang the thread
        // reporting an error, which is the worst possible place to spin.
        for (int depth = 0; depth < 16; depth++) {
            if (!(current instanceof CompletionException) && !(current instanceof ExecutionException)) {
                return current;
            }
            Throwable cause = current.getCause();
            if (cause == null || cause == current) {
                return current;
            }
            current = cause;
        }
        return current;
    }
}
