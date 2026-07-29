package com.heimdall.module.whitelist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.log.LogLevel;
import com.heimdall.core.log.RecordingLogger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The debounce, on its own, with a window short enough to assert against.
 *
 * <p>The module-level wiring — that the frame is subscribed while enabled and gone while disabled —
 * is {@link WhitelistNudgeTest}'s. What is here is the property that class cannot see: a burst of
 * fifty notifications from a bulk import has to become one sync, and the sync has to happen
 * somewhere other than the thread the notification arrived on.
 */
class WhitelistChangeNudgeTest {

    /** Short enough that a test is not a wait, long enough that a burst genuinely overlaps it. */
    private static final long DEBOUNCE_MS = 60L;

    private final RecordingLogger logger = new RecordingLogger(true);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            runnable -> {
                Thread thread = new Thread(runnable, "pretend-heimdall-sched");
                thread.setDaemon(true);
                return thread;
            });

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    private WhitelistChangeNudge nudge(Runnable sync) {
        return new WhitelistChangeNudge(logger, scheduler, sync, DEBOUNCE_MS);
    }

    @Test
    @DisplayName("one notification runs one sync")
    void oneNudgeRunsTheSync() throws Exception {
        CountDownLatch ran = new CountDownLatch(1);
        WhitelistChangeNudge nudge = nudge(ran::countDown);

        nudge.nudge();

        assertTrue(ran.await(5, TimeUnit.SECONDS),
                "without this the whole feature is a no-op and a revoked player keeps playing until "
                        + "the next pre-warm poll, up to five minutes later");
    }

    @Test
    @DisplayName("a burst collapses to a single sync")
    void aBurstIsDebounced() throws Exception {
        AtomicInteger syncs = new AtomicInteger();
        CountDownLatch ran = new CountDownLatch(1);
        WhitelistChangeNudge nudge = nudge(() -> {
            syncs.incrementAndGet();
            ran.countDown();
        });

        // What a bulk import looks like: one notification per row, all inside the window.
        for (int i = 0; i < 50; i++) {
            nudge.nudge();
        }

        assertTrue(ran.await(5, TimeUnit.SECONDS));
        // Long enough that a second scheduled sync would have fired by now if one had been armed.
        Thread.sleep(DEBOUNCE_MS * 5);
        assertEquals(1, syncs.get(),
                "fifty full whitelist syncs back to back on the single heimdall-sched thread is "
                        + "what the debounce exists to prevent — the poll and the expiry sweep run "
                        + "there too");
    }

    @Test
    @DisplayName("a change arriving during a sync arms a fresh one rather than being swallowed")
    void aNudgeDuringASyncIsNotLost() throws Exception {
        AtomicInteger syncs = new AtomicInteger();
        CountDownLatch second = new CountDownLatch(2);
        AtomicReference<WhitelistChangeNudge> self = new AtomicReference<WhitelistChangeNudge>();
        WhitelistChangeNudge nudge = nudge(() -> {
            if (syncs.incrementAndGet() == 1) {
                // The race the disarm-first ordering exists for: a revocation landing while the
                // sync that would have carried it is already in flight. Absorbing it into the
                // running sync loses exactly the change this mechanism is meant to deliver.
                self.get().nudge();
            }
            second.countDown();
        });
        self.set(nudge);

        nudge.nudge();

        assertTrue(second.await(5, TimeUnit.SECONDS),
                "a change that lands mid-sync has to arm a new one");
        assertEquals(2, syncs.get());
    }

    @Test
    @DisplayName("the sync runs on the scheduler, never on the thread that delivered the frame")
    void theSyncIsNotRunOnTheDeliveringThread() throws Exception {
        AtomicReference<String> ranOn = new AtomicReference<String>();
        CountDownLatch ran = new CountDownLatch(1);
        WhitelistChangeNudge nudge = nudge(() -> {
            ranOn.set(Thread.currentThread().getName());
            ran.countDown();
        });

        Thread deliverer = new Thread(nudge::nudge, "pretend-heimdall-io");
        deliverer.start();
        deliverer.join(5000L);

        assertTrue(ran.await(5, TimeUnit.SECONDS));
        assertEquals("pretend-heimdall-sched", ranOn.get());
        assertNotEquals("pretend-heimdall-io", ranOn.get(),
                "syncNow blocks on an HTTP call; running it on the tunnel's IO pool — let alone the "
                        + "socket's reading thread — is how a healthy link gets reaped for not "
                        + "reading");
    }

    @Test
    @DisplayName("closing cancels a sync that has not fired yet")
    void closingCancelsAPendingSync() throws Exception {
        AtomicInteger syncs = new AtomicInteger();
        WhitelistChangeNudge nudge = nudge(syncs::incrementAndGet);

        nudge.nudge();
        assertTrue(nudge.isArmed());
        nudge.close();

        Thread.sleep(DEBOUNCE_MS * 5);
        assertEquals(0, syncs.get(),
                "a pending sync firing after disable() would reach a mirror the module has already "
                        + "let go of");
        assertFalse(nudge.isArmed());
    }

    @Test
    @DisplayName("a nudge after close does nothing, and closing twice is fine")
    void aClosedNudgeIsInert() throws Exception {
        AtomicInteger syncs = new AtomicInteger();
        WhitelistChangeNudge nudge = nudge(syncs::incrementAndGet);

        nudge.close();
        nudge.close();
        nudge.nudge();

        Thread.sleep(DEBOUNCE_MS * 5);
        assertEquals(0, syncs.get());
    }

    @Test
    @DisplayName("a sync that throws is reported and leaves the debouncer usable")
    void aThrowingSyncDoesNotWedgeTheDebouncer() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch twice = new CountDownLatch(2);
        WhitelistChangeNudge nudge = nudge(() -> {
            attempts.incrementAndGet();
            twice.countDown();
            throw new IllegalStateException("the mirror is gone");
        });

        nudge.nudge();
        Thread.sleep(DEBOUNCE_MS * 3);
        nudge.nudge();

        assertTrue(twice.await(5, TimeUnit.SECONDS),
                "a one-shot that throws cancels nothing, but a debouncer left armed would swallow "
                        + "every later notification");
        assertTrue(logger.logged(LogLevel.WARN, "whitelist-change sync failed"),
                "nothing else is watching this task: " + logger.records());
    }
}
