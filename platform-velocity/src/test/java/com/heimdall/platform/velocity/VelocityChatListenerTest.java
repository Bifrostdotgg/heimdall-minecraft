package com.heimdall.platform.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.heimdall.core.log.RecordingLogger;
import com.heimdall.core.pipeline.ChatMessage;
import com.heimdall.core.pipeline.ChatObserver;
import com.heimdall.core.pipeline.ChatPipeline;
import com.heimdall.core.pipeline.Verdict;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.proxy.Player;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The one property this listener exists to have: it <strong>never touches the event</strong>.
 *
 * <p>The failure it guards is not a subtle one. A proxy that cancels or edits signed chat kicks the
 * player with a client-side "chat validation failure", from a plugin that logs nothing — so the
 * symptom is players being disconnected mid-sentence and no evidence pointing here. The listener's
 * javadoc says it must not; this file is the executable form of that, and the assertion is on the
 * event's own state after dispatch rather than on "we did not call the setter", because the second
 * is a claim about the code and only the first is a claim about the behaviour.
 *
 * <p>{@code PlayerChatEvent} is final and has a public constructor, so a real one is used — no mock
 * standing in for the type whose mutability is the whole subject.
 */
class VelocityChatListenerTest {

    private final RecordingLogger logger = new RecordingLogger(true);
    private final ChatPipeline pipeline = new ChatPipeline(logger);
    private final List<String> relayed = new ArrayList<String>();

    private VelocityChatListener listener() {
        return new VelocityChatListener(logger, pipeline);
    }

    private VelocityChatListener listeningWithAnObserver() {
        pipeline.observe(new ChatObserver() {
            @Override
            public void onChat(ChatMessage message) {
                relayed.add(message.senderName() + ": " + message.message());
            }
        });
        return listener();
    }

    private static PlayerChatEvent chat(String message) {
        Player steve = mock(Player.class);
        when(steve.getUniqueId()).thenReturn(UUID.randomUUID());
        when(steve.getUsername()).thenReturn("Steve");
        return new PlayerChatEvent(steve, message);
    }

    @Test
    @DisplayName("the event is allowed, and the same result object, after dispatch")
    void theEventIsNeverTouched() {
        PlayerChatEvent event = chat("hello everyone");
        PlayerChatEvent.ChatResult before = event.getResult();

        listeningWithAnObserver().onChat(event);

        assertTrue(event.getResult().isAllowed(),
                "cancelling signed chat on a proxy kicks the client for a chat validation failure");
        assertSame(before, event.getResult(),
                "not merely still allowed — the identical result, so nothing set it at all");
        assertEquals("hello everyone", event.getMessage(),
                "and the message is not rewritten either");
    }

    @Test
    @DisplayName("a DENY verdict is discarded rather than turned into a cancellation")
    void aDenyVerdictDoesNotCancel() {
        // The one way this class could become the bug it is written to avoid: an interceptor that
        // says "block this" and a listener that obliges. On a proxy, obliging means kicking the
        // player. So the verdict is read by nobody here — and this test is what stops somebody
        // "finishing" the listener by wiring it up.
        pipeline.register(message -> Verdict.deny(net.kyori.adventure.text.Component.text("no")),
                0, "test");
        PlayerChatEvent event = chat("hello everyone");
        PlayerChatEvent.ChatResult before = event.getResult();

        listeningWithAnObserver().onChat(event);

        assertSame(before, event.getResult(),
                "a proxy has no right to act on a chat verdict, whatever the verdict says");
        assertTrue(relayed.isEmpty(),
                "a blocked message is still not relayed — observers run only for allowed ones");
    }

    @Test
    @DisplayName("an allowed message reaches the observers verbatim")
    void anAllowedMessageIsObserved() {
        listeningWithAnObserver().onChat(chat("  spaces   kept  "));

        assertEquals(Collections.singletonList("Steve:   spaces   kept  "), relayed,
                "the bot owns rendering; a relay that trimmed what a player typed would be editing "
                        + "it");
    }

    @Test
    @DisplayName("a message another plugin already denied is not relayed")
    void anAlreadyDeniedMessageIsSkipped() {
        PlayerChatEvent event = chat("hello everyone");
        event.setResult(PlayerChatEvent.ChatResult.denied());

        listeningWithAnObserver().onChat(event);

        assertTrue(relayed.isEmpty(),
                "relaying something the network just refused to send would put it in front of a "
                        + "wider audience than it started with");
    }

    @Test
    @DisplayName("an observer that throws cannot affect the event, and its text is not logged")
    void aBrokenObserverIsContainedAndQuiet() {
        pipeline.observe(new ChatObserver() {
            @Override
            public void onChat(ChatMessage message) {
                throw new IllegalStateException("the relay is down");
            }
        });
        PlayerChatEvent event = chat("a secret sentence");
        PlayerChatEvent.ChatResult before = event.getResult();

        listener().onChat(event);

        assertSame(before, event.getResult());
        assertFalseContains(logger.records().toString(), "a secret sentence");
    }

    private static void assertFalseContains(String haystack, String needle) {
        assertTrue(!haystack.contains(needle),
                "chat content reached a log file, which is exactly the storage the bridge promises "
                        + "not to do: " + haystack);
    }
}
