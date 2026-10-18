package com.heimdall.module.punishments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.http.model.PunishmentImportRow;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * How one LiteBans row becomes an import row.
 *
 * <p>Like the hook, the importer only ever runs against a real LiteBans database on a customer's
 * server, so the mapping is a pure function of a column map and asserted here instead.
 */
class LiteBansImporterTest {

    private static final String PLAYER = "11111111-2222-3333-4444-555555555555";
    private static final String STAFF = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    private static final long START = 1_700_000_000_000L;

    @Test
    @DisplayName("a tempban carries its length in seconds, its staff uuid and the hook's id shape")
    void tempban() {
        Map<String, Object> row = row(12L, "ban");
        row.put("until", START + 7L * 86_400_000L);

        PunishmentImportRow out = LiteBansImporter.mapRow(row, "ban");

        assertNotNull(out);
        assertEquals("ban", out.type);
        assertEquals("ban:12", out.importProviderId);
        assertEquals(PLAYER, out.targetUuid);
        assertEquals(STAFF, out.issuedByUuid);
        assertEquals("Mod", out.issuedByName);
        assertEquals(Long.valueOf(7L * 86_400L), out.durationSeconds);
        assertEquals(Integer.valueOf(7 * 1440), out.durationMinutes);
        assertEquals(START, out.issuedAtMillis);
        assertTrue(out.active);
    }

    @Test
    @DisplayName("a length past the bot's ceiling is imported as permanent, not refused")
    void pastTheCeilingIsPermanent() {
        Map<String, Object> row = row(1L, "ban");
        row.put("until", START + 200L * 365L * 86_400_000L);

        PunishmentImportRow out = LiteBansImporter.mapRow(row, "ban");

        assertNotNull(out);
        assertNull(out.durationSeconds);
        assertNull(out.durationMinutes);
    }

    @Test
    @DisplayName("-1 until is permanent")
    void permanent() {
        Map<String, Object> row = row(1L, "ban");
        row.put("until", -1L);

        assertNull(LiteBansImporter.mapRow(row, "ban").durationSeconds);
    }

    @Test
    @DisplayName("the console's uuid is not a player")
    void consoleIsNobody() {
        Map<String, Object> row = row(3L, "mute");
        row.put("banned_by_uuid", "CONSOLE");
        row.put("banned_by_name", "Console");

        PunishmentImportRow out = LiteBansImporter.mapRow(row, "mute");

        assertNull(out.issuedByUuid);
        assertEquals("Console", out.issuedByName);
    }

    @Test
    @DisplayName("an IP-only ban keeps its address and names no player")
    void ipOnlyBan() {
        Map<String, Object> row = row(5L, "ban");
        row.put("uuid", "#offline#");
        row.put("ipban", Boolean.TRUE);

        PunishmentImportRow out = LiteBansImporter.mapRow(row, "ban");

        assertNotNull(out);
        assertEquals("ipban", out.type);
        assertEquals("ipban:5", out.importProviderId);
        assertNull(out.targetUuid, "#offline# is LiteBans' placeholder, not a player");
        assertEquals("203.0.113.9", out.ip);
    }

    @Test
    @DisplayName("a ban with no player and no address is left out")
    void nobodyIsSkipped() {
        Map<String, Object> row = row(6L, "ban");
        row.put("uuid", "#offline#");

        assertNull(LiteBansImporter.mapRow(row, "ban"));
    }

    @Test
    @DisplayName("an undashed uuid is dashed, as the hook does")
    void undashedUuid() {
        Map<String, Object> row = row(7L, "warn");
        row.put("uuid", PLAYER.replace("-", ""));

        assertEquals(PLAYER, LiteBansImporter.mapRow(row, "warn").targetUuid);
    }

    @Test
    @DisplayName("a lifted row says who lifted it, when, and why")
    void liftedRow() {
        Map<String, Object> row = row(8L, "ban");
        row.put("active", Integer.valueOf(0));
        row.put("removed_by_name", "Admin");
        row.put("removed_by_reason", "appeal accepted");
        row.put("removed_by_date", new Timestamp(START + 3_600_000L));

        PunishmentImportRow out = LiteBansImporter.mapRow(row, "ban");

        assertFalse(out.active);
        assertEquals("Admin", out.revokedBy);
        assertEquals("appeal accepted", out.revokeReason);
        assertEquals(START + 3_600_000L, out.revokedAtMillis);
    }

