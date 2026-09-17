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
    @DisplayName("a reason cannot forge a silent announcement with colour codes")
    void reasonCannotInjectColourCodes() {
        PunishmentAnnouncement line = PunishmentAnnouncement.issued(
                "ban", "Adam", "Steve", null, "\u00A7r\u00A78(silent) \u00A7fNotch", ANNOUNCED);

        assertFalse(line.silent());
        assertFalse(line.line().startsWith("\u00A78(silent) "),
                "the forgery is the point: a reset plus a grey (silent) prints a convincing "
                        + "announcement about a different player: " + line.line());
        assertEquals("\u00A7fAdam \u00A7cbanned \u00A7fSteve \u00A77: \u00A7f(silent) Notch",
                line.line(),
                "the words survive, the formatting does not");
    }

    @Test
    @DisplayName("every colour spelling is stripped, from every user-controlled segment")
    void everySpellingIsStripped() {
        String hexRun = "\u00A7x\u00A7f\u00A7f\u00A78\u00A78\u00A70\u00A70";
        PunishmentAnnouncement line = PunishmentAnnouncement.issued(
                "ban", "&4Adam", "\u00A7lSteve", null, hexRun + "&khi\u00A7#ff8800 there",
                ANNOUNCED);

        assertEquals("\u00A7fAdam \u00A7cbanned \u00A7fSteve \u00A77: \u00A7fhi there", line.line());
    }

    @Test
    @DisplayName("a code hidden inside a tag cannot survive the strip that removes it")
    void strippingRunsToAFixpoint() {
        // <§4red> is not a tag while the §4 is in it, so a single tag pass leaves it alone and the
        // legacy pass then hands back a live <red> that nothing looks at again.
        assertEquals("\u00A7fAdam \u00A7cbanned \u00A7fSteve \u00A77: \u00A7fcheating",
                PunishmentAnnouncement.issued(
                        "ban", "Adam", "Steve", null, "<\u00A74red>cheating", ANNOUNCED).line());
        assertEquals("\u00A7fAdam \u00A7cbanned \u00A7fSteve \u00A77: \u00A7fcheating",
                PunishmentAnnouncement.issued(
                        "ban", "Adam", "Steve", null, "<&4red>cheating", ANNOUNCED).line());
    }

    @Test
    @DisplayName("the same trick nested several deep also runs out")
    void nestedEscapesAlsoRunOut() {
        String line = PunishmentAnnouncement.issued(
                "ban", "Adam", "Steve", null, "<<\u00A74&4red>red>hi", ANNOUNCED).line();

        assertFalse(line.contains("<red>"), line);
        assertFalse(line.contains("\u00A7f<"), line);
        assertTrue(line.endsWith("hi"), line);
    }

    @Test
    @DisplayName("MiniMessage tags go too, and ordinary punctuation stays")
    void tagsAreStrippedAndTextSurvives() {
        assertTrue(PunishmentAnnouncement.issued(
                "ban", "Adam", "Steve", null, "<red>cheating</red>", ANNOUNCED)
                .line().endsWith("cheating"));
        assertTrue(PunishmentAnnouncement.issued(
                "ban", "Adam", "Steve", null, "said 3 < 4 and <3", ANNOUNCED)
                .line().endsWith("said 3 < 4 and <3"),
                "a tag is a tag, not every angle bracket a human types");
        assertTrue(PunishmentAnnouncement.issued(
                "ban", "Adam", "Steve", null, "Steve & Alex", ANNOUNCED)
                .line().endsWith("Steve & Alex"),
                "an ampersand only opens a colour code when a code character follows it");
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
    @DisplayName("a hidden line reaches the hidden node only, and nothing else widens it")
    void hiddenAudience() {
        PunishmentAnnouncement hidden = PunishmentAnnouncement.issued(
                "ban", "Adam", "Steve", null, "alting", false, true);

        assertTrue(hidden.hidden());
        assertTrue(hidden.silent(), "hidden implies silent whatever the silent argument said");
        assertTrue(hidden.line().startsWith("§5(hidden) "), hidden.line());
        assertFalse(hidden.line().contains("(silent)"),
                "one prefix: two would name a wider audience than the line has");

        assertFalse(hidden.visibleTo(false, false, false), "an ordinary player sees nothing");
        assertFalse(hidden.visibleTo(true, false, false),
                "the notify audience is the staff a hidden row is kept from");
        assertFalse(hidden.visibleTo(false, true, false),
                "and heimdall.admin does not imply the hidden node, unlike notify and silent");
        assertTrue(hidden.visibleTo(false, false, true));
        assertFalse(hidden.visibleTo(true, true),
                "the two-argument form has no hidden answer in hand, so it answers no");

        PunishmentAnnouncement lift = PunishmentAnnouncement.revoked(
                "ban", "Adam", "Steve", "", false, true);
        assertTrue(lift.hidden());
        assertTrue(lift.line().startsWith("§5(hidden) "), lift.line());
        assertFalse(lift.visibleTo(true, true, false));
        assertTrue(lift.visibleTo(false, false, true));
    }

    @Test
    @DisplayName("the hidden node does not widen an ordinary silent line's audience either way")
    void hiddenNodeDoesNotChangeASilentLine() {
        PunishmentAnnouncement silent = PunishmentAnnouncement.issued(
                "ban", "Adam", "Steve", null, "", true, false);
        assertFalse(silent.hidden());
        assertTrue(silent.line().startsWith("§8(silent) "), silent.line());
        assertFalse(silent.visibleTo(false, false, true),
                "holding the hidden node is not a claim to see every silent punishment");
        assertTrue(silent.visibleTo(true, false, false));
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
