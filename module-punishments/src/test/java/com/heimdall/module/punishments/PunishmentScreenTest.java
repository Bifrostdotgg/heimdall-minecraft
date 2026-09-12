package com.heimdall.module.punishments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.json.Payload;
import com.heimdall.core.punish.PunishmentScreens;
import com.heimdall.core.remoteconfig.ModuleConfig;
import com.heimdall.core.testing.TestText;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a punished player actually reads.
 *
 * <p>The screens were four one-line strings with three tokens between them, so a ban screen could
 * not say who banned you, why, for how long or where to appeal - which is every question a
 * disconnect screen exists to answer. These assert the composed defaults, the optional-segment
 * rule doing its job on a real screen, and the two ways a value could get from a guild or a
 * moderator into the formatting.
 */
class PunishmentScreenTest {

    /**
     * The clock the rendering uses, which is the real one.
     *
     * <p>{@code render} reads {@code System.currentTimeMillis()} for the remaining time, so a
     * fixture pinned to a date in the past would render every temporary punishment as already
     * over - and the Length row, which carries {@code {remaining}}, would vanish for the reason
     * the optional-segment rule exists rather than the reason the test is checking.
     */
    private static final long NOW = System.currentTimeMillis();

    @Test
    @DisplayName("a permanent ban names the guild, the staff, the reason and the id")
    void permanentBan() {
        String screen = plain(ban(null), settings(Payload.builder()
                .put("serverName", "Bifrost")
                .put("appealUrl", "https://bans.example/abc")
                .build()));

        assertTrue(screen.contains("Bifrost Punishments"), screen);
        assertTrue(screen.contains("Staff"), screen);
        assertTrue(screen.contains("Adam"), screen);
        assertTrue(screen.contains("Steve"), screen);
        assertTrue(screen.contains("ban-1"), screen);
        assertTrue(screen.contains("Permanent ban"), screen);
        assertTrue(screen.contains("griefing"), screen);
        assertTrue(screen.contains("https://bans.example/abc"), screen);
    }

    @Test
    @DisplayName("a permanent ban has no Length row, because there is no length")
    void permanentBanHasNoLength() {
        String screen = plain(ban(null), settings(Payload.builder().build()));

        assertFalse(screen.contains("Length"), screen);
        assertFalse(screen.contains("remaining"), screen);
        assertFalse(screen.contains("\n\n"), "and no blank line where the row used to be: " + screen);
    }

    @Test
    @DisplayName("a temporary ban says how long it was and how long is left")
    void temporaryBan() {
        ActivePunishment ban = ban(Instant.ofEpochMilli(NOW + 7L * 86400_000L).toString());
        ban.durationSeconds = Long.valueOf(7 * 86400L);

        String screen = plain(ban, settings(Payload.builder().build()));

        assertTrue(screen.contains("Temporary ban"), screen);
        assertTrue(screen.contains("Length"), screen);
        assertTrue(screen.contains("7d"), screen);
        assertTrue(screen.contains("remaining"), screen);
    }

    @Test
    @DisplayName("the length the bot sent wins over the one the dates imply")
    void sentLengthWinsOverTheDerivedOne() {
        ActivePunishment ban = ban(Instant.ofEpochMilli(NOW + 3600_000L).toString());
        ban.issuedAt = Instant.ofEpochMilli(NOW).toString();
        ban.durationSeconds = Long.valueOf(7 * 86400L);

        assertTrue(plain(ban, settings(Payload.builder().build())).contains("7d"),
                "a row whose expiry was edited still knows what it was set for");

        ban.durationSeconds = null;
        assertTrue(plain(ban, settings(Payload.builder().build())).contains("1h"),
                "and without it, the dates are the fallback rather than nothing");
    }

    @Test
    @DisplayName("no appeal form means no appeal line, rather than a dangling sentence")
    void noAppealUrl() {
        String screen = plain(ban(null), settings(Payload.builder().build()));

        assertFalse(screen.contains("appeal"), screen);
    }

    @Test
    @DisplayName("an unattributed punishment says Console and keeps the row")
    void consoleIssuer() {
        ActivePunishment ban = ban(null);
        ban.issuedByName = null;

        assertTrue(plain(ban, settings(Payload.builder().build())).contains("Console"));
    }

    @Test
    @DisplayName("a reason nobody gave takes its row with it")
    void noReason() {
        ActivePunishment ban = ban(null);
        ban.reason = "";

        String screen = plain(ban, settings(Payload.builder().build()));
        assertFalse(screen.contains("Reason"), screen);
    }

    @Test
    @DisplayName("a guild name cannot inject formatting, because it arrives from the bot verbatim")
    void serverNameIsNotTrusted() {
        String screen = plain(ban(null), settings(Payload.builder()
                .put("serverName", "<red><obfuscated>x")
                .build()));

        assertFalse(screen.contains("<red>"), screen);
        assertFalse(screen.contains("<obfuscated>"), screen);
        assertTrue(screen.contains("x Punishments"), screen);
    }

