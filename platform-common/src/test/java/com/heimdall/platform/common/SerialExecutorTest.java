package com.heimdall.platform.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.log.RecordingLogger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The queue under the LuckPerms group reads: in order, one at a time, and sane when the pool behind
 * it is going away.
 */
class SerialExecutorTest {

    private final RecordingLogger logger = new RecordingLogger(true);

    @Test
    @DisplayName("tasks run in submission order and never two at once, even on a multi-thread pool")
    void ordersAndSerialises() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            SerialExecutor serial = new SerialExecutor(pool, logger);
            final List<Integer> ran = Collections.synchronizedList(new ArrayList<Integer>());
            final AtomicInteger running = new AtomicInteger();
            final AtomicInteger overlap = new AtomicInteger();
            final CountDownLatch done = new CountDownLatch(200);
            for (int i = 0; i < 200; i++) {
                final int n = i;
                serial.execute(() -> {
                    if (running.incrementAndGet() > 1) {
                        overlap.incrementAndGet();
                    }
                    ran.add(n);
                    running.decrementAndGet();
                    done.countDown();
                });
            }
            assertTrue(done.await(10, TimeUnit.SECONDS));
            assertEquals(0, overlap.get(), "two tasks ran at once");
            for (int i = 0; i < 200; i++) {
                assertEquals(Integer.valueOf(i), ran.get(i), "out of order at " + i);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("a task that throws is logged and the queue keeps draining")
    void survivesAThrowingTask() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            SerialExecutor serial = new SerialExecutor(pool, logger);
            CountDownLatch after = new CountDownLatch(1);
            serial.execute(() -> {
                throw new IllegalStateException("boom");
            });
            serial.execute(after::countDown);
            assertTrue(after.await(5, TimeUnit.SECONDS), "the task after the failure never ran");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("a rejected hand-off rethrows, drops the queue, and does not leave it stuck draining")
    void rejectionIsReportedAndRecovers() throws Exception {
        ExecutorService dead = Executors.newSingleThreadExecutor();
        dead.shutdown();
        final boolean[] rejecting = {true};
        final ExecutorService live = Executors.newSingleThreadExecutor();
        try {
            SerialExecutor serial = new SerialExecutor(task -> {
                if (rejecting[0]) {
                    dead.execute(task);
                } else {
                    live.execute(task);
                }
            }, logger);

            AtomicInteger ran = new AtomicInteger();
            assertThrows(RejectedExecutionException.class, () -> serial.execute(ran::incrementAndGet));

            // If the failed hand-off had left the queue marked as draining, this task would be queued
            // behind a drain that never runs, and never execute.
            rejecting[0] = false;
            CountDownLatch done = new CountDownLatch(1);
            serial.execute(done::countDown);
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertEquals(0, ran.get(), "the rejected task was dropped, not run later");
        } finally {
            live.shutdownNow();
        }
    }
}
