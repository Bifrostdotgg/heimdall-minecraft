package com.heimdall.module.punishments;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.config.ServerRole;
import com.heimdall.core.json.Payload;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link HeimdallPunishmentsModule#activeFor}, the mirror read the public {@code HeimdallPunishments}
 * API answers through (departure D89). It runs on other plugins' chat threads, so it has to answer
 * from memory, ignore what has expired, and answer "nothing" rather than throw while the module is
 * off.
 */
class ActiveForTest {

    @TempDir
    Path dataDir;

    private static final UUID STEVE = UUID.fromString("8667ba71-b85a-4004-af54-457a9734eed7");

    @Test
    @DisplayName("an active mute is found by type and UUID, and only by those")
    void findsTheActiveRow() {
        try (PunishmentsHarness harness = enabled()) {
            ActivePunishment mute = row("mute", null);
            harness.module.mirrorForTest().record("mute:" + STEVE, mute);

            assertSame(mute, harness.module.activeFor("mute", STEVE));
            assertTrue(harness.module.isMuted(STEVE));
            assertNull(harness.module.activeFor("ban", STEVE), "a mute is not a ban");
            assertNull(harness.module.activeFor("mute", UUID.randomUUID()));
            assertNull(harness.module.activeFor("mute", null));
            assertNull(harness.module.activeFor(null, STEVE));
        }
    }

    @Test
    @DisplayName("an expired row still in the mirror is not active")
    void expiredIsNotActive() {
        try (PunishmentsHarness harness = enabled()) {
            harness.module.mirrorForTest().record("mute:" + STEVE,
                    row("mute", Instant.now().minusSeconds(60).toString()));

            assertNull(harness.module.activeFor("mute", STEVE));
            assertFalse(harness.module.isMuted(STEVE));
            assertFalse(harness.module.isMuteHidden(STEVE));
        }
    }

    @Test
    @DisplayName("a disabled module answers nothing rather than throwing")
    void disabledAnswersNothing() {
        try (PunishmentsHarness harness = enabled()) {
            harness.module.mirrorForTest().record("mute:" + STEVE, row("mute", null));
            harness.disableModule();

            assertFalse(harness.module.isAvailable());
            assertNull(harness.module.activeFor("mute", STEVE));
            assertFalse(harness.module.isMuted(STEVE));
        }
    }

    private PunishmentsHarness enabled() {
        return new PunishmentsHarness(dataDir, ServerRole.STANDALONE)
                .enableWith(Payload.builder().put("mode", "replace").build());
    }

    private static ActivePunishment row(String type, String expiresAt) {
        ActivePunishment p = new ActivePunishment();
        p.id = "p1";
        p.type = type;
        p.targetUuid = STEVE.toString();
        p.targetName = "Steve";
        p.reason = "spam";
        p.expiresAt = expiresAt;
        return p;
    }
}
