package com.heimdall.module.punishments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.log.RecordingLogger;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LastIpStoreTest {

    private static final String STEVE = "11111111-1111-1111-1111-111111111111";
    private static final String ALEX = "22222222-2222-2222-2222-222222222222";

    @TempDir
    Path dir;

    @Test
    void recordsAndFindsAltsWithoutExposingTheFullAddressInObfuscation() {
        LastIpStore store = new LastIpStore(new RecordingLogger(true), dir.resolve("ips.json"));
        store.record(STEVE, "Steve", "203.0.113.9", 1L);
        store.record(ALEX, "Alex", "203.0.113.9", 2L);

        LastIpStore.Alts alts = store.altsOf(STEVE, 50);
        assertTrue(alts.addressKnown);
        assertFalse(alts.truncated);
        assertEquals(1, alts.rows.size());
        assertEquals("Alex", alts.rows.get(0).name);
        assertEquals(ALEX, alts.rows.get(0).uuid);
        assertEquals(2L, alts.rows.get(0).seenAt);

        // Matching is on the last address only, so moving off it ends the association. That is the
        // narrowness altsOf documents, pinned rather than assumed.
        store.record(STEVE, "Steve", "203.0.113.10", 3L);
        assertEquals(0, store.altsOf(STEVE, 50).rows.size());
        assertEquals(0, store.altsOf(ALEX, 50).rows.size());

        assertEquals("203.0.113.*", LastIpStore.obfuscate("203.0.113.9"));
        assertEquals("2001:db8:*", LastIpStore.obfuscate("2001:db8:1:2::3"));
        assertTrue(store.get(STEVE).history.size() >= 2);
    }

    @Test
    void anUnknownPlayerIsDistinguishableFromOneWithNoAlts() {
        LastIpStore store = new LastIpStore(new RecordingLogger(true), dir.resolve("ips.json"));
        store.record(STEVE, "Steve", "203.0.113.9", 1L);

        // Two empty lists that mean opposite things. Collapsing them is how a dashboard ends up
        // telling somebody "no shared addresses" about a server that has never seen the player.
        assertFalse(store.altsOf(ALEX, 50).addressKnown);
        assertTrue(store.altsOf(STEVE, 50).addressKnown);
        assertEquals(0, store.altsOf(STEVE, 50).rows.size());
    }

    @Test
    void altsAreCappedNewestFirstAndSayWhenTheyWereCut() {
        LastIpStore store = new LastIpStore(new RecordingLogger(true), dir.resolve("ips.json"));
        store.record(STEVE, "Steve", "203.0.113.9", 0L);
        for (int i = 1; i <= 5; i++) {
            store.record(String.format("%08d-0000-0000-0000-000000000000", i),
                    "Player" + i, "203.0.113.9", i);
        }

        LastIpStore.Alts capped = store.altsOf(STEVE, 3);
        assertEquals(3, capped.rows.size());
        assertTrue(capped.truncated);
        assertEquals(5L, capped.rows.get(0).seenAt, "newest first");
        assertEquals(4L, capped.rows.get(1).seenAt);
        assertEquals(3L, capped.rows.get(2).seenAt);

        // A caller that only wants to know there were any still gets a truthful truncated flag.
        LastIpStore.Alts counted = store.altsOf(STEVE, 0);
        assertEquals(0, counted.rows.size());
        assertTrue(counted.truncated);
        assertTrue(counted.addressKnown);
    }

    @Test
    void survivesReload() {
        Path file = dir.resolve("ips.json");
        LastIpStore first = new LastIpStore(new RecordingLogger(true), file);
        first.record("11111111-1111-1111-1111-111111111111", "Steve", "10.0.0.1", 5L);
        first.flush();

        LastIpStore second = new LastIpStore(new RecordingLogger(true), file);
        LastIpStore.PlayerIps steve = second.byName("Steve");
        assertEquals("10.0.0.1", steve.lastIp);
    }
}
