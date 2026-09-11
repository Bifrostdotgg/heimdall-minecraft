package com.heimdall.core.punish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The wording, the audience rule, and the three things the line must never contain. */
class PunishmentAnnouncementTest {

    private static final boolean SILENT = true;
    private static final boolean ANNOUNCED = false;

    @Test
    @DisplayName("a permanent ban reads as one sentence")
    void permanentBan() {
        PunishmentAnnouncement line = PunishmentAnnouncement.issued(
                "ban", "Adam", "Steve", null, "griefing", ANNOUNCED);

        assertEquals("§fAdam §cbanned §fSteve §7: §fgriefing", line.line());
        assertFalse(line.silent());
    }

    @Test
    @DisplayName("a temporary mute carries how long it lasts")
    void temporaryMute() {
        PunishmentAnnouncement line = PunishmentAnnouncement.issued(
                "tempmute", "Adam", "Steve", Integer.valueOf(1440), "spam", ANNOUNCED);

        assertTrue(line.line().contains("muted"));
        assertTrue(line.line().contains("1d"), line.line());
    }

    @Test
    @DisplayName("a reasonless punishment stops after the name rather than trailing a colon")
    void noReason() {
        assertEquals("§fAdam §ckicked §fSteve",
                PunishmentAnnouncement.issued("kick", "Adam", "Steve", null, "", ANNOUNCED).line());
    }

    @Test
    @DisplayName("a revoke reads as its own verb, from either spelling of the action")
    void revokes() {
        assertEquals("§fAdam §aunbanned §fSteve",
                PunishmentAnnouncement.revoked("unban", "Adam", "Steve", "", ANNOUNCED).line());
        assertEquals("§fAdam §aunbanned §fSteve",
                PunishmentAnnouncement.revoked("ban", "Adam", "Steve", "", ANNOUNCED).line(),
                "the bot's revoke frame names the punishment type, a moderator types the verb, and "
                        + "the same event must not be worded two ways");
        assertTrue(PunishmentAnnouncement.revoked("ipban", "Adam", "Steve", "", ANNOUNCED)
                .line().contains("unbanned"));
        assertTrue(PunishmentAnnouncement.revoked("rollback", "Adam", "Steve", "", ANNOUNCED)
                .line().contains("revoked a punishment for"));
    }

    @Test
    @DisplayName("an issuer with no name is the console")
    void consoleIssuer() {
        assertTrue(PunishmentAnnouncement.issued("ban", null, "Steve", null, "", ANNOUNCED)
                .line().contains("Console"));
        assertTrue(PunishmentAnnouncement.issued("ban", "  ", "Steve", null, "", ANNOUNCED)
                .line().contains("Console"));
        assertTrue(PunishmentAnnouncement.issued("ban", "CONSOLE", "Steve", null, "", ANNOUNCED)
                .line().contains("Console"),
                "the platforms spell it CONSOLE; a broadcast line is read by players");
    }

    @Test
    @DisplayName("an IP ban says IP-banned and never the address")
    void ipBanNamesNoAddress() {
        String line = PunishmentAnnouncement.issued(
                "ipban", "Adam", "Steve", null, "alt account", ANNOUNCED).line();

        assertTrue(line.contains("IP-banned"));
        assertFalse(line.contains("."), "no address, no digest: " + line);
    }

    @Test
    @DisplayName("a multi-line reason is folded, so one punishment is one line")
    void reasonIsFoldedToOneLine() {
        String line = PunishmentAnnouncement.issued(
                "ban", "Adam", "Steve", null, "first\nsecond", ANNOUNCED).line();

        assertFalse(line.contains("\n"), line);
        assertTrue(line.contains("first second"));
    }

    @Test
    @DisplayName("silent prefixes the same sentence rather than replacing it")
    void silentIsPrefixed() {
        PunishmentAnnouncement line = PunishmentAnnouncement.issued(
                "ban", "Adam", "Steve", null, "griefing", SILENT);

        assertTrue(line.silent());
        assertTrue(line.line().startsWith("§8(silent) "), line.line());
        assertTrue(line.line().contains("banned §fSteve"));
    }

    @Test
    @DisplayName("an announced line reaches everybody; a silent one only notify or admin")
    void audience() {
        PunishmentAnnouncement announced = PunishmentAnnouncement.issued(
                "ban", "Adam", "Steve", null, "", ANNOUNCED);
        assertTrue(announced.visibleTo(false, false));
        assertTrue(announced.visibleTo(true, false));

        PunishmentAnnouncement silent = PunishmentAnnouncement.issued(
                "ban", "Adam", "Steve", null, "", SILENT);
        assertFalse(silent.visibleTo(false, false), "an ordinary player must see nothing at all");
        assertTrue(silent.visibleTo(true, false));
        assertTrue(silent.visibleTo(false, true), "heimdall.admin implies the notify node");
    }

    @Test
    @DisplayName("nothing is announced for a type that names no player, or for a nameless target")
    void nothingToSay() {
        assertNull(PunishmentAnnouncement.issued("geo", "Adam", "DE", null, "", ANNOUNCED));
        assertNull(PunishmentAnnouncement.issued("subnet", "Adam", "10.0.0.0/8", null, "", ANNOUNCED));
        assertNull(PunishmentAnnouncement.issued("freeze", "Adam", "Steve", null, "", ANNOUNCED));
        assertNull(PunishmentAnnouncement.issued("ban", "Adam", "", null, "", ANNOUNCED),
                "a UUID is not a name, and announcing one would be worse than staying quiet");
        assertNull(PunishmentAnnouncement.revoked("geo", "Adam", "DE", "", ANNOUNCED));
    }

    @Test
    @DisplayName("durations read as at most two units, and permanent reads as none")
    void durations() {
        assertNull(PunishmentAnnouncement.compactDuration(null));
        assertNull(PunishmentAnnouncement.compactDuration(Integer.valueOf(0)));
        assertEquals("45m", PunishmentAnnouncement.compactDuration(Integer.valueOf(45)));
        assertEquals("1h 30m", PunishmentAnnouncement.compactDuration(Integer.valueOf(90)));
        assertEquals("2h", PunishmentAnnouncement.compactDuration(Integer.valueOf(120)));
        assertEquals("7d", PunishmentAnnouncement.compactDuration(Integer.valueOf(7 * 1440)));
        assertEquals("3d 4h", PunishmentAnnouncement.compactDuration(
                Integer.valueOf(3 * 1440 + 4 * 60 + 17)),
                "the minutes are dropped once there are days: a broadcast answers 'how long', not "
                        + "'exactly when'");
    }
}
