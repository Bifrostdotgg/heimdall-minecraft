package com.heimdall.core.concurrent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Per-key collapse.
 *
 * <p>The last test is the important one: this must never behave like v2's 30-second response cache,
 * which replayed a stale {@code roleSync} block to a later join and reverted the player's groups.
 */
class InFlightTest {

    @Test
    @DisplayName("a second caller for the same key joins the outstanding operation")
    void secondCallerJoinsTheFirst() throws Exception {
        InFlight<String, String> inFlight = new InFlight<String, String>();
        AtomicInteger starts = new AtomicInteger();
        CompletableFuture<String> operation = new CompletableFuture<String>();

        CompletableFuture<String> first = inFlight.submit("steve", () -> {
            starts.incrementAndGet();
            return operation;
        });
        CompletableFuture<String> second = inFlight.submit("steve", () -> {
            starts.incrementAndGet();
            return CompletableFuture.completedFuture("should never be started");
        });

        assertSame(first, second, "the second caller should get the outstanding future itself");
        assertEquals(1, starts.get(), "the operation should have been started exactly once");
        assertEquals(1, inFlight.size());
        assertTrue(inFlight.isInFlight("steve"));

        operation.complete("allowed");
        assertEquals("allowed", first.get(5, TimeUnit.SECONDS));
        assertEquals("allowed", second.get(5, TimeUnit.SECONDS));
    }

    @Test
    void differentKeysDoNotCollapse() {
        InFlight<String, String> inFlight = new InFlight<String, String>();
        AtomicInteger starts = new AtomicInteger();

        inFlight.submit("steve", () -> {
            starts.incrementAndGet();
            return new CompletableFuture<String>();
        });
        inFlight.submit("alex", () -> {
            starts.incrementAndGet();
            return new CompletableFuture<String>();
        });

        assertEquals(2, starts.get());
        assertEquals(2, inFlight.size());
    }

    @Test
    @DisplayName("the entry is released on completion, so a later caller gets a fresh request")
    void entryIsReleasedOnCompletion() {
        InFlight<String, String> inFlight = new InFlight<String, String>();
        AtomicInteger starts = new AtomicInteger();

        CompletableFuture<String> operation = new CompletableFuture<String>();
        inFlight.submit("steve", () -> {
            starts.incrementAndGet();
            return operation;
        });
        operation.complete("allowed");

        assertEquals(0, inFlight.size());
        assertFalse(inFlight.isInFlight("steve"));

        inFlight.submit("steve", () -> {
            starts.incrementAndGet();
            return CompletableFuture.completedFuture("fresh");
        });
        assertEquals(2, starts.get(),
                "nothing is retained after completion — this is a collapse, not a cache");
    }

    @Test
    void failuresPropagateToEveryJoinerAndReleaseTheEntry() {
        InFlight<String, String> inFlight = new InFlight<String, String>();
        CompletableFuture<String> operation = new CompletableFuture<String>();

        CompletableFuture<String> first = inFlight.submit("steve", () -> operation);
        CompletableFuture<String> second = inFlight.submit("steve", () -> operation);

        operation.completeExceptionally(new IllegalStateException("bot unreachable"));

        ExecutionException failure = assertThrows(ExecutionException.class, () -> first.get(5, TimeUnit.SECONDS));
        assertTrue(failure.getCause() instanceof IllegalStateException);
        assertThrows(ExecutionException.class, () -> second.get(5, TimeUnit.SECONDS));
        assertEquals(0, inFlight.size(), "a failure must not wedge the key permanently");
    }

    @Test
    @DisplayName("a start that throws fails the caller rather than wedging the key")
    void throwingStartReleasesTheEntry() {
        InFlight<String, String> inFlight = new InFlight<String, String>();

        CompletableFuture<String> future =
                inFlight.submit("steve", () -> {
                    throw new IllegalStateException("could not dispatch");
                });

        assertTrue(future.isCompletedExceptionally());
        assertEquals(0, inFlight.size());
        assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("under contention the operation still starts exactly once")
    void collapsesUnderContention() throws Exception {
        InFlight<String, String> inFlight = new InFlight<String, String>();
        AtomicInteger starts = new AtomicInteger();
        CompletableFuture<String> operation = new CompletableFuture<String>();

        int callers = 32;
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(callers);
        // One thread per caller: they all have to be parked on `go` simultaneously for the release
        // to be a genuine race, which a smaller pool would turn into a deadlock instead.
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            for (int i = 0; i < callers; i++) {
                pool.execute(() -> {
                    ready.countDown();
                    try {
                        go.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    inFlight.submit("steve", () -> {
                        starts.incrementAndGet();
                        return operation;
                    });
                    done.countDown();
                });
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, starts.get(), "32 concurrent callers, one request");
        operation.complete("allowed");
        assertEquals(0, inFlight.size());
    }

    @Test
    void nullArgumentsAreRejected() {
        InFlight<String, String> inFlight = new InFlight<String, String>();

        assertThrows(IllegalArgumentException.class,
                () -> inFlight.submit(null, () -> CompletableFuture.completedFuture("x")));
        assertThrows(IllegalArgumentException.class, () -> inFlight.submit("steve", null));
    }
}
