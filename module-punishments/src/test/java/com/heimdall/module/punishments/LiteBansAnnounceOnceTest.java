package com.heimdall.module.punishments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.config.ServerRole;
import com.heimdall.core.json.Payload;
import com.heimdall.core.log.RecordingLogger;
import com.heimdall.core.testing.FakeCommandSource;
import com.heimdall.core.testing.FakePlayer;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * One chat line per punishment when LiteBans is the punishment system.
 *
 * <p>Reported 2026-10-16: a Skript ran {@code mute <player> 1m ...} on a Paper backend in
 * offenses-only mode. LiteBans broadcast it; the bridge posted it to the bot as {@code hook}; the
 * bot fanned {@code punish.apply} out to the Velocity proxy; and the proxy announced it again.
 * Every LiteBans punishment and every LiteBans lift was announced twice.
 *
 * <p>The fix keys on where the punishment came from, not on the mode, because a dashboard
 * punishment arrives on the same frame in the same mode and LiteBans never saw that one.
 */
class LiteBansAnnounceOnceTest {

    @TempDir
    Path dataDir;

    // ── issue ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("offenses-only: a hook-sourced mute is not announced and is still mirrored")
    void hookMuteIsNotAnnouncedAgain() {
        try (PunishmentsHarness harness = proxy("offenses-only")) {
            FakePlayer bystander = harness.platform.join(FakePlayer.named("Notch"));

            harness.tunnel.push("punish.apply", apply("mute", "Steve", "hook"));

            assertTrue(bystander.messageText().isEmpty(),
                    "LiteBans already broadcast this one: " + bystander.messageText());
            assertNotNull(harness.module.mirrorForTest().get("mute:" + uuidOf("Steve")),
                    "only the chat line is skipped; the mirror write is unchanged");
        }
    }

    @Test
    @DisplayName("offenses-only: a hook-sourced ban and kick are not announced, and still land")
    void hookBanAndKickAreNotAnnouncedAgain() {
        try (PunishmentsHarness harness = proxy("offenses-only")) {
            FakePlayer bystander = harness.platform.join(FakePlayer.named("Notch"));
            FakePlayer alex = harness.platform.join(FakePlayer.named("Alex"));

            harness.tunnel.push("punish.apply", apply("ban", "Steve", "hook"));
            harness.tunnel.push("punish.apply", apply("kick", "Alex", "hook"));

            assertTrue(bystander.messageText().isEmpty(), bystander.messageText().toString());
            assertNotNull(harness.module.mirrorForTest().get("ban:" + uuidOf("Steve")));
            assertEquals(1, alex.kickReasons().size(),
                    "applyLive is untouched: the kick still lands, only the line is gone");
        }
    }

    @Test
    @DisplayName("offenses-only: a dashboard punishment is announced exactly once")
    void dashboardPunishmentStillAnnounces() {
        try (PunishmentsHarness harness = proxy("offenses-only")) {
            FakePlayer bystander = harness.platform.join(FakePlayer.named("Notch"));

            harness.tunnel.push("punish.apply", apply("mute", "Steve", "dashboard"));

            assertEquals(1, linesTo(bystander),
                    "LiteBans never saw a dashboard punishment, so this is the only line: "
                            + bystander.messageText());
            assertTrue(told(bystander, "muted"));
        }
    }

    @Test
    @DisplayName("every source other than hook announces once, in every mode")
    void everyOtherSourceAnnounces() {
        for (String mode : Arrays.asList("offenses-only", "hook", "replace")) {
            for (String source : Arrays.asList("command", "dashboard", "discord", "offend")) {
                try (PunishmentsHarness harness = proxy(mode)) {
                    FakePlayer bystander = harness.platform.join(FakePlayer.named("Notch"));

                    harness.tunnel.push("punish.apply", apply("ban", "Steve", source));

                    assertEquals(1, linesTo(bystander), mode + "/" + source
                            + ": LiteBans never saw this one: " + bystander.messageText());
                }
            }
        }
    }

    @Test
    @DisplayName("a hook-sourced frame is quiet in replace mode too, since LiteBans still ran it")
    void hookIsQuietInEveryMode() {
        try (PunishmentsHarness harness = proxy("replace")) {
            FakePlayer bystander = harness.platform.join(FakePlayer.named("Notch"));

            harness.tunnel.push("punish.apply", apply("mute", "Steve", "hook"));

            assertTrue(bystander.messageText().isEmpty(), bystander.messageText().toString());
        }
    }

