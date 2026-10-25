package com.heimdall.module.punishments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.config.ServerRole;
import com.heimdall.core.json.Payload;
import com.heimdall.core.pipeline.ChatMessage;
import com.heimdall.core.pipeline.CommandAttempt;
import com.heimdall.core.pipeline.Verdict;
import com.heimdall.core.punish.HiddenPunishments;
import com.heimdall.core.testing.FakeCommandSource;
import com.heimdall.core.testing.FakePlayer;
import com.heimdall.core.testing.TestText;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Staff who opt in are told when a muted player tries to talk, and staff who may also see the
 * words get a second line with them.
 */
class MutedChatAlertsTest {

    @TempDir
    Path dataDir;

    private static Payload muteFrame(FakePlayer player, String id) {
        return muteFrame(player, id, false);
    }

    private static Payload muteFrame(FakePlayer player, String id, boolean hidden) {
        return Payload.builder()
                .put("id", id)
                .put("type", "mute")
                .put("targetUuid", player.uuid().toString())
                .put("targetName", player.name())
                .put("reason", "spam")
                .put("source", "dashboard")
                .put("silent", true)
                .put("hidden", hidden)
                .put("expiresAt", java.time.Instant.now().plusSeconds(600).toString())
                .build();
    }

    private static Verdict chat(PunishmentsHarness harness, FakePlayer player, String text) {
        return harness.chatPipeline.dispatch(ChatMessage.of(player.uuid(), player.name(), text));
    }

    @Test
    @DisplayName("the alert goes to holders of the node, the words only to holders of both")
    void alertAndContentAreSeparatelyGated() {
        try (PunishmentsHarness harness = new PunishmentsHarness(dataDir, ServerRole.STANDALONE)
                .enableReplace()) {
            FakePlayer steve = harness.platform.join(
                    FakePlayer.named("Steve").grant(MutedChatAlerts.PERMISSION));
            FakePlayer mod = harness.platform.join(
                    FakePlayer.named("Mod").grant(MutedChatAlerts.PERMISSION));
            FakePlayer admin = harness.platform.join(FakePlayer.named("Admin")
                    .grant(MutedChatAlerts.PERMISSION).grant(MutedChatAlerts.CONTENT_PERMISSION));
            FakePlayer helper = harness.platform.join(FakePlayer.named("Helper")
                    .grant(MutedChatAlerts.CONTENT_PERMISSION)
                    .grant("heimdall.punishments.notify").grant("heimdall.admin"));
            harness.tunnel.push("punish.apply", muteFrame(steve, "64b0000000000000000000d1"));
            int steveBefore = steve.messages().size();
            // The silent mute's own announcement reaches holders of the notify node.
            int helperBefore = helper.messages().size();

            assertTrue(chat(harness, steve, "hello everyone").isDeny());

            assertEquals(1, mod.messages().size(), mod.messageText().toString());
            assertEquals("[Heimdall] Steve tried to speak while muted", mod.messageText().get(0));
            assertEquals(2, admin.messages().size(), admin.messageText().toString());
            assertEquals("  \"hello everyone\"", admin.messageText().get(1));
            assertEquals(helperBefore, helper.messages().size(), "the alert is opt-in, admin or not");
            assertEquals(steveBefore, steve.messages().size(), "the player is never told about themselves");
        }
    }

    @Test
    @DisplayName("a burst alerts once, and the next alert counts what it folded in")
    void throttledPerPlayer() {
        MutedChatAlerts alerts = new MutedChatAlerts();
        UUID steve = UUID.randomUUID();
        UUID alex = UUID.randomUUID();
        assertEquals(0, alerts.admit(steve, "m1", 0));
        assertEquals(-1, alerts.admit(steve, "m1", 1_000));
        assertEquals(-1, alerts.admit(steve, "m1", 29_999));
        assertEquals(0, alerts.admit(alex, "m2", 2_000), "a window is per player");
        assertEquals(2, alerts.admit(steve, "m1", 30_000));
        assertEquals(-1, alerts.admit(steve, "m1", 30_001));
        alerts.forget(steve);
        assertEquals(0, alerts.admit(steve, "m1", 30_002), "a forgotten player alerts at once");
        assertEquals(-1, alerts.admit(steve, "m1", 30_003));
        assertEquals(0, alerts.admit(steve, "m3", 30_004),
                "a new mute alerts at once, and carries nothing folded under the old one");
        assertTrue(TestText.plain(MutedChatAlerts.alert("Steve", false, false, 2)).endsWith("(2 more since)"));

        try (PunishmentsHarness harness = new PunishmentsHarness(dataDir, ServerRole.STANDALONE)
                .enableReplace()) {
            FakePlayer player = harness.platform.join(FakePlayer.named("Steve"));
            FakePlayer mod = harness.platform.join(
                    FakePlayer.named("Mod").grant(MutedChatAlerts.PERMISSION));
            harness.tunnel.push("punish.apply", muteFrame(player, "64b0000000000000000000d2"));
            for (int i = 0; i < 5; i++) chat(harness, player, "spam " + i);
            assertEquals(1, mod.messages().size(), mod.messageText().toString());
        }
    }

