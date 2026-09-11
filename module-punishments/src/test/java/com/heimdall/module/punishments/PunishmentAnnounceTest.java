package com.heimdall.module.punishments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.config.ServerRole;
import com.heimdall.core.json.Payload;
import com.heimdall.core.punish.PunishmentAnnouncement;
import com.heimdall.core.punish.SilenceDecision;
import com.heimdall.core.testing.FakeCommandSource;
import com.heimdall.core.testing.FakePlayer;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Who hears about a punishment, and who is allowed to decide that.
 *
 * <p>Before this, nothing was ever broadcast for any punishment: the {@code silent} flag was
 * parsed, stored on the row and sent to the bot, and then read by nobody. "Silent" was therefore
 * true of every punishment, which is why the flag being ungated had gone unnoticed - it could not
 * change an outcome that did not exist.
 *
 * <p>The wording and the audience rule are unit-tested next door in {@code core}
 * ({@code PunishmentAnnouncementTest}, {@code SilenceDecisionTest}), both without a server. What
 * is tested here is the wiring those two are useless without: that the command path and the
 * tunnel path both reach the broadcast, that the permission is read from the sender, and that a
 * refusal stops the punishment rather than only the announcement.
 */
class PunishmentAnnounceTest {

    @TempDir
    Path dataDir;

    @Test
    @DisplayName("an announced ban reaches every online player")
    void announcedBanReachesEverybody() {
        try (PunishmentsHarness harness = announcing()) {
            FakePlayer steve = harness.platform.join(FakePlayer.named("Steve"));
            FakePlayer bystander = harness.platform.join(FakePlayer.named("Notch"));

            harness.module.onStaffCommand(
                    FakeCommandSource.console(), "ban", Arrays.asList("Steve", "griefing"));

            assertTrue(told(bystander, "banned"), bystander.messageText().toString());
            assertTrue(told(bystander, "Steve"));
            assertTrue(told(bystander, "griefing"));
            assertTrue(told(steve, "banned"),
                    "the punished player is online and hears it like everybody else; their ban "
                            + "screen is a separate thing");
        }
    }

    @Test
    @DisplayName("a silent ban reaches only notify and admin holders")
    void silentBanReachesOnlyStaff() {
        try (PunishmentsHarness harness = announcing()) {
            FakePlayer ordinary = harness.platform.join(FakePlayer.named("Notch"));
            FakePlayer notify = harness.platform.join(
                    FakePlayer.named("Mod").grant(PunishmentAnnouncement.NOTIFY_PERMISSION));
            FakePlayer admin = harness.platform.join(
                    FakePlayer.named("Boss").grant(PunishmentAnnouncement.ADMIN_PERMISSION));
            harness.platform.join(FakePlayer.named("Steve"));

            harness.module.onStaffCommand(
                    FakeCommandSource.console(), "ban", Arrays.asList("Steve", "-s", "griefing"));

            assertTrue(ordinary.messageText().isEmpty(),
                    "an ordinary player must see nothing at all: " + ordinary.messageText());
            assertTrue(told(notify, "(silent)"), notify.messageText().toString());
            assertTrue(told(notify, "banned"));
            assertTrue(told(admin, "(silent)"), "heimdall.admin implies the notify node");
        }
    }

    @Test
    @DisplayName("the guild default applies with no flag, and needs no permission")
    void silentByDefaultNeedsNothing() {
        try (PunishmentsHarness harness = silentByDefault()) {
            FakePlayer ordinary = harness.platform.join(FakePlayer.named("Notch"));
            FakePlayer notify = harness.platform.join(
                    FakePlayer.named("Mod").grant(PunishmentAnnouncement.NOTIFY_PERMISSION));
            harness.platform.join(FakePlayer.named("Steve"));
            FakeCommandSource moderator = FakeCommandSource.player("Adam");

            harness.module.onStaffCommand(moderator, "ban", Arrays.asList("Steve", "griefing"));

            assertFalse(moderator.wasTold(SilenceDecision.REFUSAL_MESSAGE));
            assertTrue(ordinary.messageText().isEmpty());
            assertTrue(told(notify, "(silent)"));
        }
    }