    @Test
    @DisplayName("a frame from an older bot, with no source, falls back to the mode")
    void olderBotFallsBackToTheMode() {
        try (PunishmentsHarness harness = proxy("offenses-only")) {
            FakePlayer bystander = harness.platform.join(FakePlayer.named("Notch"));
            harness.tunnel.push("punish.apply", apply("mute", "Steve", null));
            assertTrue(bystander.messageText().isEmpty(),
                    "outside replace mode an unlabelled frame is almost always LiteBans': "
                            + bystander.messageText());
            assertNotNull(harness.module.mirrorForTest().get("mute:" + uuidOf("Steve")));
        }
        try (PunishmentsHarness harness = proxy("replace")) {
            FakePlayer bystander = harness.platform.join(FakePlayer.named("Notch"));
            harness.tunnel.push("punish.apply", apply("mute", "Steve", null));
            assertEquals(1, linesTo(bystander), "replace mode behaves as it always did");
        }
    }

    @Test
    @DisplayName("replace: a native issue announces once, and its echo is still suppressed")
    void replaceNativeIssueAnnouncesOnce() {
        try (PunishmentsHarness harness = standalone("replace")) {
            FakePlayer bystander = harness.platform.join(FakePlayer.named("Notch"));
            harness.platform.join(FakePlayer.named("Steve"));

            harness.module.onStaffCommand(
                    FakeCommandSource.console(), "ban", Arrays.asList("Steve", "griefing"));
            assertEquals(1, linesTo(bystander), "the command announced it");

            Payload echo = Payload.builder()
                    .put("id", "srv-1")
                    .put("opId", harness.module.outboxForTest().snapshot().get(0).opId)
                    .put("type", "ban")
                    .put("source", "command")
                    .put("targetUuid", uuidOf("Steve"))
                    .put("targetName", "Steve")
                    .put("reason", "griefing")
                    .build();
            harness.tunnel.push("punish.apply", echo);

            assertEquals(1, linesTo(bystander), "still once: " + bystander.messageText());
            assertNotNull(harness.module.mirrorForTest().get("ban:" + uuidOf("Steve")));
        }
    }

    // ── revoke ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("offenses-only: a LiteBans unmute is not announced again and is still evicted")
    void hookRevokeIsNotAnnouncedAgain() {
        try (PunishmentsHarness harness = proxy("offenses-only")) {
            FakePlayer bystander = harness.platform.join(FakePlayer.named("Notch"));
            inMirror(harness, "mute", "Steve");

            harness.tunnel.push("punish.revoke", revoke("mute", "Steve", "hook"));

            assertTrue(bystander.messageText().isEmpty(),
                    "LiteBans broadcast its own unmute: " + bystander.messageText());
            assertNull(harness.module.mirrorForTest().get("mute:" + uuidOf("Steve")),
                    "the eviction is not conditional; only the chat line is");
        }
    }

    @Test
    @DisplayName("offenses-only: a revoke from Heimdall is announced exactly once")
    void heimdallRevokeStillAnnounces() {
        try (PunishmentsHarness harness = proxy("offenses-only")) {
            FakePlayer bystander = harness.platform.join(FakePlayer.named("Notch"));
            inMirror(harness, "ban", "Steve");

            harness.tunnel.push("punish.revoke", revoke("ban", "Steve", "heimdall"));

            assertEquals(1, linesTo(bystander), bystander.messageText().toString());
            assertTrue(told(bystander, "unbanned"));
        }
    }

    @Test
    @DisplayName("a revoke from an older bot falls back to the mode, as an apply does")
    void olderBotRevokeFallsBackToTheMode() {
        try (PunishmentsHarness harness = proxy("offenses-only")) {
            FakePlayer bystander = harness.platform.join(FakePlayer.named("Notch"));
            inMirror(harness, "ban", "Steve");
            harness.tunnel.push("punish.revoke", revoke("ban", "Steve", null));
            assertTrue(bystander.messageText().isEmpty(), bystander.messageText().toString());
        }
        try (PunishmentsHarness harness = proxy("replace")) {
            FakePlayer bystander = harness.platform.join(FakePlayer.named("Notch"));
            inMirror(harness, "ban", "Steve");
            harness.tunnel.push("punish.revoke", revoke("ban", "Steve", null));
            assertEquals(1, linesTo(bystander), bystander.messageText().toString());
        }
    }