    @Test
    @DisplayName("a blocked command alerts with the command as typed; an allowed one does not")
    void blockedCommands() {
        try (PunishmentsHarness harness = new PunishmentsHarness(dataDir, ServerRole.STANDALONE)
                .enableReplace()) {
            FakePlayer steve = harness.platform.join(FakePlayer.named("Steve"));
            FakePlayer mod = harness.platform.join(FakePlayer.named("Mod")
                    .grant(MutedChatAlerts.PERMISSION).grant(MutedChatAlerts.CONTENT_PERMISSION));
            harness.tunnel.push("punish.apply", muteFrame(steve, "64b0000000000000000000d3"));

            harness.commandPipeline.dispatch(CommandAttempt.of(steve.uuid(), "Steve", "/spawn"));
            assertEquals(0, mod.messages().size(), "an allowed command is not an attempt to talk");

            harness.commandPipeline.dispatch(CommandAttempt.of(steve.uuid(), "Steve", "/msg Alex hi"));
            assertEquals("[Heimdall] Steve tried to use a blocked command while muted",
                    mod.messageText().get(0));
            assertEquals("  /msg Alex hi", mod.messageText().get(1));
        }
    }

    @Test
    @DisplayName("what was said is plain text: a player cannot format staff chat")
    void contentIsPlainText() {
        try (PunishmentsHarness harness = new PunishmentsHarness(dataDir, ServerRole.STANDALONE)
                .enableReplace()) {
            FakePlayer steve = harness.platform.join(FakePlayer.named("Steve"));
            FakePlayer mod = harness.platform.join(FakePlayer.named("Mod")
                    .grant(MutedChatAlerts.PERMISSION).grant(MutedChatAlerts.CONTENT_PERMISSION));
            harness.tunnel.push("punish.apply", muteFrame(steve, "64b0000000000000000000d4"));
            chat(harness, steve, "<click:run_command:'/op Steve'>free diamonds</click>");
            assertEquals("  \"<click:run_command:'/op Steve'>free diamonds</click>\"",
                    mod.messageText().get(1));
            assertEquals(null, mod.messages().get(1).clickEvent());
        }
        StringBuilder longLine = new StringBuilder();
        for (int i = 0; i < 400; i++) longLine.append('a');
        String shown = TestText.plain(MutedChatAlerts.content(longLine.toString(), true));
        assertEquals(2 + MutedChatAlerts.MAX_SAID_CHARS + 3, shown.length());
    }

    @Test
    @DisplayName("a proxy, hook mode and a chat-plugin channel line never alert")
    void quietWhereHeimdallDoesNotRefuse() {
        try (PunishmentsHarness harness = new PunishmentsHarness(dataDir, ServerRole.GATEKEEPER)
                .enableReplace()) {
            FakePlayer steve = harness.platform.join(FakePlayer.named("Steve"));
            FakePlayer mod = harness.platform.join(
                    FakePlayer.named("Mod").grant(MutedChatAlerts.PERMISSION));
            harness.tunnel.push("punish.apply", muteFrame(steve, "64b0000000000000000000d5"));
            chat(harness, steve, "hi");
            assertEquals(0, mod.messages().size(), "the backend that refuses it tells its staff");
        }
        try (PunishmentsHarness harness = new PunishmentsHarness(dataDir, ServerRole.STANDALONE)) {
            harness.enableWith(Payload.builder().put("mode", "hook").put("ipSalt", "s").build());
            FakePlayer steve = harness.platform.join(FakePlayer.named("Steve"));
            FakePlayer mod = harness.platform.join(
                    FakePlayer.named("Mod").grant(MutedChatAlerts.PERMISSION));
            harness.tunnel.push("punish.apply", muteFrame(steve, "64b0000000000000000000d6"));
            chat(harness, steve, "hi");
            assertEquals(0, mod.messages().size(), "LiteBans refuses it in hook mode");
        }
        try (PunishmentsHarness harness = new PunishmentsHarness(dataDir, ServerRole.STANDALONE)
                .enableReplace()) {
            FakePlayer steve = harness.platform.join(FakePlayer.named("Steve"));
            FakePlayer mod = harness.platform.join(
                    FakePlayer.named("Mod").grant(MutedChatAlerts.PERMISSION));
            harness.tunnel.push("punish.apply", muteFrame(steve, "64b0000000000000000000d7"));
            harness.chatPipeline.dispatch(
                    ChatMessage.inChannel(steve.uuid(), "Steve", "hi", "global"));
            assertEquals(0, mod.messages().size(), "the line was not refused here");
        }
    }

