package com.heimdall.platform.bukkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.IntSupplier;
import net.kyori.adventure.text.Component;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mineacademy.chatcontrol.model.Channel;
import org.mineacademy.chatcontrol.settings.Settings;

/**
 * The legacy chat listener's shapes: unchanged without ChatControl; "check at NORMAL, relay later"
 * with it, where later is this event's MONITOR on Spigot and Paper's {@code AsyncChatEvent} on Paper.
 * Departure D85.
 *
 * <p>The handlers are called directly, in Bukkit's order. {@link #fire} reproduces the one piece of
 * dispatch that matters here: NORMAL is {@code ignoreCancelled}, LOWEST and MONITOR are not.
 */
class BukkitChatListenerTest {

    private final RecordingLogger logger = new RecordingLogger(true);
    private final ChatPipeline pipeline = new ChatPipeline(logger);
    private final List<ChatMessage> relayed = new ArrayList<ChatMessage>();

    /** ChatControl present or not, steered per test. */
    private volatile Plugin chatControl;
    private volatile ClassLoader loader = BukkitChatListenerTest.class.getClassLoader();
    private volatile int online = 2;

    private final Player steve = player("Steve");
    private final Player bystander = player("Bystander");

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

    private BukkitChatListener listener(BukkitChatListener.ModernChat modern) {
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
        return new BukkitChatListener(logger, pipeline, messenger, channels, modern,
                new IntSupplier() {
                    @Override
                    public int getAsInt() {
                        return online;
                    }
                });
    }

    /** A Spigot server: no AsyncChatEvent. */
    private BukkitChatListener spigot() {
        return listener(null);
    }

