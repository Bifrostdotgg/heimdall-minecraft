package com.heimdall.core.mirror;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.log.RecordingLogger;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The mirror engine.
 *
 * <p>{@code TheCeiling} and {@code Reconcile} are v2's ten {@code WhitelistCacheTest} cases,
 * scenario for scenario, against the generalised API — the revocation bound (issue #771) is the
 * reason this class exists and losing it would be losing the point. {@code Persistence} covers what
 * v2 got wrong: whole-file synchronous saves from the login thread, and a plain {@code FileWriter}
 * that a crash could truncate.
 */
class MirrorStoreTest {

    private static final String UUID = "11111111-2222-3333-4444-555555555555";
    private static final String UUID2 = "99999999-8888-7777-6666-555555555555";
    private static final String USER = "Steve";

    private static final long ONE_HOUR = TimeUnit.HOURS.toMillis(1);

    private final RecordingLogger logger = new RecordingLogger(true);
    private final MutableClock clock = new MutableClock();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    /** cacheWindow=60m, maxExtension=Nh, writes go straight to disk so assertions can read them. */
    private MirrorStore<String> open(Path dir, long maxExtensionHours) {
        return open(dir, maxExtensionHours, 0);
    }

    private MirrorStore<String> open(Path dir, long maxExtensionHours, long debounceMs) {
        MirrorPolicy policy = MirrorPolicy.builder()
                .windowMinutes(60)
                .maxExtensionHours(maxExtensionHours)
                .saveDebounceMs(debounceMs)
                .build();
        return MirrorStore.open(logger, file(dir), String.class, policy, scheduler, clock);
    }

    private static Path file(Path dir) {
        return dir.resolve("whitelist-mirror.json");
    }

    private static void writeRaw(Path dir, String json) throws IOException {
        Files.write(file(dir), json.getBytes(StandardCharsets.UTF_8));
    }

    private static String readRaw(Path dir) throws IOException {
        return new String(Files.readAllBytes(file(dir)), StandardCharsets.UTF_8);
    }

    private static Map<String, String> authoritative(String... uuids) {
        Map<String, String> map = new LinkedHashMap<String, String>();
        for (String uuid : uuids) {
            map.put(uuid, USER);
        }
        return map;
    }

    // ── v2 WhitelistCacheTest, cases 1-6 ─────────────────────────────────────

    @Nested
    @DisplayName("the max-extension ceiling (v2 issue #771)")
    class TheCeiling {

        @Test
        void freshVerificationIsServedWithinWindow(@TempDir Path dir) {
            MirrorStore<String> store = open(dir, 24);

            store.record(UUID, USER);

            assertEquals(USER, store.get(UUID));
            assertTrue(store.isPresent(UUID));
        }

        @Test
        @DisplayName("activity slides the expiry but never past lastVerified + maxExtension")
        void extensionsNeverSlideBeyondTheCeiling(@TempDir Path dir) {
            // A one-hour ceiling against a three-hour leave extension: the bound must clamp it.
            MirrorStore<String> store = open(dir, 1);
            store.record(UUID, USER);
            long verifiedAt = clock.now();

            store.extendOnEvent(UUID, TimeUnit.MINUTES.toMillis(120));
            store.extendOnEvent(UUID, TimeUnit.MINUTES.toMillis(180));

            clock.advance(ONE_HOUR + 1);
            assertNull(store.get(UUID),
                    "two extensions totalling five hours must not outlive a one-hour ceiling "
                            + "measured from " + verifiedAt);
        }

        @Test
        @DisplayName("a zero ceiling restores v2's unbounded slide")
        void zeroDisablesTheBound(@TempDir Path dir) {
            MirrorStore<String> store = open(dir, 0);
            store.record(UUID, USER);

            store.extendOnEvent(UUID, TimeUnit.MINUTES.toMillis(180));

            clock.advanceHours(2);
            assertEquals(USER, store.get(UUID), "with the bound off the full 180m slide applies");
            clock.advanceHours(2);
            assertNull(store.get(UUID), "unbounded still means the slide itself eventually expires");
        }

        @Test
        @DisplayName("an old verification with a future expiry is still refused")
        void staleVerificationPastCeilingForcesReverify(@TempDir Path dir) throws IOException {
            // The core revocation scenario: cacheExpiry says "valid for another 24 hours",
            // lastVerified says "the bot last confirmed this 48 hours ago".
            long now = clock.now();
            writeRaw(dir, "{\"entries\":{\"" + UUID + "\":{\"value\":\"" + USER
                    + "\",\"lastConnection\":" + now
                    + ",\"cacheExpiry\":" + (now + TimeUnit.HOURS.toMillis(24))
                    + ",\"lastVerified\":" + (now - TimeUnit.HOURS.toMillis(48)) + "}}}");

            MirrorStore<String> store = open(dir, 6);

            assertNull(store.get(UUID),
                    "past the ceiling must not be served, despite a cacheExpiry far in the future");
        }

        @Test
        @DisplayName("an entry written before lastVerified existed forces re-verification")
        void legacyEntryWithoutLastVerifiedForcesReverify(@TempDir Path dir) throws IOException {
            long now = clock.now();
            writeRaw(dir, "{\"entries\":{\"" + UUID + "\":{\"value\":\"" + USER
                    + "\",\"lastConnection\":" + now
                    + ",\"cacheExpiry\":" + (now + ONE_HOUR) + "}}}");

            MirrorStore<String> store = open(dir, 24);

            assertNull(store.get(UUID),
                    "lastVerified defaults to 0, whose ceiling is 1970 — that is the migration path");
        }

        @Test
        void reVerificationAdvancesTheCeiling(@TempDir Path dir) {
            MirrorStore<String> store = open(dir, 1);
            store.record(UUID, USER);

            clock.advanceMinutes(50);
            store.record(UUID, USER);

            clock.advanceMinutes(50);
            assertEquals(USER, store.get(UUID),
                    "100 minutes after the first verification, but only 50 after the second");
        }

        @Test
        @DisplayName("activity alone never creates an entry")
        void extendingAnUnknownKeyDoesNothing(@TempDir Path dir) {
            MirrorStore<String> store = open(dir, 24);

            assertFalse(store.extendOnEvent(UUID, ONE_HOUR));
            assertNull(store.get(UUID), "a join is not evidence that the bot would allow it");
        }

        @Test
        @DisplayName("an expired entry is evicted by the read, not merely ignored")
        void readEvictsExpiredEntries(@TempDir Path dir) {
            MirrorStore<String> store = open(dir, 24);
            store.record(UUID, USER);
            clock.advanceMinutes(61);

            assertNull(store.get(UUID));
            assertEquals(0, store.size(),
                    "leaving it behind would let a later extendOnEvent resurrect a stale value");
        }

        @Test
        void sweepDropsExpiredEntriesNobodyAskedAbout(@TempDir Path dir) {
            MirrorStore<String> store = open(dir, 24);
            store.record(UUID, USER);
            store.record(UUID2, "Alex");
            clock.advanceMinutes(30);
            store.record(UUID2, "Alex");

            clock.advanceMinutes(31);

            assertEquals(1, store.sweepExpired());
            assertEquals(1, store.size());
            assertTrue(store.isPresent(UUID2));
        }
    }

    // ── v2 WhitelistCacheTest, cases 7-10 ────────────────────────────────────

    @Nested
    @DisplayName("reconcile against the authoritative set")
    class Reconcile {

        @Test
        void addsAnUnmirroredEntry(@TempDir Path dir) {
            MirrorStore<String> store = open(dir, 24);
            assertNull(store.get(UUID), "precondition: not mirrored");

            ReconcileResult result = store.reconcile(authoritative(UUID));

            assertEquals(1, result.added());
            assertEquals(0, result.pruned());
            assertEquals(USER, store.get(UUID),
                    "a pre-warmed player must be served locally, so a bot outage is invisible to them");
        }

        @Test
        @DisplayName("an entry absent from the set is pruned, propagating the revocation")
        void prunesARevokedEntry(@TempDir Path dir) {
            MirrorStore<String> store = open(dir, 24);
            store.record(UUID, USER);

            ReconcileResult result = store.reconcile(Collections.<String, String>emptyMap());

            assertEquals(1, result.pruned());
            assertNull(store.get(UUID),
                    "waiting for expiry would leave a revoked player with access for another hour");
        }

        @Test
        void refreshesAnEntryThatWasPastTheCeiling(@TempDir Path dir) throws IOException {
            long now = clock.now();
            writeRaw(dir, "{\"entries\":{\"" + UUID + "\":{\"value\":\"" + USER
                    + "\",\"lastConnection\":" + now
                    + ",\"cacheExpiry\":" + (now + ONE_HOUR)
                    + ",\"lastVerified\":" + (now - TimeUnit.HOURS.toMillis(48)) + "}}}");
            MirrorStore<String> store = open(dir, 6);
            assertNull(store.get(UUID), "precondition: stale, past the ceiling");

            store.reconcile(authoritative(UUID));

            assertEquals(USER, store.get(UUID),
                    "a sync is a real verification, so it advances lastVerified and restores service");
        }

        @Test
        void keepsListedAndPrunesUnlisted(@TempDir Path dir) {
            MirrorStore<String> store = open(dir, 24);
            store.record(UUID, USER);
            store.record(UUID2, "Alex");

            ReconcileResult result = store.reconcile(authoritative(UUID));

            assertEquals(1, result.updated());
            assertEquals(1, result.pruned());
            assertEquals(USER, store.get(UUID));
            assertNull(store.get(UUID2));
        }

        @Test
        @DisplayName("a sync never shrinks an expiry a recent event already slid forward")
        void neverShrinksAnExtendedExpiry(@TempDir Path dir) {
            MirrorStore<String> store = open(dir, 24);
            store.record(UUID, USER);
            store.extendOnEvent(UUID, TimeUnit.MINUTES.toMillis(180));

            store.reconcile(authoritative(UUID));

            clock.advanceMinutes(120);
            assertEquals(USER, store.get(UUID),
                    "the 60m sync window must not have overwritten the 180m extension");
        }

        @Test
        void blankKeysAndNullValuesAreSkipped(@TempDir Path dir) {
            MirrorStore<String> store = open(dir, 24);
            Map<String, String> incoming = new LinkedHashMap<String, String>();
            incoming.put(UUID, USER);
            incoming.put("  ", "blank key");
            incoming.put(UUID2, null);

            ReconcileResult result = store.reconcile(incoming);

            assertEquals(1, result.added());
            assertEquals(1, store.size());
        }

        @Test
        @DisplayName("null is refused, because it cannot be told apart from an empty whitelist")
        void nullSetIsRejected(@TempDir Path dir) {
            MirrorStore<String> store = open(dir, 24);

            assertThrows(IllegalArgumentException.class, () -> store.reconcile(null));
        }
    }

    // ── New in v3 ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("persistence")
    class Persistence {

        @Test
        void survivesAReopen(@TempDir Path dir) {
            MirrorStore<String> first = open(dir, 24);
            first.record(UUID, USER);
            first.setLastEtag("\"deadbeef\"");
            first.close();

            MirrorStore<String> second = open(dir, 24);

            assertEquals(USER, second.get(UUID));
            assertEquals("\"deadbeef\"", second.lastEtag());
        }

        @Test
        @DisplayName("the ETag rides along with the entries, so a restart resumes conditional polling")
        void etagIsPersistedAndCleared(@TempDir Path dir) {
            MirrorStore<String> store = open(dir, 24);
            assertNull(store.lastEtag());

            store.setLastEtag("\"abc\"");
            assertEquals("\"abc\"", store.lastEtag());

            store.clear();
            assertNull(store.lastEtag(), "an emptied mirror must force a full fetch, not a 304");
        }

        @Test
        @DisplayName("a burst of mutations is coalesced instead of rewriting the file each time")
        void savesAreDebounced(@TempDir Path dir) throws Exception {
            MirrorStore<String> store = open(dir, 24, 150);

            for (int i = 0; i < 25; i++) {
                store.record(UUID + i, USER);
            }
            assertEquals(0L, store.writeCount(),
                    "v2 rewrote the whole file 25 times here, several of them on the login thread");

            long deadline = System.currentTimeMillis() + 5000;
            while (store.writeCount() == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertEquals(1L, store.writeCount(), "twenty-five mutations, one write");
            assertTrue(Files.isRegularFile(file(dir)));
        }

        @Test
        @DisplayName("close() flushes a pending save")
        void closeFlushes(@TempDir Path dir) {
            MirrorStore<String> store = open(dir, 24, 60_000);
            store.record(UUID, USER);
            assertFalse(Files.exists(file(dir)), "a minute-long debounce should not have fired");

            store.close();

            assertTrue(Files.isRegularFile(file(dir)));
            assertEquals(USER, open(dir, 24).get(UUID), "shutdown must not lose the entry");
        }

        @Test
        @DisplayName("a save leaves no partial file behind")
        void savesAreAtomic(@TempDir Path dir) throws IOException {
            MirrorStore<String> store = open(dir, 24);
            for (int i = 0; i < 5; i++) {
                store.record(UUID + i, USER);
            }

            try (Stream<Path> files = Files.list(dir)) {
                List<String> names = files.map(p -> p.getFileName().toString()).collect(Collectors.toList());
                assertEquals(1, names.size(), "a temp file per save would accumulate: " + names);
                assertEquals("whitelist-mirror.json", names.get(0));
            }
        }

        @Test
        @DisplayName("a leftover temp file from a crashed save is not the store, and is ignored")
        void partialTempFileDoesNotCorruptTheStore(@TempDir Path dir) throws IOException {
            MirrorStore<String> store = open(dir, 24);
            store.record(UUID, USER);
            store.close();

            // What a crash mid-write leaves: the real file intact, a truncated sibling next to it.
            Files.write(dir.resolve("whitelist-mirror.json8675309.tmp"),
                    "{\"entries\":{\"broke".getBytes(StandardCharsets.UTF_8));

            MirrorStore<String> reopened = open(dir, 24);

            assertEquals(USER, reopened.get(UUID),
                    "the store is replaced by a rename, so a half-written temp file is never read");
            assertTrue(readRaw(dir).contains(UUID));
        }

        @Test
        @DisplayName("a truncated store file starts empty rather than failing the boot")
        void truncatedStoreFileLoadsEmpty(@TempDir Path dir) throws IOException {
            writeRaw(dir, "{\"entries\":{\"" + UUID + "\":{\"value\":\"Ste");

            MirrorStore<String> store = open(dir, 24);

            assertEquals(0, store.size());
            assertNull(store.get(UUID));
            // An empty mirror is repopulated by the next fetch; a failed boot means nobody joins.
            assertFalse(logger.records().isEmpty(), "but the operator should be told");
        }

        @Test
        void missingFileIsNotAnError(@TempDir Path dir) {
            MirrorStore<String> store = open(dir, 24);

            assertEquals(0, store.size());
            assertNull(store.lastEtag());
        }

        @Test
        void statsSummarisesTheMirror(@TempDir Path dir) {
            MirrorStore<String> store = open(dir, 24);
            store.record(UUID, USER);
            store.setLastEtag("\"abc\"");

            String stats = store.stats();

            assertTrue(stats.contains("1 entries"), stats);
            assertTrue(stats.contains("\"abc\""), stats);
        }

        @Test
        void blankKeysAndNullValuesAreRefused(@TempDir Path dir) {
            MirrorStore<String> store = open(dir, 24);

            store.record("  ", USER);
            store.record(UUID, null);

            assertEquals(0, store.size());
            assertNotNull(logger.records());
        }
    }
}
