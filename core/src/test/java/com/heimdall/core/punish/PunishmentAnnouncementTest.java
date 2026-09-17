package com.heimdall.core.punish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.testing.TestText;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The wording, the audience rule, and the three things the line must never contain. */
class PunishmentAnnouncementTest {

    private static final boolean SILENT = true;
    private static final boolean ANNOUNCED = false;

    private static final long NOW = 1_757_000_000_000L;
    private static final String ISSUE = PunishmentScreens.ANNOUNCE_ISSUE;
    private static final String REVOKE = PunishmentScreens.ANNOUNCE_REVOKE;

    @Test
    @DisplayName("a permanent ban reads as one sentence")
    void permanentBan() {
        PunishmentAnnouncement line = issued("ban", "Adam", "Steve", null, "griefing", ANNOUNCED);

        assertEquals("Bifrost » Adam banned Steve for griefing", plain(line));
        assertFalse(line.silent());
    }

    @Test
    @DisplayName("a temporary ban says so, and carries how long it lasts")
    void temporaryBan() {
        PunishmentAnnouncement line = issued(
                "ban", "Adam", "Steve", Long.valueOf(NOW + 86_400_000L), "spam", ANNOUNCED);

        assertEquals("Bifrost » Adam temporarily banned Steve for spam (1d)", plain(line),
                "the verb and the length are the whole difference between the two, and a "
                        + "permanent ban must not read as a temporary one with the clause missing");
    }

    @Test
    @DisplayName("a temporary mute reads as one too")
    void temporaryMute() {
        PunishmentAnnouncement line = issued(
                "mute", "Adam", "Steve", Long.valueOf(NOW + 3_600_000L), "spam", ANNOUNCED);

        assertTrue(plain(line).contains("temporarily muted"), plain(line));
        assertTrue(plain(line).contains("(1h)"), plain(line));
    }

    @Test
    @DisplayName("a reasonless punishment stops after the name rather than trailing a clause")
    void noReason() {
        assertEquals("Bifrost » Adam kicked Steve",
                plain(issued("kick", "Adam", "Steve", null, "", ANNOUNCED)));
    }

    @Test
    @DisplayName("a revoke reads as its own verb, from either spelling of the action")
    void revokes() {
        assertEquals("Bifrost » Adam unbanned Steve", plain(revoked("unban", "Steve", "")));
        assertEquals("Bifrost » Adam unbanned Steve", plain(revoked("ban", "Steve", "")),
                "the bot's revoke frame names the punishment type, a moderator types the verb, and "
                        + "the same event must not be worded two ways");
        assertTrue(plain(revoked("ipban", "Steve", "")).contains("unbanned"));
        assertTrue(plain(revoked("rollback", "Steve", "")).contains("revoked a punishment for"));
        assertTrue(plain(revoked("unban", "Steve", "served their time"))
                .contains("for served their time"));
    }

    @Test
    @DisplayName("an issuer with no name is the console")
    void consoleIssuer() {
        assertTrue(plain(issued("ban", null, "Steve", null, "", ANNOUNCED)).contains("Console"));
        assertTrue(plain(issued("ban", "  ", "Steve", null, "", ANNOUNCED)).contains("Console"));
        assertTrue(plain(issued("ban", "CONSOLE", "Steve", null, "", ANNOUNCED)).contains("Console"),
                "the platforms spell it CONSOLE; a broadcast line is read by players");
    }

    @Test
    @DisplayName("an IP ban says IP-banned and never the address")
    void ipBanNamesNoAddress() {
        String line = plain(issued("ipban", "Adam", "Steve", null, "alt account", ANNOUNCED));

        assertTrue(line.contains("IP-banned"));
        assertFalse(line.contains("."), "no address, no digest: " + line);
    }

    @Test
    @DisplayName("a multi-line reason is folded, so one punishment is one line")
    void reasonIsFoldedToOneLine() {
        String line = plain(issued("ban", "Adam", "Steve", null, "first\nsecond", ANNOUNCED));

        assertFalse(line.contains("\n"), line);
        assertTrue(line.contains("first second"));
    }