    @Test
    @DisplayName("-s without the override node refuses the command instead of dropping the flag")
    void overrideWithoutPermissionIsRefused() {
        try (PunishmentsHarness harness = announcing()) {
            FakePlayer bystander = harness.platform.join(FakePlayer.named("Notch"));
            harness.platform.join(FakePlayer.named("Steve"));
            FakeCommandSource moderator = FakeCommandSource.player("Adam");

            harness.module.onStaffCommand(
                    moderator, "ban", Arrays.asList("Steve", "-s", "griefing"));

            assertTrue(moderator.wasTold(SilenceDecision.REFUSAL_MESSAGE),
                    moderator.messageText().toString());
            assertTrue(bystander.messageText().isEmpty(), "and nothing was announced either");
            assertNull(harness.module.mirrorForTest().get("ban:" + uuidOf("Steve")),
                    "the punishment itself is refused: running it loudly is exactly what the "
                            + "moderator was trying to avoid");
            assertTrue(harness.module.outboxForTest().isEmpty(), "and nothing is queued for the bot");
        }
    }

    @Test
    @DisplayName("-s with the override node is allowed and silent")
    void overrideWithPermission() {
        try (PunishmentsHarness harness = announcing()) {
            FakePlayer ordinary = harness.platform.join(FakePlayer.named("Notch"));
            FakePlayer notify = harness.platform.join(
                    FakePlayer.named("Mod").grant(PunishmentAnnouncement.NOTIFY_PERMISSION));
            harness.platform.join(FakePlayer.named("Steve"));
            FakeCommandSource moderator =
                    FakeCommandSource.player("Adam").grant(SilenceDecision.OVERRIDE_PERMISSION);

            harness.module.onStaffCommand(
                    moderator, "ban", Arrays.asList("Steve", "-s", "griefing"));

            assertFalse(moderator.wasTold(SilenceDecision.REFUSAL_MESSAGE));
            assertTrue(ordinary.messageText().isEmpty());
            assertTrue(told(notify, "(silent)"));
        }
    }

    @Test
    @DisplayName("-p with the override node makes a silent guild's ban public")
    void publicOverrideOnASilentGuild() {
        try (PunishmentsHarness harness = silentByDefault()) {
            FakePlayer ordinary = harness.platform.join(FakePlayer.named("Notch"));
            harness.platform.join(FakePlayer.named("Steve"));
            FakeCommandSource moderator =
                    FakeCommandSource.player("Adam").grant(SilenceDecision.OVERRIDE_PERMISSION);

            harness.module.onStaffCommand(
                    moderator, "ban", Arrays.asList("Steve", "-p", "griefing"));

            assertTrue(told(ordinary, "banned"), ordinary.messageText().toString());
            assertFalse(told(ordinary, "(silent)"));
        }
    }

    @Test
    @DisplayName("-s on an already-silent guild is a no-op, not a refusal")
    void agreeingFlagNeedsNoPermission() {
        try (PunishmentsHarness harness = silentByDefault()) {
            FakePlayer notify = harness.platform.join(
                    FakePlayer.named("Mod").grant(PunishmentAnnouncement.NOTIFY_PERMISSION));
            harness.platform.join(FakePlayer.named("Steve"));
            FakeCommandSource moderator = FakeCommandSource.player("Adam");

            harness.module.onStaffCommand(
                    moderator, "ban", Arrays.asList("Steve", "-s", "griefing"));

            assertFalse(moderator.wasTold(SilenceDecision.REFUSAL_MESSAGE));
            assertTrue(told(notify, "(silent)"));
        }
    }

    @Test
    @DisplayName("a revoke is announced too, and its flags are gated the same way")
    void revokesAnnounce() {
        try (PunishmentsHarness harness = announcing()) {
            FakePlayer bystander = harness.platform.join(FakePlayer.named("Notch"));
            harness.platform.join(FakePlayer.named("Steve"));
            harness.module.onStaffCommand(
                    FakeCommandSource.console(), "ban", Arrays.asList("Steve", "griefing"));

            harness.module.onStaffCommand(
                    FakeCommandSource.console(), "unban", Arrays.asList("Steve", "served their time"));

            assertTrue(told(bystander, "unbanned"), bystander.messageText().toString());
            assertTrue(told(bystander, "served their time"),
                    "a revoke reason is not a duration, so it must survive the parse intact");

            FakeCommandSource moderator = FakeCommandSource.player("Adam");
            harness.module.onStaffCommand(moderator, "unban", Arrays.asList("Steve", "-s"));
            assertTrue(moderator.wasTold(SilenceDecision.REFUSAL_MESSAGE));
        }
    }

