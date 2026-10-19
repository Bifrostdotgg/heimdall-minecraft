package com.heimdall.core.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.config.ServerRole;
import com.heimdall.core.http.BedrockIdentity;
import com.heimdall.core.log.RecordingLogger;
import com.heimdall.core.platform.LinkDialogs;
import com.heimdall.core.platform.PlayerHandle;
import com.heimdall.core.testing.FakePlatform;
import com.heimdall.core.testing.FakePlayer;
import com.heimdall.core.testing.MutableClock;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Who gets a dialog, who gets chat, and the rule that the same code is not shown twice in a row.
 *
 * <p>Everything here is decided in core, before any platform is asked, so it is tested against a
 * fake dialog that records what it was offered rather than against a server.
 */
class LinkPrompterTest {

    private final RecordingLogger logger = new RecordingLogger(true);
    private final MutableClock clock = new MutableClock();

    @TempDir
    Path dataDir;

    /** A dialog that takes every prompt it is offered, or none, and remembers each one. */
    static final class RecordingDialogs implements LinkDialogs {

        final List<String> shown = new CopyOnWriteArrayList<String>();
        final List<Runnable> fallbacks = new CopyOnWriteArrayList<Runnable>();
        volatile boolean accept = true;
        volatile RuntimeException failure;

        @Override
        public boolean show(PlayerHandle player, LinkCodePrompt prompt, Runnable fallback) {
            if (failure != null) {
                throw failure;
            }
            if (!accept) {
                return false;
            }
            shown.add(player.name() + ":" + prompt.code());
            fallbacks.add(fallback);
            return true;
        }
    }

    private LinkCodePrompt prompt(String code) {
        return LinkCodePrompt.of(code, String.valueOf(clock.now() + 300_000L));
    }

    private LinkPrompter prompter(FakePlatform platform) {
        return new LinkPrompter(logger, platform, clock);
    }

    @Test
    @DisplayName("a dialog-capable player gets the dialog and no chat")
    void dialogWhereSupported() {
        RecordingDialogs dialogs = new RecordingDialogs();
        FakePlatform platform = new FakePlatform(ServerRole.STANDALONE, dataDir).withLinkDialogs(dialogs);
        FakePlayer steve = platform.join(FakePlayer.named("Steve"));

        assertEquals(LinkPrompter.Outcome.SHOWN, prompter(platform).present(steve, prompt("123456")));

        assertEquals(1, dialogs.shown.size());
        assertTrue(steve.messageText().isEmpty(),
                "the dialog is the prompt; chat on top would be the code twice: " + steve.messageText());
    }

    @Test
    @DisplayName("a platform with no dialog sends the two chat lines")
    void chatEverywhereElse() {
        FakePlatform platform = new FakePlatform(ServerRole.GATEKEEPER, dataDir);
        FakePlayer steve = platform.join(FakePlayer.named("Steve"));

        assertEquals(LinkPrompter.Outcome.SHOWN, prompter(platform).present(steve, prompt("123456")));

        assertEquals(2, steve.messageText().size());
        assertTrue(steve.messageText().get(0).contains("123456"));
        assertTrue(steve.messageText().get(1).contains("Confirm Code"));
    }

    @Test
    @DisplayName("a dialog that declines this player, or throws, becomes chat")
    void declinedOrBrokenDialogIsChat() {
        RecordingDialogs dialogs = new RecordingDialogs();
        dialogs.accept = false;
        FakePlatform platform = new FakePlatform(ServerRole.STANDALONE, dataDir).withLinkDialogs(dialogs);
        FakePlayer oldClient = platform.join(FakePlayer.named("OldClient"));
        LinkPrompter prompter = prompter(platform);

        prompter.present(oldClient, prompt("111111"));
        assertEquals(2, oldClient.messageText().size(), "a 1.21.5 client is told in chat");

        dialogs.accept = true;
        dialogs.failure = new IllegalStateException("reflection went wrong on this build");
        FakePlayer unlucky = platform.join(FakePlayer.named("Unlucky"));
        prompter.present(unlucky, prompt("222222"));
        assertEquals(2, unlucky.messageText().size(),
                "a broken dialog path must still get the code to the player");
    }