    @Test
    @DisplayName("LiteBans retiring a row itself is an expiry, not somebody lifting it")
    void expiredIsNotARevoke() {
        Map<String, Object> row = row(9L, "mute");
        row.put("until", START + 60_000L);
        row.put("active", Boolean.FALSE);
        row.put("removed_by_name", "#expired");

        PunishmentImportRow out = LiteBansImporter.mapRow(row, "mute");

        assertFalse(out.active);
        assertNull(out.revokedBy);
    }

    @Test
    @DisplayName("a scoped punishment is marked, a global one is not")
    void serverScope() {
        Map<String, Object> scoped = row(10L, "ban");
        scoped.put("server_scope", "survival");
        Map<String, Object> global = row(11L, "ban");
        global.put("server_scope", "*");

        assertEquals("survival", LiteBansImporter.mapRow(scoped, "ban").serverScope);
        assertNull(LiteBansImporter.mapRow(global, "ban").serverScope);
    }

    @Test
    @DisplayName("missing columns cost their field, not the row")
    void missingColumns() {
        Map<String, Object> row = new HashMap<String, Object>();
        row.put("id", Long.valueOf(13L));
        row.put("uuid", PLAYER);
        row.put("time", Long.valueOf(START));

        PunishmentImportRow out = LiteBansImporter.mapRow(row, "kick");

        assertNotNull(out);
        assertEquals("kick:13", out.importProviderId);
        assertFalse(out.active, "a kick is never active");
    }

    @Test
    @DisplayName("history rows: placeholder addresses are dropped, dates read from a timestamp")
    void sightings() {
        Map<String, Object> row = new HashMap<String, Object>();
        row.put("uuid", PLAYER);
        row.put("name", "Steve");
        row.put("ip", "#undefined#");
        row.put("date", new Timestamp(START));

        LiteBansImporter.Sighting sighting = LiteBansImporter.mapSighting(row);

        assertNotNull(sighting);
        assertEquals("Steve", sighting.name);
        assertNull(sighting.ip);
        assertEquals(START, sighting.seenAt);
    }

    @Test
    @DisplayName("sightings are kept for punished players, and for everyone else only when recent")
    void sightingsKept() {
        long cutoff = START;
        java.util.Set<String> punished = Collections.singleton(PLAYER);
        String other = "99999999-8888-7777-6666-555555555555";

        assertTrue(LiteBansImporter.keepSighting(
                new LiteBansImporter.Sighting(PLAYER.toUpperCase(), "Steve", "203.0.113.9", 1L),
                punished, cutoff), "a punished player's old sighting stays, whatever the case");
        assertFalse(LiteBansImporter.keepSighting(
                new LiteBansImporter.Sighting(other, "Alex", "203.0.113.9", START - 1L),
                punished, cutoff), "an unpunished player's old sighting goes");
        assertTrue(LiteBansImporter.keepSighting(
                new LiteBansImporter.Sighting(other, "Alex", "203.0.113.9", START),
                punished, cutoff));
        assertFalse(LiteBansImporter.keepSighting(
                new LiteBansImporter.Sighting(PLAYER, "Steve", null, START),
                punished, cutoff), "no address, nothing to keep");
    }

    @Test
    @DisplayName("rows are sent oldest first across sources, undated rows last")
    void importOrder() {
        PunishmentImportRow kick2023 = dated("kick", 1_680_000_000_000L);
        PunishmentImportRow ban2026 = dated("ban", 1_790_000_000_000L);
        PunishmentImportRow vanilla = dated("ban", 0L);
        PunishmentImportRow mute2024 = dated("mute", 1_710_000_000_000L);
        List<PunishmentImportRow> rows = new ArrayList<PunishmentImportRow>(
                Arrays.asList(ban2026, vanilla, mute2024, kick2023));

        PunishmentImporters.sortForImport(rows);

        assertEquals(Arrays.asList(kick2023, mute2024, ban2026, vanilla), rows);
    }

    private static PunishmentImportRow dated(String type, long at) {
        PunishmentImportRow row = new PunishmentImportRow();
        row.type = type;
        row.issuedAtMillis = at;
        return row;
    }

    private static Map<String, Object> row(long id, String type) {
        Map<String, Object> row = new HashMap<String, Object>();
        row.put("id", Long.valueOf(id));
        row.put("uuid", PLAYER);
        row.put("ip", "203.0.113.9");
        row.put("reason", "griefing");
        row.put("banned_by_uuid", STAFF);
        row.put("banned_by_name", "Mod");
        row.put("time", Long.valueOf(START));
        row.put("until", Long.valueOf(0L));
        row.put("silent", Boolean.FALSE);
        row.put("ipban", Boolean.FALSE);
        row.put("active", Boolean.TRUE);
        return row;
    }
}