    @Test
    @DisplayName("replace: a native unban announces once, and the bot's revoke frame adds nothing")
    void replaceNativeRevokeAnnouncesOnce() {
        try (PunishmentsHarness harness = standalone("replace")) {
            FakePlayer bystander = harness.platform.join(FakePlayer.named("Notch"));
            harness.platform.join(FakePlayer.named("Steve"));
            harness.module.onStaffCommand(
                    FakeCommandSource.console(), "ban", Arrays.asList("Steve", "griefing"));
            int afterBan = linesTo(bystander);

            harness.module.onStaffCommand(
                    FakeCommandSource.console(), "unban", Arrays.asList("Steve"));
            assertEquals(afterBan + 1, linesTo(bystander), bystander.messageText().toString());

            harness.tunnel.push("punish.revoke", revoke("ban", "Steve", "heimdall"));

            assertEquals(afterBan + 1, linesTo(bystander),
                    "the native unban already evicted the row, so the frame announces nothing: "
                            + bystander.messageText());
        }
    }

    // ── the bridge ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("the bridge labels a LiteBans lift as hook, as it does an issue")
    void bridgeLabelsRevokesAsHook() {
        Payload issue = LiteBansEventBridge.issueBody(
                new LiteBansHookTest.FakeEntry("mute", 1L, 0L), "salt",
                new RecordingLogger());
        Payload revoke = LiteBansEventBridge.revokeBody(
                new LiteBansHookTest.FakeEntry("mute", 1L, 0L), "salt");

        assertNotNull(issue);
        assertNotNull(revoke);
        assertEquals("hook", issue.string("source", ""));
        assertEquals("hook", revoke.string("source", ""));
        assertEquals("mute", revoke.string("type", ""));
        assertEquals("Adam", revoke.string("revokedBy", ""));
        assertFalse(revoke.string("targetUuid", "").isEmpty());
        assertNull(LiteBansEventBridge.revokeBody(
                new LiteBansHookTest.FakeEntry("note", 1L, 0L), "salt"));
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    /** A Velocity proxy in front of backends: the instance that announces. */
    private PunishmentsHarness proxy(String mode) {
        return harness(mode, ServerRole.GATEKEEPER);
    }

    private PunishmentsHarness standalone(String mode) {
        return harness(mode, ServerRole.STANDALONE);
    }

    private PunishmentsHarness harness(String mode, ServerRole role) {
        return new PunishmentsHarness(dataDir, role).enableWith(Payload.builder()
                .put("mode", mode)
                .put("ipSalt", "salt")
                .put("rootAliases", false)
                .put("silentByDefault", false)
                .build());
    }

    private static Payload apply(String type, String target, String source) {
        Payload.Builder frame = Payload.builder()
                .put("id", "srv-" + target)
                .put("type", type)
                .put("targetUuid", uuidOf(target))
                .put("targetName", target)
                .put("reason", "Lost at /potato")
                .put("issuedByName", "Console");
        if (source != null) frame.put("source", source);
        return frame.build();
    }

    private static Payload revoke(String type, String target, String revokeSource) {
        Payload.Builder frame = Payload.builder()
                .put("type", type)
                .put("targetUuid", uuidOf(target))
                .put("revokeCause", "manual")
                .put("revokedBy", "Console");
        if (revokeSource != null) frame.put("revokeSource", revokeSource);
        return frame.build();
    }

    private static void inMirror(PunishmentsHarness harness, String type, String target) {
        ActivePunishment p = new ActivePunishment();
        p.type = type;
        p.targetUuid = uuidOf(target);
        p.targetName = target;
        p.reason = "Lost at /potato";
        harness.module.mirrorForTest().record(type + ":" + p.targetUuid, p);
    }

    private static int linesTo(FakePlayer player) {
        return player.messageText().size();
    }

    private static String uuidOf(String name) {
        return FakePlayer.named(name).uuid().toString();
    }

    private static boolean told(FakePlayer player, String needle) {
        List<String> lines = player.messageText();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(needle)) return true;
        }
        return false;
    }
}
