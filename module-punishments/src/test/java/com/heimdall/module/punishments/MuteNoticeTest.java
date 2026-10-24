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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A muted player sees the full mute screen once per mute, then one short line for every message
 * they try to send. Every refused message used to repeat the whole screen.
 */
class MuteNoticeTest {

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

    private static String text(Verdict verdict) {
        return TestText.plain(verdict.reason());
    }

    @Test
    @DisplayName("the screen is shown when the mute lands, then refused messages get one line")
    void screenOnceThenReminder() {
        try (PunishmentsHarness harness = new PunishmentsHarness(dataDir, ServerRole.ENFORCER)
                .enableReplace()) {
            FakePlayer steve = harness.platform.join(FakePlayer.named("Steve"));

            harness.tunnel.push("punish.apply", muteFrame(steve, "64b0000000000000000000c1"));
            assertEquals(1, steve.messages().size(), steve.messageText().toString());
            assertTrue(steve.messageText().get(0).contains("spam"), "the full screen, with the reason");

            Verdict chat = harness.chatPipeline.dispatch(ChatMessage.of(steve.uuid(), "Steve", "hi"));
            assertTrue(chat.isDeny());
            assertTrue(text(chat).startsWith("You are muted for another"), text(chat));
            Verdict msg = harness.commandPipeline.dispatch(
                    CommandAttempt.of(steve.uuid(), "Steve", "/msg Alex hi"));
            assertTrue(text(msg).startsWith("You are muted"), text(msg));

            // The same mute arriving again (a resync, a second frame) shows nothing new.
            harness.tunnel.push("punish.apply", muteFrame(steve, "64b0000000000000000000c1"));
            assertEquals(1, steve.messages().size());

            // A new mute is news again.
            harness.tunnel.push("punish.apply", muteFrame(steve, "64b0000000000000000000c2"));
            assertEquals(2, steve.messages().size());
        }
    }

    @Test
    @DisplayName("a player muted while away sees the screen at their first refused message")
    void screenAtFirstMessageWhenMutedOffline() {
        try (PunishmentsHarness harness = new PunishmentsHarness(dataDir, ServerRole.STANDALONE)
                .enableReplace()) {
            FakePlayer steve = FakePlayer.named("Steve");
            harness.tunnel.push("punish.apply", muteFrame(steve, "64b0000000000000000000c3"));
            harness.platform.join(steve);

            Verdict first = harness.chatPipeline.dispatch(ChatMessage.of(steve.uuid(), "Steve", "hi"));
            assertTrue(text(first).contains("spam"), text(first));
            Verdict second = harness.chatPipeline.dispatch(ChatMessage.of(steve.uuid(), "Steve", "hi"));
            assertTrue(text(second).startsWith("You are muted for another"), text(second));
        }
    }

    @Test
    @DisplayName("a proxy never tells the player: it cannot refuse their chat")
    void proxyStaysQuiet() {
        try (PunishmentsHarness harness = new PunishmentsHarness(dataDir, ServerRole.GATEKEEPER)
                .enableReplace()) {
            FakePlayer steve = harness.platform.join(FakePlayer.named("Steve"));
            harness.tunnel.push("punish.apply", muteFrame(steve, "64b0000000000000000000c4"));
            assertEquals(0, steve.messages().size(), steve.messageText().toString());
        }
    }
}
