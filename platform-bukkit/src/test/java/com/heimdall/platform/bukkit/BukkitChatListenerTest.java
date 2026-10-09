package com.heimdall.platform.bukkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.heimdall.core.log.LogLevel;
import com.heimdall.core.log.RecordingLogger;
import com.heimdall.core.pipeline.ChatMessage;
import com.heimdall.core.pipeline.ChatPipeline;
import com.heimdall.core.pipeline.Verdict;
import com.heimdall.core.platform.PlayerHandle;
import com.heimdall.core.testing.FakePlayer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mineacademy.chatcontrol.model.Channel;
import org.mineacademy.chatcontrol.settings.Settings;

/**
 * The legacy chat listener's two shapes: unchanged without ChatControl, split into "check at
 * NORMAL, relay at MONITOR" with it. Departure D85.
 *
 * <p>The handlers are called directly, in Bukkit's order, and {@link #fire} reproduces the one piece
 * of Bukkit's dispatch that matters here: a MONITOR handler declared {@code ignoreCancelled} never
 * sees an event an earlier handler cancelled.
 */
class BukkitChatListenerTest {

    private final RecordingLogger logger = new RecordingLogger(true);
    private final ChatPipeline pipeline = new ChatPipeline(logger);
    private final List<ChatMessage> relayed = new ArrayList<ChatMessage>();

    /** ChatControl present or not, steered per test. */
    private volatile Plugin chatControl;
    private volatile ClassLoader loader = BukkitChatListenerTest.class.getClassLoader();

    private final ChatControlChannels.Environment environment =
            new ChatControlChannels.Environment() {
                @Override
                public Plugin findChatControl() {
                    return chatControl;
                }

                @Override
                public ClassLoader loaderOf(Plugin plugin) {
                    return loader;
                }

                @Override
                public void register(
                        Class<? extends Event> type, Listener listener, EventExecutor executor) {
                    // The channel hook itself is ChatControlChannelsTest's subject.
                }
            };

    @BeforeEach
    void setUp() {
        Channel.reset();
        Settings.Channels.ENABLED = null;
        pipeline.observe(relayed::add);
    }

    @AfterEach
    void tearDown() {
        Channel.reset();
        Settings.Channels.ENABLED = null;
    }

    private BukkitChatListener listener() {
        ChatControlChannels channels = new ChatControlChannels(logger, environment,
                new Function<Player, PlayerHandle>() {
                    @Override
                    public PlayerHandle apply(Player player) {
                        return new FakePlayer(player.getUniqueId(), player.getName());
                    }
                }, 0L);
        channels.attach(pipeline);
        // A mock Plugin cannot host Adventure, so the messenger degrades to legacy sendMessage on
        // the player, which a mock records. Its own warning goes to a separate logger.
        BukkitMessenger messenger = new BukkitMessenger(mock(Plugin.class), new RecordingLogger());
        return new BukkitChatListener(logger, pipeline, messenger, channels);
    }

    private void withChatControl(Boolean channelsEnabled) {
        Plugin plugin = mock(Plugin.class);
        when(plugin.isEnabled()).thenReturn(true);
        chatControl = plugin;
        Settings.Channels.ENABLED = channelsEnabled;
    }