    @Test
    @DisplayName("a reason cannot forge a silent announcement with colour codes")
    void reasonCannotInjectColourCodes() {
        PunishmentAnnouncement line = issued(
                "ban", "Adam", "Steve", null, "§r§8(silent) §fNotch", ANNOUNCED);

        assertFalse(line.silent());
        assertFalse(line.line().startsWith("§8(silent) "),
                "the forgery is the point: a reset plus a grey (silent) prints a convincing "
                        + "announcement about a different player: " + line.line());
        assertEquals("Bifrost » Adam banned Steve for (silent) Notch", plain(line),
                "the words survive, the formatting does not");
    }

    @Test
    @DisplayName("every colour spelling is stripped, from every user-controlled segment")
    void everySpellingIsStripped() {
        String hexRun = "§x§f§f§8§8§0§0";
        PunishmentAnnouncement line = issued(
                "ban", "&4Adam", "§lSteve", null, hexRun + "&khi§#ff8800 there",
                ANNOUNCED);

        assertEquals("Bifrost » Adam banned Steve for hi there", plain(line));
    }

    @Test
    @DisplayName("a code hidden inside a tag cannot survive the strip that removes it")
    void strippingRunsToAFixpoint() {
        // <§4red> is not a tag while the §4 is in it, so a single tag pass leaves it alone and the
        // legacy pass then hands back a live <red> that nothing looks at again.
        assertEquals("Bifrost » Adam banned Steve for cheating",
                plain(issued("ban", "Adam", "Steve", null, "<§4red>cheating", ANNOUNCED)));
        assertEquals("Bifrost » Adam banned Steve for cheating",
                plain(issued("ban", "Adam", "Steve", null, "<&4red>cheating", ANNOUNCED)));
    }

    @Test
    @DisplayName("the same trick nested several deep also runs out")
    void nestedEscapesAlsoRunOut() {
        String line = plain(issued("ban", "Adam", "Steve", null, "<<§4&4red>red>hi", ANNOUNCED));

        assertFalse(line.contains("<red>"), line);
        assertTrue(line.endsWith("hi"), line);
    }

    @Test
    @DisplayName("MiniMessage tags go too, and ordinary punctuation stays")
    void tagsAreStrippedAndTextSurvives() {
        assertTrue(plain(issued("ban", "Adam", "Steve", null, "<red>cheating</red>", ANNOUNCED))
                .endsWith("cheating"));
        assertTrue(plain(issued("ban", "Adam", "Steve", null, "said 3 < 4 and <3", ANNOUNCED))
                .endsWith("said 3 < 4 and <3"),
                "a tag is a tag, not every angle bracket a human types");
        assertTrue(plain(issued("ban", "Adam", "Steve", null, "Steve & Alex", ANNOUNCED))
                .endsWith("Steve & Alex"),
                "an ampersand only opens a colour code when a code character follows it");
    }

    @Test
    @DisplayName("silent prefixes the same sentence rather than replacing it")
    void silentIsPrefixed() {
        PunishmentAnnouncement line = issued("ban", "Adam", "Steve", null, "griefing", SILENT);

        assertTrue(line.silent());
        assertTrue(plain(line).startsWith("(silent) "), plain(line));
        assertTrue(plain(line).contains("banned Steve"));
    }

    @Test
    @DisplayName("a guild cannot template its way out of the silent prefix")
    void silenceIsNotConfigurable() {
        PunishmentAnnouncement line = PunishmentAnnouncement.issued(
                "<white>{staff} did something</white>",
                view("ban", "Adam", "Steve", null, "griefing", SILENT), NOW);

        assertTrue(plain(line).startsWith("(silent) "), plain(line));
    }

    @Test
    @DisplayName("an announced line reaches everybody; a silent one only notify or admin")
    void audience() {
        PunishmentAnnouncement announced = issued("ban", "Adam", "Steve", null, "", ANNOUNCED);
        assertTrue(announced.visibleTo(false, false));
        assertTrue(announced.visibleTo(true, false));

        PunishmentAnnouncement silent = issued("ban", "Adam", "Steve", null, "", SILENT);
        assertFalse(silent.visibleTo(false, false), "an ordinary player must see nothing at all");
        assertTrue(silent.visibleTo(true, false));
        assertTrue(silent.visibleTo(false, true), "heimdall.admin implies the notify node");
    }