    @Test
    @DisplayName("leaving and having the mute lifted both reset the throttle")
    void quitAndRevokeReset() {
        try (PunishmentsHarness harness = new PunishmentsHarness(dataDir, ServerRole.STANDALONE)
                .enableReplace()) {
            FakePlayer steve = harness.platform.join(FakePlayer.named("Steve"));
            FakePlayer mod = harness.platform.join(
                    FakePlayer.named("Mod").grant(MutedChatAlerts.PERMISSION));
            harness.tunnel.push("punish.apply", muteFrame(steve, "64b0000000000000000000d8"));
            chat(harness, steve, "one");
            harness.sessions.quit(steve, System.currentTimeMillis());
            harness.platform.leave(steve);
            harness.platform.join(steve);
            chat(harness, steve, "two");
            assertEquals(2, mod.messages().size(), mod.messageText().toString());

            harness.tunnel.push("punish.revoke", Payload.builder()
                    .put("type", "mute")
                    .put("targetUuid", steve.uuid().toString())
                    .build());
            harness.tunnel.push("punish.apply", muteFrame(steve, "64b0000000000000000000d9"));
            chat(harness, steve, "three");
            assertEquals(3, mod.messages().size(), mod.messageText().toString());
        }
    }

    @Test
    @DisplayName("the reminder line is the guild's, and a blank one is the default")
    void reminderFromSettings() {
        try (PunishmentsHarness harness = new PunishmentsHarness(dataDir, ServerRole.STANDALONE)) {
            harness.enableWith(Payload.builder()
                    .put("mode", "replace")
                    .put("ipSalt", "s")
                    .put("muteReminderScreen", "<gray>Shh[, {remaining} to go]</gray>")
                    .build());
            FakePlayer steve = harness.platform.join(FakePlayer.named("Steve"));
            harness.tunnel.push("punish.apply", muteFrame(steve, "64b0000000000000000000da"));
            String line = TestText.plain(chat(harness, steve, "hi").reason());
            assertTrue(line.startsWith("Shh, ") && line.endsWith(" to go"), line);
        }
        try (PunishmentsHarness harness = new PunishmentsHarness(dataDir, ServerRole.STANDALONE)) {
            harness.enableWith(Payload.builder()
                    .put("mode", "replace")
                    .put("ipSalt", "s")
                    .put("muteReminderScreen", "  ")
                    .build());
            FakePlayer steve = harness.platform.join(FakePlayer.named("Steve"));
            harness.tunnel.push("punish.apply", muteFrame(steve, "64b0000000000000000000db"));
            String line = TestText.plain(chat(harness, steve, "hi").reason());
            assertTrue(line.startsWith("You are muted for another"), line);
        }
    }

    @Test
    @DisplayName("a hidden mute alerts only staff who may see hidden punishments")
    void hiddenMuteNarrowsTheAudience() {
        try (PunishmentsHarness harness = new PunishmentsHarness(dataDir, ServerRole.STANDALONE)
                .enableReplace()) {
            FakePlayer steve = harness.platform.join(FakePlayer.named("Steve"));
            FakePlayer mod = harness.platform.join(FakePlayer.named("Mod")
                    .grant(MutedChatAlerts.PERMISSION).grant(MutedChatAlerts.CONTENT_PERMISSION));
            FakePlayer lead = harness.platform.join(FakePlayer.named("Lead")
                    .grant(MutedChatAlerts.PERMISSION).grant(MutedChatAlerts.CONTENT_PERMISSION)
                    .grant(HiddenPunishments.PERMISSION));
            FakePlayer keeper = harness.platform.join(
                    FakePlayer.named("Keeper").grant(HiddenPunishments.PERMISSION));
            harness.tunnel.push("punish.apply", muteFrame(steve, "64b0000000000000000000dc", true));
            int modBefore = mod.messages().size();
            int leadBefore = lead.messages().size();
            int keeperBefore = keeper.messages().size();

            chat(harness, steve, "hello");

            assertEquals(modBefore, mod.messages().size(), mod.messageText().toString());
            assertEquals(leadBefore + 2, lead.messages().size(), lead.messageText().toString());
            assertEquals("[Heimdall] (hidden) Steve tried to speak while muted",
                    lead.messageText().get(leadBefore));
            assertEquals(keeperBefore, keeper.messages().size(), "the alert stays opt-in");
        }
    }

