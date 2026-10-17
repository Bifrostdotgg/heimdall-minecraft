package com.heimdall.platform.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.module.punishments.ActivePunishment;
import com.heimdall.module.punishments.HeimdallPunishmentsModule;
import com.heimdall.shell.contract.PunishmentRecord;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The core half of the public {@code HeimdallPunishments} API: what a mirror row becomes on its way
 * to the shell, and what a module that is not running answers (departure D89).
 */
class PunishmentSpiServiceTest {

    private static final UUID STEVE = UUID.fromString("8667ba71-b85a-4004-af54-457a9734eed7");
    private static final UUID ADAM = UUID.fromString("0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0");

    @Test
    @DisplayName("a mirror row carries every field across, with times as epoch milliseconds")
    void recordCarriesTheRow() {
        ActivePunishment p = new ActivePunishment();
        p.id = "abc123";
        p.type = "mute";
        p.targetUuid = STEVE.toString();
        p.targetName = "Steve";
        p.reason = "spam";
        p.issuedAt = "2026-10-01T12:00:00Z";
        p.expiresAt = "2026-10-02T12:00:00Z";
        p.issuedByName = "Adam";
        p.issuedByUuid = ADAM.toString();
        p.hidden = true;
        p.silent = true;

        PunishmentRecord record = PunishmentSpiService.record(p, STEVE, "mute");

        assertEquals("abc123", record.id());
        assertEquals("mute", record.type());
        assertEquals(STEVE, record.player());
        assertEquals("Steve", record.playerName());
        assertEquals("spam", record.reason());
        assertEquals(1_790_856_000_000L, record.issuedAtMillis());
        assertEquals(1_790_942_400_000L, record.expiresAtMillis());
        assertEquals("Adam", record.issuerName());
        assertEquals(ADAM, record.issuerUuid());
        assertTrue(record.hidden());
        assertTrue(record.silent());
    }

    @Test
    @DisplayName("a permanent row has no expiry, and a console issuer has no UUID")
    void permanentAndConsole() {
        ActivePunishment p = new ActivePunishment();
        p.type = "ban";
        p.targetUuid = STEVE.toString();
        p.issuedByName = "Console";
        p.issuedByUuid = "console";

        PunishmentRecord record = PunishmentSpiService.record(p, STEVE, "ban");

        assertEquals(0L, record.expiresAtMillis());
        assertEquals(0L, record.issuedAtMillis());
        assertEquals("", record.id());
        assertEquals("", record.reason());
        assertNull(record.issuerUuid(), "a non-UUID issuer is dropped rather than thrown on");
    }

    @Test
    @DisplayName("a module that was never enabled is unavailable and reports nobody punished")
    void disabledModuleAnswersNothing() {
        PunishmentSpiService service = new PunishmentSpiService(new HeimdallPunishmentsModule());

        assertFalse(service.isAvailable());
        assertNull(service.active(STEVE, "mute"));
    }
}
