package com.heimdall.module.punishments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.log.RecordingLogger;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LastIpStoreTest {

    @TempDir
    Path dir;

    @Test
    void recordsAndFindsAltsWithoutExposingTheFullAddressInObfuscation() {
        LastIpStore store = new LastIpStore(new RecordingLogger(true), dir.resolve("ips.json"));
        store.record("11111111-1111-1111-1111-111111111111", "Steve", "203.0.113.9", 1L);
        store.record("22222222-2222-2222-2222-222222222222", "Alex", "203.0.113.9", 2L);
        store.record("11111111-1111-1111-1111-111111111111", "Steve", "203.0.113.10", 3L);

        List<LastIpStore.PlayerIps> first = store.sharing("203.0.113.9");
        assertEquals(1, first.size());
        assertEquals("Alex", first.get(0).name);

        assertEquals("203.0.113.*", LastIpStore.obfuscate("203.0.113.9"));
        assertEquals("2001:db8:*", LastIpStore.obfuscate("2001:db8:1:2::3"));
        assertTrue(store.get("11111111-1111-1111-1111-111111111111").history.size() >= 2);
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
