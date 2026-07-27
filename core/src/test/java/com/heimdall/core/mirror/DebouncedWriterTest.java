package com.heimdall.core.mirror;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.log.LogLevel;
import com.heimdall.core.log.RecordingLogger;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Coalescing, flushing and failure handling for the debounced save.
 *
 * <p>Driven by {@link ManualScheduler}, so "the debounce window elapsed" is a method call rather
 * than a sleep. The earlier version of this suite scheduled real 50–150 ms windows and slept past
 * them, which was slow, flaked under load, and made a real regression indistinguishable from a
 * timing blip.
 */
class DebouncedWriterTest {

    private final RecordingLogger logger = new RecordingLogger(true);
    private final ManualScheduler scheduler = new ManualScheduler();

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    @Test
    @DisplayName("a burst of mutations produces one write")
    void burstCoalescesIntoOneWrite() {
        AtomicInteger writes = new AtomicInteger();
        DebouncedWriter writer =
                new DebouncedWriter(logger, scheduler, 120, "test", writes::incrementAndGet);

        for (int i = 0; i < 50; i++) {
            writer.markDirty();
        }

        assertEquals(0, writes.get(), "nothing should have hit the disk yet");
        assertEquals(1, scheduler.pendingCount(),
                "fifty mutations must schedule one write, not fifty");

        scheduler.runPending();

        assertEquals(1, writes.get());
        assertEquals(0, scheduler.pendingCount());
    }

    @Test
    @DisplayName("a mutation after a write schedules the next one")
    void laterMutationSchedulesAnotherWrite() {
        AtomicInteger writes = new AtomicInteger();
        DebouncedWriter writer =
                new DebouncedWriter(logger, scheduler, 50, "test", writes::incrementAndGet);

        writer.markDirty();
        scheduler.runPending();
        assertEquals(1, writes.get());

        writer.markDirty();
        assertEquals(1, scheduler.pendingCount(), "the writer must not latch after its first write");
        scheduler.runPending();
        assertEquals(2, writes.get());
    }

    @Test
    @DisplayName("a mutation arriving during the write is not swallowed by it")
    void mutationDuringAWriteSchedulesAnother() {
        AtomicInteger writes = new AtomicInteger();
        final DebouncedWriter[] holder = new DebouncedWriter[1];
        holder[0] = new DebouncedWriter(logger, scheduler, 50, "test", () -> {
            // Something mutates while the save is in flight — a join landing mid-flush.
            if (writes.incrementAndGet() == 1) {
                holder[0].markDirty();
            }
        });

        holder[0].markDirty();
        scheduler.runPending();

        assertEquals(1, scheduler.pendingCount(),
                "the change made during the write has to survive it");
        scheduler.runPending();
        assertEquals(2, writes.get());
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
    @DisplayName("a failed write is logged with its cause and retried on the next flush")
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
        assertEquals(1, logger.at(LogLevel.SEVERE).size(), "the operator has to hear about it");
        assertTrue(logger.at(LogLevel.SEVERE).get(0).throwable instanceof IllegalStateException,
                "and the cause has to survive rather than be flattened into the message");

        writer.flush();
        assertEquals(2, attempts.get(), "the change must not be silently dropped");
    }

    @Test
    @DisplayName("a dead scheduler falls back to writing inline rather than losing the change")
    void rejectedScheduleWritesInline() {
        AtomicInteger writes = new AtomicInteger();
        ScheduledExecutorService dead = Executors.newSingleThreadScheduledExecutor();
        dead.shutdownNow();
        DebouncedWriter writer =
                new DebouncedWriter(logger, dead, 60_000, "test", writes::incrementAndGet);

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