    @Test
    @DisplayName("neither can a reason, in either spelling")
    void reasonIsNotTrusted() {
        ActivePunishment ban = ban(null);
        ban.reason = "<red>§cvery bad";

        String screen = plain(ban, settings(Payload.builder().build()));
        assertFalse(screen.contains("<red>"), screen);
        assertFalse(screen.contains("§c"), screen);
        assertTrue(screen.contains("very bad"), screen);
    }

    @Test
    @DisplayName("a mute shows when it expires, and a permanent one does not")
    void muteScreens() {
        ActivePunishment mute = ban(Instant.ofEpochMilli(NOW + 3600_000L).toString());
        mute.type = "mute";
        String temporary = plain(mute, settings(Payload.builder().build()));
        assertTrue(temporary.contains("Temporary mute"), temporary);
        assertTrue(temporary.contains("Expires"), temporary);

        mute.expiresAt = null;
        String permanent = plain(mute, settings(Payload.builder().build()));
        assertTrue(permanent.contains("Permanent mute"), permanent);
        assertFalse(permanent.contains("Expires"), permanent);
    }

    @Test
    @DisplayName("a country ban and a subnet ban are shown the ban screen, as an IP ban")
    void addressBansUseTheBanScreen() {
        ActivePunishment geo = ban(null);
        geo.type = "geo";

        String screen = plain(geo, settings(Payload.builder().build()));
        assertTrue(screen.contains("Permanent IP ban"), screen);
    }

    @Test
    @DisplayName("a kick reads as a kick and a warning as a warning")
    void kickAndWarn() {
        ActivePunishment kick = ban(null);
        kick.type = "kick";
        assertTrue(plain(kick, settings(Payload.builder().build())).contains("Kick"));

        ActivePunishment warn = ban(null);
        warn.type = "warn";
        assertTrue(plain(warn, settings(Payload.builder().build())).contains("Warning"));
    }

    @Test
    @DisplayName("a guild that cleared the base gets a screen with no header")
    void emptyBaseRendersNothing() {
        String screen = plain(ban(null), settings(Payload.builder()
                .put("screenBase", "")
                .put("serverName", "Bifrost")
                .build()));

        assertFalse(screen.contains("Bifrost"), screen);
        assertFalse(screen.startsWith("\n"), "and no blank line where the base was: " + screen);
        assertTrue(screen.startsWith("Punishment"), screen);
    }

    @Test
    @DisplayName("a guild that wrote a permanent variant gets it, and only when it applies")
    void permanentVariant() {
        Payload config = Payload.builder()
                .put("banPermanentScreen", "<red>Gone for good.</red>")
                .build();

        assertEquals("Gone for good.", plain(ban(null), settings(config)));
        assertFalse(plain(ban(Instant.ofEpochMilli(NOW + 3600_000L).toString()), settings(config))
                .contains("Gone for good"), "a temporary ban still gets the temporary screen");
    }

    @Test
    @DisplayName("an older bot that sends no screens at all still gets the shared defaults")
    void defaultsWithoutAPush() {
        PunishmentSettings settings = settings(Payload.builder().build());

        assertEquals(PunishmentScreens.BASE, settings.screenBase);
        assertEquals(PunishmentScreens.BAN, settings.banScreen);
        assertEquals(PunishmentScreens.MUTE, settings.muteScreen);
        assertEquals(PunishmentScreens.KICK, settings.kickScreen);
        assertEquals(PunishmentScreens.WARN, settings.warnScreen);
        assertEquals("", settings.banPermanentScreen);
        assertEquals("", settings.mutePermanentScreen);
        assertEquals(PunishmentScreens.ANNOUNCE_ISSUE, settings.announceIssue);
        assertEquals(PunishmentScreens.ANNOUNCE_REVOKE, settings.announceRevoke);
        assertEquals("", settings.serverName);
    }

    @Test
    @DisplayName("an explicitly empty screen is a decision, not a missing key")
    void emptyIsNotAbsent() {
        PunishmentSettings settings = settings(Payload.builder()
                .put("banScreen", "")
                .build());

        assertEquals("", settings.banScreen);
        assertEquals(PunishmentScreens.MUTE, settings.muteScreen, "the others are still defaulted");
    }

    private static ActivePunishment ban(String expiresAt) {
        ActivePunishment punishment = new ActivePunishment();
        punishment.id = "ban-1";
        punishment.type = "ban";
        punishment.targetUuid = "11111111-1111-1111-1111-111111111111";
        punishment.targetName = "Steve";
        punishment.reason = "griefing";
        punishment.issuedByName = "Adam";
        punishment.issuedAt = Instant.ofEpochMilli(NOW).toString();
        punishment.expiresAt = expiresAt;
        return punishment;
    }

    private static PunishmentSettings settings(Payload extra) {
        Payload.Builder base = Payload.builder().put("mode", "replace");
        for (String key : extra.keys()) {
            base.put(key, extra.string(key, ""));
        }
        return PunishmentSettings.from(ModuleConfig.of(true, base.build()));
    }

    private static String plain(ActivePunishment punishment, PunishmentSettings settings) {
        return TestText.plain(HeimdallPunishmentsModule.render(punishment, settings));
    }
}
