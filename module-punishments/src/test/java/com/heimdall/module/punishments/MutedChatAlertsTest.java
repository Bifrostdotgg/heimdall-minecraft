package com.heimdall.module.punishments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.config.ServerRole;
import com.heimdall.core.json.Payload;
import com.heimdall.core.pipeline.ChatMessage;
import com.heimdall.core.pipeline.CommandAttempt;
import com.heimdall.core.pipeline.Verdict;
import com.heimdall.core.testing.FakePlayer;
import com.heimdall.core.testing.TestText;
import java.nio.file.Path;
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
        return Payload.builder()
                .put("id", id)
                .put("type", "mute")
                .put("targetUuid", player.uuid().toString())
                .put("targetName", player.name())
                .put("reason", "spam")
                .put("source", "dashboard")
                .put("silent", true)
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
        assertEquals(0, alerts.admit(steve, 0));
        assertEquals(-1, alerts.admit(steve, 1_000));
        assertEquals(-1, alerts.admit(steve, 29_999));
        assertEquals(0, alerts.admit(alex, 2_000), "a window is per player");
        assertEquals(2, alerts.admit(steve, 30_000));
        assertEquals(-1, alerts.admit(steve, 30_001));
        alerts.forget(steve);
        assertEquals(0, alerts.admit(steve, 30_002), "a forgotten player alerts at once");
        assertTrue(TestText.plain(MutedChatAlerts.alert("Steve", false, 2)).endsWith("(2 more since)"));

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
            assertEquals(0, mod.messages().size(), "the same line already came through chat");
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
}