    @Test
    @DisplayName("a hidden line reaches the hidden node only, and nothing else widens it")
    void hiddenAudience() {
        PunishmentAnnouncement hidden = PunishmentAnnouncement.issued(
                ISSUE, hiddenView("ban", "Adam", "Steve", null, "alting", ANNOUNCED), NOW);

        assertTrue(hidden.hidden());
        assertTrue(hidden.silent(), "hidden implies silent whatever the row's silent flag said");
        assertTrue(plain(hidden).startsWith("(hidden) "), plain(hidden));
        assertTrue(hidden.line().contains("§5(hidden)"), hidden.line());
        assertFalse(plain(hidden).contains("(silent)"),
                "one prefix: two would name a wider audience than the line has");

        assertFalse(hidden.visibleTo(false, false, false), "an ordinary player sees nothing");
        assertFalse(hidden.visibleTo(true, false, false),
                "the notify audience is the staff a hidden row is kept from");
        assertFalse(hidden.visibleTo(false, true, false),
                "and heimdall.admin does not imply the hidden node, unlike notify and silent");
        assertTrue(hidden.visibleTo(false, false, true));
        assertFalse(hidden.visibleTo(true, true),
                "the two-argument form has no hidden answer in hand, so it answers no");

        PunishmentAnnouncement lift = PunishmentAnnouncement.revoked(REVOKE, "ban",
                hiddenView("ban", "Adam", "Steve", null, "", ANNOUNCED), NOW);
        assertTrue(lift.hidden(),
                "hidden is read off the row being lifted, so -p cannot publish the lift");
        assertTrue(plain(lift).startsWith("(hidden) "), plain(lift));
        assertFalse(lift.visibleTo(true, true, false));
        assertTrue(lift.visibleTo(false, false, true));
    }

    @Test
    @DisplayName("the hidden node does not widen an ordinary silent line's audience either way")
    void hiddenNodeDoesNotChangeASilentLine() {
        PunishmentAnnouncement silent = PunishmentAnnouncement.issued(
                ISSUE, view("ban", "Adam", "Steve", null, "", SILENT), NOW);
        assertFalse(silent.hidden());
        assertTrue(plain(silent).startsWith("(silent) "), plain(silent));
        assertFalse(silent.visibleTo(false, false, true),
                "holding the hidden node is not a claim to see every silent punishment");
        assertTrue(silent.visibleTo(true, false, false));
    }

    @Test
    @DisplayName("nothing is announced for a type that names no player, or for a nameless target")
    void nothingToSay() {
        assertNull(issued("geo", "Adam", "DE", null, "", ANNOUNCED));
        assertNull(issued("subnet", "Adam", "10.0.0.0/8", null, "", ANNOUNCED));
        assertNull(issued("freeze", "Adam", "Steve", null, "", ANNOUNCED));
        assertNull(issued("ban", "Adam", "", null, "", ANNOUNCED),
                "a UUID is not a name, and announcing one would be worse than staying quiet");
        assertNull(revoked("geo", "DE", ""));
    }

    @Test
    @DisplayName("a guild that cleared the template announces nothing at all")
    void emptyTemplateIsOff() {
        assertNull(PunishmentAnnouncement.issued(
                "", view("ban", "Adam", "Steve", null, "griefing", ANNOUNCED), NOW));
        assertNull(PunishmentAnnouncement.issued(
                "   ", view("ban", "Adam", "Steve", null, "griefing", ANNOUNCED), NOW),
                "and a template of nothing but spaces is the same decision typed less carefully");
    }

    @Test
    @DisplayName("a guild's own template is used, with the same tokens")
    void customTemplate() {
        PunishmentAnnouncement line = PunishmentAnnouncement.issued(
                "<red>{player}</red> was {verb} by {staff}[ ({duration})]",
                view("ban", "Adam", "Steve", Long.valueOf(NOW + 3_600_000L), "", ANNOUNCED), NOW);

        assertEquals("Steve was temporarily banned by Adam (1h)", plain(line));
    }