    @Test
    @DisplayName("a punishment issued from Discord is announced here as well")
    void tunnelApplyAnnounces() {
        try (PunishmentsHarness harness = announcing()) {
            FakePlayer bystander = harness.platform.join(FakePlayer.named("Notch"));

            assertEquals(1, harness.tunnel.push("punish.apply", Payload.builder()
                    .put("id", "srv-1")
                    .put("type", "mute")
                    .put("targetUuid", uuidOf("Steve"))
                    .put("targetName", "Steve")
                    .put("reason", "spam")
                    .put("issuedByName", "Adam")
                    .put("silent", false)
                    .build()));

            assertTrue(told(bystander, "Adam"), bystander.messageText().toString());
            assertTrue(told(bystander, "muted"));
            assertTrue(told(bystander, "spam"));
        }
    }

    @Test
    @DisplayName("a silent punishment from Discord stays silent here")
    void tunnelApplyRespectsTheStoredFlag() {
        try (PunishmentsHarness harness = announcing()) {
            FakePlayer ordinary = harness.platform.join(FakePlayer.named("Notch"));
            FakePlayer notify = harness.platform.join(
                    FakePlayer.named("Mod").grant(PunishmentAnnouncement.NOTIFY_PERMISSION));

            harness.tunnel.push("punish.apply", Payload.builder()
                    .put("type", "mute")
                    .put("targetUuid", uuidOf("Steve"))
                    .put("targetName", "Steve")
                    .put("reason", "spam")
                    .put("silent", true)
                    .build());

            assertTrue(ordinary.messageText().isEmpty());
            assertTrue(told(notify, "(silent)"));
            assertTrue(told(notify, "Console"), "no issuer on the row means the console issued it");
        }
    }

    @Test
    @DisplayName("a revoke from Discord announces from the row it lifts")
    void tunnelRevokeAnnounces() {
        try (PunishmentsHarness harness = announcing()) {
            FakePlayer bystander = harness.platform.join(FakePlayer.named("Notch"));
            ActivePunishment ban = new ActivePunishment();
            ban.id = "srv-1";
            ban.type = "ban";
            ban.targetUuid = uuidOf("Steve");
            ban.targetName = "Steve";
            ban.reason = "griefing";
            harness.module.mirrorForTest().record("ban:" + ban.targetUuid, ban);

            harness.tunnel.push("punish.revoke", Payload.builder()
                    .put("type", "ban")
                    .put("targetUuid", ban.targetUuid)
                    .put("revokedBy", "Adam")
                    .build());

            assertNull(harness.module.mirrorForTest().get("ban:" + ban.targetUuid),
                    "the eviction still happens; the announcement is read off the row first");
            assertTrue(told(bystander, "unbanned"), bystander.messageText().toString());
            assertTrue(told(bystander, "Steve"));
        }
    }

    @Test
    @DisplayName("lifting a silent ban is announced as quietly as the ban was")
    void tunnelRevokeInheritsSilence() {
        try (PunishmentsHarness harness = announcing()) {
            FakePlayer ordinary = harness.platform.join(FakePlayer.named("Notch"));
            FakePlayer notify = harness.platform.join(
                    FakePlayer.named("Mod").grant(PunishmentAnnouncement.NOTIFY_PERMISSION));
            ActivePunishment ban = new ActivePunishment();
            ban.type = "ban";
            ban.targetUuid = uuidOf("Steve");
            ban.targetName = "Steve";
            ban.silent = true;
            harness.module.mirrorForTest().record("ban:" + ban.targetUuid, ban);

            harness.tunnel.push("punish.revoke", Payload.builder()
                    .put("type", "ban")
                    .put("targetUuid", ban.targetUuid)
                    .build());

            assertTrue(ordinary.messageText().isEmpty(),
                    "announcing the unban would tell the server about the ban it was hiding");
            assertTrue(told(notify, "(silent)"));
        }
    }

    @Test
    @DisplayName("a ban that names a country announces nothing, because it names no player")
    void geoBansAreNotAnnounced() {
        try (PunishmentsHarness harness = announcing()) {
            FakePlayer bystander = harness.platform.join(FakePlayer.named("Notch"));

            harness.tunnel.push("punish.apply", Payload.builder()
                    .put("type", "geo")
                    .put("country", "DE")
                    .put("reason", "region block")
                    .build());

            assertTrue(bystander.messageText().isEmpty(), bystander.messageText().toString());
        }
    }

