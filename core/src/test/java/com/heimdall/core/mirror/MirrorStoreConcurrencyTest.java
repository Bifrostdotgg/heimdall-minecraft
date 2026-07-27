package com.heimdall.core.mirror;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.log.RecordingLogger;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The mirror under concurrent mutation.
 *
 * <p>The login thread extends entries while the scheduler reconciles them, which on the previous
 * mutable-entry design was an unguarded read-modify-write across three fields. The invariant
 * asserted here — no entry may ever hold an expiry beyond its own ceiling — is precisely what a
 * lost or interleaved update produces, and it is checked on the raw entries rather than through
 * {@link MirrorStore#get}, which would hide a bad expiry behind the read-side clamp.
 */
class MirrorStoreConcurrencyTest {

    private static final long WINDOW_MS = TimeUnit.MINUTES.toMillis(60);
    private static final long CEILING_MS = TimeUnit.HOURS.toMillis(2);
    private static final int KEYS = 16;

    private final RecordingLogger logger = new RecordingLogger();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    private static String key(int index) {
        return "uuid-" + index;
    }

    @Test
    @DisplayName("concurrent extend, reconcile and read never produce an expiry past the ceiling")
    void mutationsDoNotInterleave(@TempDir Path dir) throws Exception {
        MirrorPolicy policy = MirrorPolicy.builder()
                .windowMs(WINDOW_MS)
                .maxExtensionMs(CEILING_MS)
                // Real time here on purpose: this is about the store's own locking, and freezing
                // the clock would remove the interleaving the test exists to provoke.
                .saveDebounceMs(50)
                .build();
        MirrorStore<String> store = MirrorStore.builder(logger, dir.resolve("mirror.json"), String.class)
                .policy(policy)
                .scheduler(scheduler)
                .open();

        Map<String, String> authoritative = new LinkedHashMap<String, String>();
        for (int i = 0; i < KEYS; i++) {
            authoritative.put(key(i), "Player" + i);
        }
        store.reconcile(authoritative);

        int threads = 8;
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int t = 0; t < threads; t++) {
                final int worker = t;
                pool.execute(() -> {
                    try {
                        go.await(5, TimeUnit.SECONDS);
                        for (int round = 0; round < 400; round++) {
                            String k = key((worker * 7 + round) % KEYS);
                            switch (worker % 4) {
                                case 0:
                                    store.extendOnEvent(k, TimeUnit.MINUTES.toMillis(180));
                                    break;
                                case 1:
                                    store.reconcile(authoritative);
                                    break;
                                case 2:
                                    store.touchValue(k, "Renamed" + round);
                                    break;
                                default:
                                    store.get(k);
                                    break;
                            }
                            assertCeilingHolds(store);
                        }
                    } catch (Throwable e) {
                        failure.compareAndSet(null, e);
                    } finally {
                        done.countDown();
                    }
                });
            }
            go.countDown();
            assertTrue(done.await(60, TimeUnit.SECONDS), "workers did not finish");
        } finally {
            pool.shutdownNow();
        }

        if (failure.get() != null) {
            throw new AssertionError("a worker failed", failure.get());
        }

        assertCeilingHolds(store);
        for (int i = 0; i < KEYS; i++) {
            assertNotNull(store.rawEntry(key(i)), "reconcile keeps every authoritative key present");
        }
        store.close();
    }

    /**
     * Every persisted expiry must sit inside its entry's own ceiling.
     *
     * <p>A lost update — an extension computed against one {@code lastVerified} and written after a
     * reconcile moved it, or vice versa — is exactly what breaks this, and it is the failure the
     * read-side clamp would otherwise mask.
     */
    private static void assertCeilingHolds(MirrorStore<String> store) {
        for (String key : store.keys()) {
            MirrorEntry<String> entry = store.rawEntry(key);
            if (entry == null) {
                continue;
            }
            long ceiling = entry.lastVerified() + CEILING_MS;
            assertTrue(entry.cacheExpiry() <= ceiling,
                    key + " holds expiry " + entry.cacheExpiry() + " past its ceiling " + ceiling
                            + " — an extension and a reconcile interleaved");
        }
    }
}