    @Test
    @DisplayName("a dialog that fails after accepting runs the chat fallback")
    void lateFailureFallsBack() {
        RecordingDialogs dialogs = new RecordingDialogs();
        FakePlatform platform = new FakePlatform(ServerRole.STANDALONE, dataDir).withLinkDialogs(dialogs);
        FakePlayer steve = platform.join(FakePlayer.named("Steve"));

        prompter(platform).present(steve, prompt("123456"));
        assertTrue(steve.messageText().isEmpty());

        // What the Bukkit binding does when building the dialog on the player's thread throws.
        dialogs.fallbacks.get(0).run();

        assertEquals(2, steve.messageText().size());
        assertTrue(steve.messageText().get(0).contains("123456"));
    }

    @Test
    @DisplayName("a Bedrock player gets chat even where dialogs work, by Floodgate or by UUID shape")
    void bedrockIsAlwaysChat() {
        RecordingDialogs dialogs = new RecordingDialogs();
        final FakePlayer viaFloodgate = FakePlayer.named("BedrockBob");
        FakePlatform platform = new FakePlatform(ServerRole.STANDALONE, dataDir)
                .withLinkDialogs(dialogs)
                .withFloodgate(FakePlatform.bedrockFor(
                        viaFloodgate.uuid().toString(), new BedrockIdentity("BedrockBob", "123")));
        platform.join(viaFloodgate);
        // Floodgate on the proxy only: this server cannot ask it, but the UUID still says Bedrock.
        FakePlayer bySynthetic = platform.join(new FakePlayer(
                new UUID(0L, 0x0009_0000_1234_5678L), ".PhonePlayer"));
        LinkPrompter prompter = prompter(platform);

        prompter.present(viaFloodgate, prompt("123456"));
        prompter.present(bySynthetic, prompt("654321"));

        assertTrue(dialogs.shown.isEmpty(), "no dialog for either: " + dialogs.shown);
        assertEquals(2, viaFloodgate.messageText().size());
        assertEquals(2, bySynthetic.messageText().size());
    }

    @Test
    @DisplayName("the same code twice inside the window is shown once; a new code always shows")
    void repeatsAreSuppressed() {
        FakePlatform platform = new FakePlatform(ServerRole.STANDALONE, dataDir);
        FakePlayer steve = platform.join(FakePlayer.named("Steve"));
        LinkPrompter prompter = prompter(platform);

        assertEquals(LinkPrompter.Outcome.SHOWN, prompter.present(steve, prompt("123456")));
        clock.advance(5_000L);
        assertEquals(LinkPrompter.Outcome.ALREADY_SHOWN, prompter.present(steve, prompt("123456")),
                "the join check and the bot's push racing each other must not show it twice");
        assertEquals(2, steve.messageText().size());

        assertEquals(LinkPrompter.Outcome.SHOWN, prompter.present(steve, prompt("999999")),
                "a player who restarted linking in Discord needs the new code at once");
        assertEquals(4, steve.messageText().size());

        clock.advance(LinkPrompter.REPEAT_WINDOW_MS);
        assertEquals(LinkPrompter.Outcome.SHOWN, prompter.present(steve, prompt("999999")),
                "the window is for the race, not a ration: asked again later, it shows again");
    }

    @Test
    @DisplayName("the window is per player")
    void windowIsPerPlayer() {
        FakePlatform platform = new FakePlatform(ServerRole.STANDALONE, dataDir);
        FakePlayer steve = platform.join(FakePlayer.named("Steve"));
        FakePlayer alex = platform.join(FakePlayer.named("Alex"));
        LinkPrompter prompter = prompter(platform);

        prompter.present(steve, prompt("123456"));
        assertEquals(LinkPrompter.Outcome.SHOWN, prompter.present(alex, prompt("123456")));
    }

    @Test
    @DisplayName("an expired code is not shown, and does not use up the window")
    void expiredIsNotShown() {
        FakePlatform platform = new FakePlatform(ServerRole.STANDALONE, dataDir);
        FakePlayer steve = platform.join(FakePlayer.named("Steve"));
        LinkPrompter prompter = prompter(platform);

        LinkCodePrompt dead = LinkCodePrompt.of("123456", String.valueOf(clock.now() - 1L));
        assertEquals(LinkPrompter.Outcome.EXPIRED, prompter.present(steve, dead));
        assertTrue(steve.messageText().isEmpty());

        assertEquals(LinkPrompter.Outcome.SHOWN, prompter.present(steve, prompt("123456")));
    }
}