    @Test
    @DisplayName("the remaining duration is read off expiresAt, because the wire has no length")
    void remainingDuration() {
        long now = 1_700_000_000_000L;
        assertNull(HeimdallPunishmentsModule.minutesUntil(null, now));
        assertNull(HeimdallPunishmentsModule.minutesUntil("", now));
        assertNull(HeimdallPunishmentsModule.minutesUntil("not an instant", now),
                "an unparseable expiry reads as permanent rather than taking the announcement down");
        assertNull(HeimdallPunishmentsModule.minutesUntil(
                java.time.Instant.ofEpochMilli(now - 1000).toString(), now),
                "already expired is not a duration");
        assertEquals(Integer.valueOf(60), HeimdallPunishmentsModule.minutesUntil(
                java.time.Instant.ofEpochMilli(now + 3_600_000L).toString(), now));
    }

    @Test
    @DisplayName("a backend behind a proxy announces nothing, from either path")
    void enforcerNeverAnnounces() {
        try (PunishmentsHarness harness = harnessWith(false, ServerRole.ENFORCER)) {
            FakePlayer ordinary = harness.platform.join(FakePlayer.named("Notch"));
            FakePlayer notify = harness.platform.join(
                    FakePlayer.named("Mod").grant(PunishmentAnnouncement.NOTIFY_PERMISSION));
            FakePlayer admin = harness.platform.join(
                    FakePlayer.named("Boss").grant(PunishmentAnnouncement.ADMIN_PERMISSION));
            harness.platform.join(FakePlayer.named("Steve"));

            harness.module.onStaffCommand(
                    FakeCommandSource.console(), "mute", Arrays.asList("Steve", "spam"));
            harness.tunnel.push("punish.apply", Payload.builder()
                    .put("type", "ban")
                    .put("targetUuid", uuidOf("Steve"))
                    .put("targetName", "Steve")
                    .put("reason", "griefing")
                    .put("issuedByName", "Adam")
                    .build());

            assertTrue(ordinary.messageText().isEmpty(),
                    "the proxy in front already told everyone, including this player: "
                            + ordinary.messageText());
            assertTrue(notify.messageText().isEmpty(), notify.messageText().toString());
            assertTrue(admin.messageText().isEmpty(),
                    "not even staff: a second copy of a line they already have is not more "
                            + "information");
        }
    }

    @Test
    @DisplayName("a gatekeeper announces, because it is the outermost instance")
    void gatekeeperAnnounces() {
        try (PunishmentsHarness harness = harnessWith(false, ServerRole.GATEKEEPER)) {
            FakePlayer bystander = harness.platform.join(FakePlayer.named("Notch"));

            harness.tunnel.push("punish.apply", Payload.builder()
                    .put("type", "ban")
                    .put("targetUuid", uuidOf("Steve"))
                    .put("targetName", "Steve")
                    .put("reason", "griefing")
                    .put("issuedByName", "Adam")
                    .build());

            assertTrue(told(bystander, "banned"), bystander.messageText().toString());
        }
    }

    @Test
    @DisplayName("an enforcer still applies and records what it does not announce")
    void enforcerStillPunishes() {
        try (PunishmentsHarness harness = harnessWith(false, ServerRole.ENFORCER)) {
            FakePlayer steve = harness.platform.join(FakePlayer.named("Steve"));

            harness.tunnel.push("punish.apply", Payload.builder()
                    .put("type", "kick")
                    .put("targetUuid", steve.uuid().toString())
                    .put("targetName", "Steve")
                    .put("reason", "griefing")
                    .build());

            assertEquals(1, steve.kickReasons().size(),
                    "silence is about the chat line, never about whether the punishment lands");
        }
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private PunishmentsHarness announcing() {
        return harnessWith(false, ServerRole.STANDALONE);
    }

    private PunishmentsHarness silentByDefault() {
        return harnessWith(true, ServerRole.STANDALONE);
    }

    private PunishmentsHarness harnessWith(boolean silentByDefault, ServerRole role) {
        return new PunishmentsHarness(dataDir, role)
                .enableWith(Payload.builder()
                        .put("mode", "replace")
                        .put("ipSalt", "replace-salt")
                        .put("rootAliases", false)
                        .put("silentByDefault", silentByDefault)
                        .build());
    }

    private static String uuidOf(String name) {
        return FakePlayer.named(name).uuid().toString();
    }

    private static boolean told(FakePlayer player, String needle) {
        List<String> lines = player.messageText();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
