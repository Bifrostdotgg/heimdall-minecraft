package com.heimdall.core.mirror;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.log.LogLevel;
import com.heimdall.core.log.RecordingLogger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Coalescing, flushing and failure handling for the debounced save. */
class DebouncedWriterTest {

    private final RecordingLogger logger = new RecordingLogger(true);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    @Test
    @DisplayName("a burst of mutations produces one write")
    void burstCoalescesIntoOneWrite() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        CountDownLatch written = new CountDownLatch(1);
        DebouncedWriter writer = new DebouncedWriter(logger, scheduler, 120, "test", () -> {
            writes.incrementAndGet();
            written.countDown();
        });

        for (int i = 0; i < 50; i++) {
            writer.markDirty();
        }
        assertEquals(0, writes.get(), "nothing should have hit the disk yet");

        assertTrue(written.await(5, TimeUnit.SECONDS));
        // Give any second scheduled write a chance to appear before asserting there wasn't one.
        Thread.sleep(250);
        assertEquals(1, writes.get(), "fifty mutations, one write — this is the point of the class");
    }

    @Test
    @DisplayName("a mutation after a write schedules the next one")
    void laterMutationSchedulesAnotherWrite() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        DebouncedWriter writer =
                new DebouncedWriter(logger, scheduler, 50, "test", writes::incrementAndGet);

        writer.markDirty();
        Thread.sleep(300);
        assertEquals(1, writes.get());

        writer.markDirty();
        Thread.sleep(300);
        assertEquals(2, writes.get(), "the writer must not latch after its first write");
    }

    @Test
    @DisplayName("a zero debounce writes inline")
    void zeroDebounceWritesInline() {
        AtomicInteger writes = new AtomicInteger();
        DebouncedWriter writer = new DebouncedWriter(logger, null, 0, "test", writes::incrementAndGet);

        writer.markDirty();
        writer.markDirty();

        assertEquals(2, writes.get());
    }

    @Test
    @DisplayName("close() flushes pending work")
    void closeFlushes() {
        AtomicInteger writes = new AtomicInteger();
        DebouncedWriter writer =
                new DebouncedWriter(logger, scheduler, 60_000, "test", writes::incrementAndGet);

        writer.markDirty();
        assertEquals(0, writes.get(), "a minute-long debounce should not have fired");

        writer.close();

        assertEquals(1, writes.get(), "shutdown must not lose the pending change");
    }

    @Test
    @DisplayName("flush() with nothing pending does not write")
    void flushIsANoOpWhenClean() {
        AtomicInteger writes = new AtomicInteger();
        DebouncedWriter writer =
                new DebouncedWriter(logger, scheduler, 60_000, "test", writes::incrementAndGet);

        writer.flush();
        writer.flush();

        assertEquals(0, writes.get());
    }

    @Test
    @DisplayName("a failed write is logged and retried on the next flush")
    void failedWriteIsRetried() {
        AtomicInteger attempts = new AtomicInteger();
        DebouncedWriter writer = new DebouncedWriter(logger, scheduler, 60_000, "test", () -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("disk full");
            }
        });

        writer.markDirty();
        writer.flush();
        assertEquals(1, attempts.get());
        assertTrue(logger.logged(LogLevel.SEVERE, "Could not save test"));

        writer.flush();
        assertEquals(2, attempts.get(), "the change must not be silently dropped");
    }

    @Test
    @DisplayName("a dead scheduler falls back to writing inline rather than losing the change")
    void rejectedScheduleWritesInline() {
        AtomicInteger writes = new AtomicInteger();
        ScheduledExecutorService dead = Executors.newSingleThreadScheduledExecutor();
        dead.shutdownNow();
        DebouncedWriter writer = new DebouncedWriter(logger, dead, 60_000, "test", writes::incrementAndGet);

        writer.markDirty();

        assertEquals(1, writes.get());
    }

    @Test
    void countsItsWrites() {
        DebouncedWriter writer = new DebouncedWriter(logger, null, 0, "test", () -> { });

        assertEquals(0L, writer.writeCount());
        writer.markDirty();
        assertEquals(1L, writer.writeCount());
    }
}