    /** A Paper server with the AsyncChatEvent hook installed. */
    private BukkitChatListener paper() {
        BukkitChatListener listener =
                listener(BukkitChatListener.ModernChat.of(FakePaperChatEvent.class));
        listener.installModern((type, executor) -> {
            assertEquals(FakePaperChatEvent.class, type);
        });
        assertEquals(BukkitChatListener.ModernState.HOOKED, listener.modernState());
        return listener;
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

    /** A line whose recipients are the sender and a bystander, as an ordinary line's would be. */
    private AsyncPlayerChatEvent chat(Player player, String message) {
        return chatTo(player, message, player, bystander);
    }

    @SuppressWarnings("deprecation")
    private static AsyncPlayerChatEvent chatTo(Player player, String message, Player... recipients) {
        Set<Player> to = new LinkedHashSet<Player>(Arrays.asList(recipients));
        return new AsyncPlayerChatEvent(true, player, message, to);
    }

    /** LOWEST, NORMAL unless cancelled, MONITOR: the legacy event as Bukkit dispatches it. */
    private static void fire(BukkitChatListener listener, AsyncPlayerChatEvent event) {
        listener.onChatStart(event);
        if (!event.isCancelled()) {
            listener.onChat(event);
        }
        listener.onChatRelay(event);
    }

    private List<String> relayedText() {
        List<String> out = new ArrayList<String>();
        for (ChatMessage message : relayed) {
            out.add(message.message() + (message.channel() == null ? "" : "@" + message.channel()));
        }
        return out;
    }

    // ── Without ChatControl ──────────────────────────────────────────────────

    @Nested
    @DisplayName("without ChatControl")
    class WithoutChatControl {

        @Test
        @DisplayName("relayed once, at NORMAL, exactly as before")
        void relaysAtNormal() {
            BukkitChatListener listener = spigot();
            AsyncPlayerChatEvent event = chat(steve, "hello");

            listener.onChatStart(event);
            listener.onChat(event);
            assertEquals(Collections.singletonList("hello"), relayedText(), "at NORMAL");

            listener.onChatRelay(event);
            assertEquals(Collections.singletonList("hello"), relayedText(),
                    "and the MONITOR handler adds nothing, or every line would reach Discord twice");
        }

        @Test
        @DisplayName("a blocked line is cancelled, explained and not relayed")
        void denyStillWorks() {
            pipeline.register(message -> Verdict.deny(Component.text("you are muted")), 0, "test");
            AsyncPlayerChatEvent event = chat(steve, "hello");

            fire(spigot(), event);

            assertTrue(event.isCancelled());
            assertTrue(relayed.isEmpty());
            verify(steve).sendMessage(anyString());
        }

        @Test
        @DisplayName("on Paper too: relayed at NORMAL, and the AsyncChatEvent hook adds nothing")
        void paperWithoutChatControl() {
            BukkitChatListener listener = paper();
            fire(listener, chat(steve, "hello"));
            listener.onModernChat(new FakePaperChatEvent(steve, steve, bystander));

            assertEquals(Collections.singletonList("hello"), relayedText());
        }
    }

    // ── Spigot with ChatControl ──────────────────────────────────────────────

    @Nested
    @DisplayName("Spigot with ChatControl")
    class SpigotWithChatControl {

        @Test
        @DisplayName("channels off: nothing at NORMAL, one untagged relay at MONITOR")
        void channelsOffRelaysAtMonitor() {
            withChatControl(Boolean.FALSE);
            BukkitChatListener listener = spigot();
            AsyncPlayerChatEvent event = chat(steve, "hello");

            listener.onChatStart(event);
            listener.onChat(event);
            assertTrue(relayed.isEmpty(), "NORMAL is checks only once ChatControl is installed");

            listener.onChatRelay(event);
            assertEquals(Collections.singletonList("hello"), relayedText());
        }

        @Test
        @DisplayName("MONITOR relays the line as the server finally showed it")
        void monitorRelaysTheFinalText() {
            withChatControl(Boolean.FALSE);
            BukkitChatListener listener = spigot();
            AsyncPlayerChatEvent event = chat(steve, "a rude word");

            listener.onChatStart(event);
            listener.onChat(event);
            event.setMessage("a **** word");
            listener.onChatRelay(event);

            assertEquals(Collections.singletonList("a **** word"), relayedText(),
                    "a filter between NORMAL and MONITOR rewrote it; Discord gets what the game got");
        }

        @Test
        @DisplayName("channels on: a channel player's line is left to the channel hook")
        void channelPlayerIsSkipped() {
            withChatControl(Boolean.TRUE);
            Channel.using(steve, true);

            fire(spigot(), chat(steve, "staff stuff"));

            assertTrue(relayed.isEmpty(),
                    "relaying it here would publish a staff-channel line as public chat");
        }

        @Test
        @DisplayName("channels on: a player outside channels is relayed untagged")
        void nonChannelPlayerIsRelayed() {
            withChatControl(Boolean.TRUE);

            fire(spigot(), chat(steve, "hello"));

            assertEquals(Collections.singletonList("hello"), relayedText());
        }

        @Test
        @DisplayName("a blocked line is still blocked at NORMAL and never relayed")
        void denyWithChatControl() {
            withChatControl(Boolean.TRUE);
            pipeline.register(message -> Verdict.deny(Component.text("you are muted")), 0, "test");
            AsyncPlayerChatEvent event = chat(steve, "hello");

            fire(spigot(), event);

            assertTrue(event.isCancelled(), "blocked before ChatControl delivers anything");
            assertTrue(relayed.isEmpty());
            verify(steve).sendMessage(anyString());
        }

        @Test
        @DisplayName("recipients shrunk to the sender while others are online: shadow-blocked, dropped")
        void shadowBlockedIsDropped() {
            withChatControl(Boolean.FALSE);
            online = 3;

            fire(spigot(), chatTo(steve, "spam spam", steve));

            assertTrue(relayed.isEmpty(),
                    "ChatControl showed it to the sender only; Discord must not see more");
        }

        @Test
        @DisplayName("no recipients at all: dropped")
        void emptyRecipientsAreDropped() {
            withChatControl(Boolean.FALSE);

            fire(spigot(), chatTo(steve, "to nobody"));

            assertTrue(relayed.isEmpty());
        }

        @Test
        @DisplayName("alone on the server, a line only the sender can see is still relayed")
        void aloneIsNotShadowBlocked() {
            withChatControl(Boolean.FALSE);
            online = 1;

            fire(spigot(), chatTo(steve, "anyone there?", steve));

            assertEquals(Collections.singletonList("anyone there?"), relayedText());
        }

        @Test
        @DisplayName("the path is decided once per line: ChatControl appearing mid-line cannot double it")
        void decidedOncePerLine() {
            BukkitChatListener listener = spigot();
            AsyncPlayerChatEvent event = chat(steve, "hello");

            listener.onChatStart(event);
            listener.onChat(event);
            assertEquals(1, relayed.size(), "not installed at LOWEST, so relayed at NORMAL");

            withChatControl(Boolean.FALSE);
            listener.onChatRelay(event);

            assertEquals(1, relayed.size(),
                    "deciding again at MONITOR would relay the same line a second time");
        }

        @Test
        @DisplayName("a broken integration relays nothing, and says so once rather than per line")
        void brokenRelaysNothing() {
            withChatControl(Boolean.TRUE);
            loader = new ClassLoader(null) {
            };
            BukkitChatListener listener = spigot();

            AsyncPlayerChatEvent first = chat(steve, "correct-horse-battery-staple");
            AsyncPlayerChatEvent second = chat(bystander, "another line");
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

        @Test
        @DisplayName("ChatControl throwing for one line drops that line only")
        void transientFailureDropsOneLine() {
            withChatControl(Boolean.TRUE);
            BukkitChatListener listener = spigot();

            Channel.failUsing = new IllegalStateException("player data not loaded");
            fire(listener, chat(steve, "first"));
            Channel.failUsing = null;
            fire(listener, chat(steve, "second"));

            assertEquals(Collections.singletonList("second"), relayedText());
        }
    }

    // ── Paper with ChatControl ───────────────────────────────────────────────

    @Nested
    @DisplayName("Paper with ChatControl: relay waits for AsyncChatEvent")
    class PaperWithChatControl {

        @Test
        @DisplayName("nothing relays at the legacy MONITOR; the AsyncChatEvent hook relays it once")
        void relaysFromTheModernEvent() {
            withChatControl(Boolean.FALSE);
            BukkitChatListener listener = paper();

            fire(listener, chat(steve, "hello"));
            assertTrue(relayed.isEmpty(),
                    "on Paper ChatControl has not looked at the line yet when the legacy MONITOR runs");

            listener.onModernChat(new FakePaperChatEvent(steve, steve, bystander));
            assertEquals(Collections.singletonList("hello"), relayedText());

            listener.onModernChat(new FakePaperChatEvent(steve, steve, bystander));
            assertEquals(1, relayed.size(), "taken once: the hand-off is cleared when it is read");
        }

        @Test
        @DisplayName("ChatControl cancelling AsyncChatEvent (mute, rule, PM auto-mode) stops the relay")
        void cancelledModernEventIsNotRelayed() {
            withChatControl(Boolean.FALSE);
            BukkitChatListener listener = paper();

            fire(listener, chat(steve, "psst, the password is hunter2"));
            FakePaperChatEvent modern = new FakePaperChatEvent(steve, steve, bystander);
            modern.setCancelled(true);
            listener.onModernChat(modern);

            assertTrue(relayed.isEmpty(),
                    "private-message auto-mode re-sends chat as /tell and cancels the chat event");
            listener.onModernChat(new FakePaperChatEvent(steve, steve, bystander));
            assertTrue(relayed.isEmpty(), "and the parked line is gone, not waiting for a retry");
        }

        @Test
        @DisplayName("viewers shrunk to the sender while others are online: shadow-blocked, dropped")
        void shrunkViewersAreDropped() {
            withChatControl(Boolean.FALSE);
            BukkitChatListener listener = paper();

            fire(listener, chat(steve, "spam"));
            listener.onModernChat(
                    new FakePaperChatEvent(steve, steve, mock(ConsoleCommandSender.class)));

            assertTrue(relayed.isEmpty(), "the console in the viewers is not a second player");
        }

        @Test
        @DisplayName("an AsyncChatEvent for someone else does not release the parked line")
        void otherSendersEventDoesNotRelay() {
            withChatControl(Boolean.FALSE);
            BukkitChatListener listener = paper();

            fire(listener, chat(steve, "hello"));
            listener.onModernChat(new FakePaperChatEvent(bystander, steve, bystander));

            assertTrue(relayed.isEmpty());
        }

        @Test
        @DisplayName("LOWEST resets the hand-off: a line parked by an earlier event never leaks")
        void lowestClearsStaleState() {
            withChatControl(Boolean.FALSE);
            BukkitChatListener listener = paper();

            fire(listener, chat(steve, "parked and abandoned"));
            listener.onChatStart(chat(steve, "the next line"));
            listener.onModernChat(new FakePaperChatEvent(steve, steve, bystander));

            assertTrue(relayed.isEmpty());
        }

        @Test
        @DisplayName("a channel player is still left to the channel hook")
        void channelPlayerIsSkipped() {
            withChatControl(Boolean.TRUE);
            Channel.using(steve, true);
            BukkitChatListener listener = paper();

            fire(listener, chat(steve, "staff stuff"));
            listener.onModernChat(new FakePaperChatEvent(steve, steve, bystander));

            assertTrue(relayed.isEmpty());
        }

        @Test
        @DisplayName("AsyncChatEvent present but unhookable: relay nothing, said once, never fall back")
        void unhookableRelaysNothing() {
            withChatControl(Boolean.FALSE);
            BukkitChatListener listener =
                    listener(BukkitChatListener.ModernChat.of(FakePaperChatEvent.class));
            listener.installModern((type, executor) -> {
                throw new IllegalStateException("plugin not enabled");
            });
            assertEquals(BukkitChatListener.ModernState.FAILED, listener.modernState());

            fire(listener, chat(steve, "one"));
            fire(listener, chat(steve, "two"));

            assertTrue(relayed.isEmpty(),
                    "falling back to the legacy MONITOR is exactly the Paper leak the hook closes");
            assertEquals(1, logger.messagesAt(LogLevel.SEVERE).stream()
                    .filter(line -> line.contains("AsyncChatEvent is present but not hooked")).count(),
                    logger.records().toString());
        }

        @Test
        @DisplayName("before the hook is installed (PENDING), a line is dropped quietly")
        void pendingDropsWithoutSpendingTheOnceOnlyError() {
            withChatControl(Boolean.FALSE);
            BukkitChatListener listener =
                    listener(BukkitChatListener.ModernChat.of(FakePaperChatEvent.class));
            assertEquals(BukkitChatListener.ModernState.PENDING, listener.modernState());

            fire(listener, chat(steve, "during a reload"));
            assertTrue(relayed.isEmpty(), "fail closed while the hook is not there yet");
            assertTrue(logger.at(LogLevel.SEVERE).isEmpty(),
                    "the once-per-boot error is for a hook that will never come: "
                            + logger.records());

            listener.installModern((type, executor) -> {
                throw new IllegalStateException("plugin not enabled");
            });
            fire(listener, chat(steve, "after it failed"));
            assertEquals(1, logger.messagesAt(LogLevel.SEVERE).stream()
                    .filter(line -> line.contains("AsyncChatEvent is present but not hooked")).count(),
                    "still available to report the real failure: " + logger.records());
        }

        @Test
        @DisplayName("a parked line no Paper event came for expires instead of waiting forever")
        void parkedLineExpires() {
            withChatControl(Boolean.FALSE);
            BukkitChatListener listener = paper();
            listener.parkTtlNanos = 0L;

            fire(listener, chat(steve, "a synthetic legacy event"));
            listener.onModernChat(new FakePaperChatEvent(steve, steve, bystander));

            assertTrue(relayed.isEmpty(),
                    "older than the hand-off window: not this event's line, not relayed");
        }

        @Test
        @DisplayName("close() releases this thread's parked line")
        void closeReleasesTheThreadsState() {
            withChatControl(Boolean.FALSE);
            BukkitChatListener listener = paper();

            fire(listener, chat(steve, "parked on the main thread"));
            listener.close();
            listener.onModernChat(new FakePaperChatEvent(steve, steve, bystander));

            assertTrue(relayed.isEmpty());
        }

        @Test
        @DisplayName("a class that is present but not the event it should be counts as unhookable")
        void wrongShapeIsUnusable() {
            assertNull(BukkitChatListener.ModernChat.detect(new ClassLoader(null) {
            }), "absent is null: Spigot");
            assertFalse(BukkitChatListener.ModernChat.of(String.class).usable());
            assertEquals(BukkitChatListener.ModernState.FAILED,
                    listener(BukkitChatListener.ModernChat.of(String.class)).modernState());
        }
    }
}