    @Test
    @DisplayName("durations read as at most two units, and permanent reads as none")
    void durations() {
        assertNull(PunishmentText.compactDuration(null));
        assertNull(PunishmentText.compactDuration(Long.valueOf(0L)));
        assertEquals("30s", PunishmentText.compactDuration(Long.valueOf(30L)),
                "half a minute is a punishment somebody asked for, not a rounding error");
        assertEquals("45m", PunishmentText.compactDuration(Long.valueOf(45 * 60L)));
        assertEquals("1m 30s", PunishmentText.compactDuration(Long.valueOf(90L)));
        assertEquals("1h 30m", PunishmentText.compactDuration(Long.valueOf(90 * 60L)));
        assertEquals("2h", PunishmentText.compactDuration(Long.valueOf(2 * 3600L)));
        assertEquals("23h 42m",
                PunishmentText.compactDuration(Long.valueOf(23 * 3600 + 42 * 60 + 9L)));
        assertEquals("7d", PunishmentText.compactDuration(Long.valueOf(7 * 86400L)));
        assertEquals("3d 4h",
                PunishmentText.compactDuration(Long.valueOf(3 * 86400 + 4 * 3600 + 17 * 60L)),
                "the minutes are dropped once there are days: a broadcast answers 'how long', not "
                        + "'exactly when'");
    }

    @Test
    @DisplayName("a cleared template is the off switch, and it is off for notify holders too")
    void aClearedTemplateSilencesEverybody() {
        PunishmentView ban = view("ban", "Adam", "Steve", null, "griefing", ANNOUNCED);
        PunishmentView silentBan = view("ban", "Adam", "Steve", null, "griefing", SILENT);

        assertNull(PunishmentAnnouncement.issued("", ban, NOW));
        assertNull(PunishmentAnnouncement.issued("   \n  ", ban, NOW),
                "whitespace is a cleared box that kept a newline");
        assertNull(PunishmentAnnouncement.issued(null, ban, NOW));
        assertNull(PunishmentAnnouncement.revoked("", "unban", ban, NOW));
        assertNull(PunishmentAnnouncement.issued("", silentBan, NOW),
                "there is no line, so there is no audience question to answer: a guild that "
                        + "cleared the box turned the broadcast off, not the public half of it");

        assertNotNull(PunishmentAnnouncement.issued(ISSUE, ban, NOW),
                "and an unset key is not a cleared one - it falls back to the shared default");
    }

    private static PunishmentAnnouncement issued(String type, String staff, String target,
            Long expiresAtMillis, String reason, boolean silent) {
        return PunishmentAnnouncement.issued(
                ISSUE, view(type, staff, target, expiresAtMillis, reason, silent), NOW);
    }

    private static PunishmentAnnouncement revoked(String typeOrVerb, String target, String reason) {
        return PunishmentAnnouncement.revoked(REVOKE, typeOrVerb,
                view(typeOrVerb, "Adam", target, null, reason, false), NOW);
    }

    /** The same row, hidden. Silence is left off deliberately: hidden has to imply it. */
    private static PunishmentView hiddenView(String type, String staff, String target,
            Long expiresAtMillis, String reason, boolean silent) {
        return PunishmentView.builder()
                .type(type)
                .staffName(staff)
                .targetName(target)
                .reason(reason)
                .serverName("Bifrost")
                .issuedAtMillis(NOW)
                .expiresAtMillis(expiresAtMillis)
                .silent(silent)
                .hidden(true)
                .build();
    }

    private static PunishmentView view(String type, String staff, String target,
            Long expiresAtMillis, String reason, boolean silent) {
        return PunishmentView.builder()
                .type(type)
                .staffName(staff)
                .targetName(target)
                .reason(reason)
                .serverName("Bifrost")
                .issuedAtMillis(NOW)
                .expiresAtMillis(expiresAtMillis)
                .silent(silent)
                .build();
    }

    private static String plain(PunishmentAnnouncement announcement) {
        return TestText.plain(announcement.message());
    }
}