    @Test
    @DisplayName("a frozen player is refused as frozen, not reported as muted")
    void frozenDoesNotAlert() {
        try (PunishmentsHarness harness = new PunishmentsHarness(dataDir, ServerRole.STANDALONE)
                .enableReplace()) {
            FakePlayer steve = harness.platform.join(FakePlayer.named("Steve"));
            FakePlayer mod = harness.platform.join(
                    FakePlayer.named("Mod").grant(MutedChatAlerts.PERMISSION));
            harness.tunnel.push("punish.apply", muteFrame(steve, "64b0000000000000000000dd"));
            ActivePunishment freeze = new ActivePunishment();
            freeze.id = "freeze-1";
            freeze.type = "freeze";
            freeze.targetUuid = steve.uuid().toString();
            freeze.targetName = "Steve";
            harness.module.mirrorForTest().record("freeze:" + steve.uuid(), freeze);
            assertTrue(chat(harness, steve, "hi").isDeny());
            harness.commandPipeline.dispatch(CommandAttempt.of(steve.uuid(), "Steve", "/msg A hi"));
            assertEquals(0, mod.messages().size(), mod.messageText().toString());
        }
    }

    @Test
    @DisplayName("an unmute issued here resets the throttle without waiting for the bot")
    void localUnmuteResets() {
        try (PunishmentsHarness harness = new PunishmentsHarness(dataDir, ServerRole.STANDALONE)
                .enableReplace()) {
            FakePlayer steve = harness.platform.join(FakePlayer.named("Steve"));
            FakePlayer mod = harness.platform.join(
                    FakePlayer.named("Mod").grant(MutedChatAlerts.PERMISSION));
            harness.tunnel.push("punish.apply", muteFrame(steve, "64b0000000000000000000de"));
            chat(harness, steve, "one");
            harness.module.onStaffCommand(FakeCommandSource.console(), "unmute",
                    Arrays.asList("Steve"));
            harness.tunnel.push("punish.apply", muteFrame(steve, "64b0000000000000000000df"));
            int before = mod.messages().size();
            chat(harness, steve, "two");
            assertEquals(before + 1, mod.messages().size(), mod.messageText().toString());
        }
    }

    @Test
    @DisplayName("attempts under a hidden mute never surface in a later mute's count")
    void hiddenCountDoesNotCarryOver() {
        MutedChatAlerts alerts = new MutedChatAlerts();
        FakePlayer steve = FakePlayer.named("Steve");
        FakePlayer mod = FakePlayer.named("Mod").grant(MutedChatAlerts.PERMISSION);
        java.util.List<com.heimdall.core.platform.PlayerHandle> online =
                Arrays.<com.heimdall.core.platform.PlayerHandle>asList(steve, mod);
        for (int i = 0; i < 5; i++) {
            alerts.raise(() -> online, steve.uuid(), "Steve", "hidden-1", false, true, "hi", i);
        }
        assertEquals(0, mod.messages().size(), "the hidden mute is kept from Mod");
        alerts.raise(() -> online, steve.uuid(), "Steve", "open-2", false, false, "hi", 3_600_000);
        assertEquals("[Heimdall] Steve tried to speak while muted", mod.messageText().get(0));
    }

    @Test
    @DisplayName("an unreadable online list gives the window back")
    void failedRosterGivesTheWindowBack() {
        MutedChatAlerts alerts = new MutedChatAlerts();
        FakePlayer mod = FakePlayer.named("Mod").grant(MutedChatAlerts.PERMISSION);
        UUID steve = UUID.randomUUID();
        alerts.raise(() -> null, steve, "Steve", "m1", false, false, "hi", 0);
        alerts.raise(() -> Arrays.<com.heimdall.core.platform.PlayerHandle>asList(mod),
                steve, "Steve", "m1", false, false, "hi", 1_000);
        assertEquals(1, mod.messages().size(), mod.messageText().toString());
    }
}
