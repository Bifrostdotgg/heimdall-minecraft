package com.heimdall.platform.bungee;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.heimdall.core.log.RecordingLogger;
import com.heimdall.core.pipeline.ChatMessage;
import com.heimdall.core.pipeline.ChatObserver;
import com.heimdall.core.pipeline.ChatPipeline;
import com.heimdall.core.pipeline.Verdict;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.md_5.bungee.api.connection.Connection;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.api.event.ChatEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Never cancels, never edits, and knows which half of a two-directional event is actually chat.
 *
 * <p>Two failures are pinned here and they have different shapes.
 *
 * <p><strong>Cancellation</strong> is the client-compatibility one: a proxy that drops signed chat
 * disconnects the player for a "chat validation failure" and leaves nothing in any log pointing at
 * the plugin that did it. The assertion is on the event's own state after dispatch, not on "we did
 * not call the setter" — the second is a claim about the code and only the first is about the
 * behaviour.
 *
 * <p><strong>Direction</strong> is BungeeCord's alone. {@code ChatEvent} fires from
 * {@code UpstreamBridge} for a player talking <em>and</em> from {@code DownstreamBridge} for a
 * server talking to a player. Relaying the second would ship every server message to Discord —
 * including the bridge's own Discord→Minecraft deliveries, which is a loop rather than noise.
 *
 * <p>The event is constructed for real: it is final in behaviour terms (a value type with a public
 * constructor), and its mutability is the subject.
 */
class BungeeChatListenerTest {

    private final RecordingLogger logger = new RecordingLogger(true);
    private final ChatPipeline pipeline = new ChatPipeline(logger);
    private final List<String> relayed = new ArrayList<String>();

    private BungeeChatListener listener() {
        return new BungeeChatListener(logger, pipeline);
    }

    private BungeeChatListener listeningWithAnObserver() {
        pipeline.observe(new ChatObserver() {
            @Override
            public void onChat(ChatMessage message) {
                relayed.add(message.senderName() + ": " + message.message());
            }
        });
        return listener();
    }

    private static ProxiedPlayer steve() {
        ProxiedPlayer player = mock(ProxiedPlayer.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getName()).thenReturn("Steve");
        return player;
    }

    /** A player talking: the upstream half, which is the only half that is chat. */
    private static ChatEvent fromPlayer(String message) {
        return new ChatEvent(steve(), mock(Server.class), message);
    }

    /** A server talking to a player: the downstream half. */
    private static ChatEvent fromServer(String message) {
        return new ChatEvent(mock(Server.class), steve(), message);
    }

    @Test
    @DisplayName("the event is neither cancelled nor rewritten")
    void theEventIsNeverTouched() {
        ChatEvent event = fromPlayer("hello everyone");

        listeningWithAnObserver().onChat(event);

        assertFalse(event.isCancelled(),
                "cancelling signed chat on a proxy kicks the client for a chat validation failure");
        assertEquals("hello everyone", event.getMessage(), "and the text is not rewritten either");
    }

    @Test
    @DisplayName("a DENY verdict is discarded rather than turned into a cancellation")
    void aDenyVerdictDoesNotCancel() {
        // The one way this class could become the bug it is written to avoid: an interceptor that
        // says "block this" and a listener that obliges. On a proxy, obliging means kicking the
        // player — so the verdict is read by nobody, and this test is what stops somebody
        // "finishing" the listener by wiring it up.
        pipeline.register(message -> Verdict.deny(Component.text("no")), 0, "test");
        ChatEvent event = fromPlayer("hello everyone");

        listeningWithAnObserver().onChat(event);

        assertFalse(event.isCancelled(),
                "a proxy has no right to act on a chat verdict, whatever the verdict says");
        assertTrue(relayed.isEmpty(),
                "a blocked message is still not relayed — observers run only for allowed ones");
    }

    @Test
    @DisplayName("a player's message reaches the observers verbatim")
    void aPlayerMessageIsObserved() {
        listeningWithAnObserver().onChat(fromPlayer("  spaces   kept  "));

        assertEquals(Collections.singletonList("Steve:   spaces   kept  "), relayed,
                "the bot owns rendering; a relay that trimmed what a player typed would be editing "
                        + "it");
    }

    @Test
    @DisplayName("a message the SERVER sent to a player is not relayed")
    void theDownstreamHalfIsIgnored() {
        listeningWithAnObserver().onChat(fromServer("[Discord] someone: hi"));

        assertTrue(relayed.isEmpty(),
                "DownstreamBridge fires ChatEvent too; relaying it would send the bridge's own "
                        + "Discord deliveries straight back to Discord");
    }

    @Test
    @DisplayName("commands are not chat, proxy-side or backend-side")
    void commandsAreExcluded() {
        listeningWithAnObserver().onChat(fromPlayer("/login hunter2"));
        listeningWithAnObserver().onChat(fromPlayer("/server lobby"));

        assertTrue(relayed.isEmpty(),
                "a relay that published /login hunter2 to a Discord channel would be publishing "
                        + "passwords");
    }

    @Test
    @DisplayName("an already-cancelled message is skipped")
    void anAlreadyCancelledMessageIsSkipped() {
        ChatEvent event = fromPlayer("hello everyone");
        event.setCancelled(true);

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
        ChatEvent event = fromPlayer("a secret sentence");

        listener().onChat(event);

        assertFalse(event.isCancelled());
        assertEquals("a secret sentence", event.getMessage());
        assertFalse(logger.records().toString().contains("a secret sentence"),
                "chat content reached a log file, which is exactly the storage the bridge promises "
                        + "not to do: " + logger.records());
    }

    /**
     * A sender that is neither a player nor a server — the shape a future BungeeCord could
     * introduce, and the reason the check is an {@code instanceof ProxiedPlayer} rather than a
     * {@code !(sender instanceof Server)}.
     */
    @Test
    @DisplayName("an unrecognised sender is ignored rather than guessed at")
    void anUnknownSenderIsIgnored() {
        listeningWithAnObserver()
                .onChat(new ChatEvent(mock(Connection.class), steve(), "who said that"));

        assertTrue(relayed.isEmpty());
    }
}
