package com.heimdall.core.testing;

import static org.junit.jupiter.api.Assertions.fail;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Waits for a condition instead of sleeping for a duration.
 *
 * <p>The tunnel tests are unavoidably asynchronous — a reconnect happens on {@code heimdall-ws}, a
 * handler runs on {@code heimdall-io} — so something has to bridge threads. The two ways to do it
 * are a fixed sleep and a poll, and only one of them is honest: a fixed sleep either flakes on a
 * loaded CI runner or is long enough to make the suite slow, and it fails <em>late</em>, blaming
 * timing for what may be a real regression.
 *
 * <p>Polling with a generous ceiling costs nothing when the code is correct (it returns in
 * milliseconds) and gives a named assertion failure when it is not. Nothing here waits more than a
 * couple of seconds in the failure case, and every test that uses it configures intervals in tens
 * of milliseconds so the success case is immediate.
 */
public final class Await {

    /** Long enough to absorb a stalled CI runner, short enough that a hang is still a fast failure. */
    public static final long DEFAULT_TIMEOUT_MS = 5_000L;

    private static final long POLL_INTERVAL_MS = 5L;

    private Await() {
    }

    /** Waits for {@code condition}, failing the test with {@code description} if it never holds. */
    public static void until(String description, BooleanSupplier condition) {
        until(description, condition, DEFAULT_TIMEOUT_MS);
    }

    /** Waits for {@code condition} up to {@code timeoutMs}. */
    public static void until(String description, BooleanSupplier condition, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            pause();
        }
        if (!condition.getAsBoolean()) {
            fail("timed out after " + timeoutMs + "ms waiting for: " + description);
        }
    }

    /** Waits for {@code supplier} to return non-null, and returns it. */
    public static <T> T value(String description, Supplier<T> supplier) {
        long deadline = System.currentTimeMillis() + DEFAULT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            T value = supplier.get();
            if (value != null) {
                return value;
            }
            pause();
        }
        T last = supplier.get();
        if (last == null) {
            fail("timed out after " + DEFAULT_TIMEOUT_MS + "ms waiting for: " + description);
        }
        return last;
    }

    private static void pause() {
        try {
            Thread.sleep(POLL_INTERVAL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("interrupted while waiting");
        }
    }
}