    private static Player player(String name) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.nameUUIDFromBytes(name.getBytes()));
        when(player.getName()).thenReturn(name);
        return player;
    }

    @SuppressWarnings("deprecation")
    private static AsyncPlayerChatEvent chat(Player player, String message) {
        return new AsyncPlayerChatEvent(true, player, message, Collections.<Player>emptySet());
    }

    /** NORMAL, then MONITOR unless something cancelled it, as Bukkit dispatches. */
    private static void fire(BukkitChatListener listener, AsyncPlayerChatEvent event) {
        listener.onChat(event);
        if (!event.isCancelled()) {
            listener.onChatRelay(event);
        }
    }

    private List<String> relayedText() {
        List<String> out = new ArrayList<String>();
        for (ChatMessage message : relayed) {
            out.add(message.message() + (message.channel() == null ? "" : "@" + message.channel()));
        }
        return out;
    }

    // ── Without ChatControl ──────────────────────────────────────────────────

    @Test
    @DisplayName("without ChatControl: relayed once, at NORMAL, exactly as before")
    void withoutChatControlRelaysAtNormal() {
        BukkitChatListener listener = listener();
        AsyncPlayerChatEvent event = chat(player("Steve"), "hello");

        listener.onChat(event);
        assertEquals(Collections.singletonList("hello"), relayedText(), "at NORMAL");

        listener.onChatRelay(event);
        assertEquals(Collections.singletonList("hello"), relayedText(),
                "and the MONITOR handler adds nothing, or every line would reach Discord twice");
    }

    @Test
    @DisplayName("without ChatControl: a blocked line is cancelled, explained and not relayed")
    void withoutChatControlDenyStillWorks() {
        pipeline.register(message -> Verdict.deny(Component.text("you are muted")), 0, "test");
        Player steve = player("Steve");
        AsyncPlayerChatEvent event = chat(steve, "hello");

        fire(listener(), event);

        assertTrue(event.isCancelled());
        assertTrue(relayed.isEmpty());
        verify(steve).sendMessage(anyString());
    }

    // ── With ChatControl ─────────────────────────────────────────────────────

    @Test
    @DisplayName("ChatControl with channels off: nothing at NORMAL, one untagged relay at MONITOR")
    void channelsOffRelaysAtMonitor() {
        withChatControl(Boolean.FALSE);
        BukkitChatListener listener = listener();
        AsyncPlayerChatEvent event = chat(player("Steve"), "hello");

        listener.onChat(event);
        assertTrue(relayed.isEmpty(), "NORMAL is checks only once ChatControl is installed");

        listener.onChatRelay(event);
        assertEquals(Collections.singletonList("hello"), relayedText());
    }

    @Test
    @DisplayName("MONITOR relays the line as the server finally showed it")
    void monitorRelaysTheFinalText() {
        withChatControl(Boolean.FALSE);
        BukkitChatListener listener = listener();
        AsyncPlayerChatEvent event = chat(player("Steve"), "a rude word");

        listener.onChat(event);
        event.setMessage("a **** word");
        listener.onChatRelay(event);

        assertEquals(Collections.singletonList("a **** word"), relayedText(),
                "a filter between NORMAL and MONITOR rewrote it; Discord gets what the game got");
    }

    @Test
    @DisplayName("ChatControl with channels on: a channel player's line is left to the channel hook")
    void channelPlayerIsSkipped() {
        withChatControl(Boolean.TRUE);
        Player inChannel = player("InChannel");
        Channel.using(inChannel, true);

        fire(listener(), chat(inChannel, "staff stuff"));

        assertTrue(relayed.isEmpty(),
                "relaying it here would publish a staff-channel line as public chat");
    }

    @Test
    @DisplayName("ChatControl with channels on: a player outside channels is relayed untagged")
    void nonChannelPlayerIsRelayed() {
        withChatControl(Boolean.TRUE);

        fire(listener(), chat(player("Outside"), "hello"));

        assertEquals(Collections.singletonList("hello"), relayedText());
    }

    @Test
    @DisplayName("with ChatControl, a blocked line is still blocked at NORMAL and never relayed")
    void denyWithChatControl() {
        withChatControl(Boolean.TRUE);
        pipeline.register(message -> Verdict.deny(Component.text("you are muted")), 0, "test");
        Player steve = player("Steve");
        AsyncPlayerChatEvent event = chat(steve, "hello");

        fire(listener(), event);

        assertTrue(event.isCancelled(), "blocked before ChatControl delivers anything");
        assertTrue(relayed.isEmpty());
        verify(steve).sendMessage(anyString());
    }

    @Test
    @DisplayName("a broken integration relays nothing, and says so once rather than per line")
    void brokenRelaysNothing() {
        withChatControl(Boolean.TRUE);
        loader = new ClassLoader(null) {
        };
        BukkitChatListener listener = listener();

        AsyncPlayerChatEvent first = chat(player("Steve"), "correct-horse-battery-staple");
        AsyncPlayerChatEvent second = chat(player("Alex"), "another line");
        fire(listener, first);
        fire(listener, second);

        assertTrue(relayed.isEmpty(), "fail closed: which channel a line is in is unknowable");
        assertFalse(first.isCancelled(), "relay is paused; chat itself is not");
        assertEquals(1, logger.messagesAt(LogLevel.SEVERE).stream()
                        .filter(line -> line.contains("in-game chat is not being relayed")).count(),
                "once, not per line: " + logger.records());
        assertFalse(logger.records().toString().contains("correct-horse"),
                "and never with the text: " + logger.records());
    }
}
