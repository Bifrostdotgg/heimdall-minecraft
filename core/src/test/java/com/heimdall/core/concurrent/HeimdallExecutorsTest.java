package com.heimdall.core.concurrent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.log.LogLevel;
import com.heimdall.core.log.RecordingLogger;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The owned pools: named, daemon, bounded, and shut down in the right order. */
class HeimdallExecutorsTest {

    private final RecordingLogger logger = new RecordingLogger();

    @Test
    @DisplayName("IO threads are named heimdall-io-N and are daemons")
    void ioThreadsAreNamedAndDaemon() throws Exception {
        try (HeimdallExecutors executors = new HeimdallExecutors(logger, 2)) {
            Set<String> names = new TreeSet<String>();
            CountDownLatch started = new CountDownLatch(2);
            CountDownLatch release = new CountDownLatch(1);
            AtomicReference<Boolean> daemon = new AtomicReference<Boolean>();

            for (int i = 0; i < 2; i++) {
                executors.io().execute(() -> {
                    synchronized (names) {
                        names.add(Thread.currentThread().getName());
                    }
                    daemon.set(Thread.currentThread().isDaemon());
                    started.countDown();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }

            assertTrue(started.await(5, TimeUnit.SECONDS), "both IO threads should have started");
            release.countDown();

            assertEquals(2, names.size(), "the pool should really have two threads: " + names);
            for (String name : names) {
                assertTrue(name.startsWith("heimdall-io-"), "unhelpful thread name: " + name);
            }
            assertTrue(daemon.get().booleanValue(), "a stuck Heimdall thread must not hold the JVM open");
        }
    }

    @Test
    @DisplayName("the scheduler is one thread called heimdall-sched")
    void schedulerIsNamedAndSingleThreaded() throws Exception {
        try (HeimdallExecutors executors = new HeimdallExecutors(logger)) {
            AtomicReference<String> name = new AtomicReference<String>();
            CountDownLatch done = new CountDownLatch(1);

            executors.scheduler().schedule(() -> {
                name.set(Thread.currentThread().getName());
                done.countDown();
            }, 1, TimeUnit.MILLISECONDS);

            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertTrue(name.get().startsWith("heimdall-sched"),
                    "the prefix is the contract; a pool suffix is not: " + name.get());
        }
    }

    @Test
    @DisplayName("the tunnel scheduler is its own thread called heimdall-ws")
    void wsSchedulerIsSeparateAndNamed() throws Exception {
        try (HeimdallExecutors executors = new HeimdallExecutors(logger)) {
            AtomicReference<String> name = new AtomicReference<String>();
            CountDownLatch done = new CountDownLatch(1);

            executors.ws().schedule(() -> {
                name.set(Thread.currentThread().getName());
                done.countDown();
            }, 1, TimeUnit.MILLISECONDS);

            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertTrue(name.get().startsWith("heimdall-ws"), "unhelpful thread name: " + name.get());
            assertNotSame(executors.scheduler(), executors.ws(),
                    "sharing one thread would put the heartbeat behind a slow whitelist poll");
        }
    }

    @Test
    void ioPoolSizeIsClampedToAtLeastOne() throws Exception {
        try (HeimdallExecutors executors = new HeimdallExecutors(logger, 0)) {
            CountDownLatch done = new CountDownLatch(1);
            executors.io().execute(done::countDown);
            assertTrue(done.await(5, TimeUnit.SECONDS), "a zero-sized pool would never run anything");
        }
    }

    @Test
    @DisplayName("shutdown stops both pools and is idempotent")
    void shutdownStopsBothPools() {
        HeimdallExecutors executors = new HeimdallExecutors(logger, 1);
        assertFalse(executors.isShutdown());

        executors.shutdown();
        executors.shutdown();

        assertTrue(executors.isShutdown());
        assertTrue(executors.io().isShutdown());
        assertTrue(executors.scheduler().isShutdown());
        assertTrue(executors.ws().isShutdown());
        assertTrue(logger.at(LogLevel.SEVERE).isEmpty(), "a clean shutdown should say nothing");
    }

    @Test
    @DisplayName("a task that outlasts the grace period is interrupted and logged")
    void stragglersAreReportedAndInterrupted() throws Exception {
        HeimdallExecutors executors = new HeimdallExecutors(logger, 1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);

        executors.io().execute(() -> {
            started.countDown();
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException e) {
                interrupted.countDown();
            }
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));

        executors.shutdown(50);

        assertTrue(interrupted.await(5, TimeUnit.SECONDS), "the straggler should have been interrupted");
        assertTrue(logger.logged(LogLevel.SEVERE, "heimdall-io"),
                "a task holding a socket past shutdown deserves a line naming its pool");
    }
}
