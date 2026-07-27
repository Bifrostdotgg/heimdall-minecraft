package com.heimdall.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The undo handle: runs once, whoever closes it and however often. */
class RegistrationTest {

    @Test
    @DisplayName("closing twice undoes once")
    void closeIsIdempotent() {
        AtomicInteger undone = new AtomicInteger();
        Registration registration = Registration.once(undone::incrementAndGet);

        registration.close();
        registration.close();
        registration.close();

        assertEquals(1, undone.get(),
                "a module closes its own handle and ModuleManager closes it again on teardown; the "
                        + "second close must not be an error during teardown, where it is least useful");
    }

    @Test
    @DisplayName("two threads racing to close still undo exactly once")
    void closeIsThreadSafe() throws Exception {
        AtomicInteger undone = new AtomicInteger();
        Registration registration = Registration.once(undone::incrementAndGet);

        int racers = 16;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(racers);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            for (int i = 0; i < racers; i++) {
                pool.execute(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    registration.close();
                    done.countDown();
                });
            }
            start.countDown();
            done.await(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, undone.get());
    }

    @Test
    void noneDoesNothingAndDoesNotThrow() {
        Registration.NONE.close();
        Registration.NONE.close();
    }

    @Test
    void anUndoActionIsRequired() {
        assertThrows(IllegalArgumentException.class, () -> Registration.once(null));
    }

    @Test
    @DisplayName("usable in try-with-resources without a checked-exception catch")
    void worksInTryWithResources() {
        AtomicInteger undone = new AtomicInteger();
        try (Registration registration = Registration.once(undone::incrementAndGet)) {
            assertEquals(0, undone.get());
        }
        assertEquals(1, undone.get());
    }
}
