package com.heimdall.platform.bukkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.heimdall.core.log.LogLevel;
import com.heimdall.core.log.RecordingLogger;
import com.heimdall.core.pipeline.ChatMessage;
import com.heimdall.core.pipeline.ChatPipeline;
import com.heimdall.core.pipeline.Verdict;
import com.heimdall.core.platform.ChatChannels;
import com.heimdall.core.platform.PlayerHandle;
import com.heimdall.core.testing.FakePlayer;
import it.pino.zelchat.api.ZelChatAPI;
import it.pino.zelchat.api.ZelChatProvider;
import it.pino.zelchat.api.event.Event;
import it.pino.zelchat.api.event.EventListener;
import it.pino.zelchat.api.event.EventRegistry;
import it.pino.zelchat.api.event.builtin.AsyncPostMessageDispatchEvent;
import it.pino.zelchat.api.message.channel.ChannelType;
import it.pino.zelchat.api.player.ChatPlayer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The reflective ZelChat integration, against the real {@code zelchat-api} jar.
 *
 * <p>Only ZelChat's implementation is faked (a mocked {@link ZelChatAPI} published through ZelChat's
 * own {@link ZelChatProvider}, and a recording {@link EventRegistry}). Every class, method and enum
 * constant the production code looks up by name is the real one, so a ZelChat release that renames
 * something fails here.
 */
class ZelChatChannelsTest {

    private final RecordingLogger logger = new RecordingLogger(true);
    private final ChatPipeline pipeline = new ChatPipeline(logger);
    private final List<ChatMessage> relayed = new ArrayList<ChatMessage>();
    private final FakeEnvironment environment = new FakeEnvironment();
    private final FakeRegistry registry = new FakeRegistry();

    private static final Function<Player, PlayerHandle> HANDLES =
            new Function<Player, PlayerHandle>() {
                @Override
                public PlayerHandle apply(Player player) {
                    return new FakePlayer(player.getUniqueId(), player.getName());
                }
            };

    private final Player steve = player("Steve", true, false);
    private final Player moderator = player("Moderator", true, true);

    /** Steerable stand-in for the server. */
    private static final class FakeEnvironment implements ZelChatChannels.Environment {

        volatile Plugin zelChat;
        volatile ClassLoader loader = ZelChatChannelsTest.class.getClassLoader();
        volatile Collection<? extends Player> online = new ArrayList<Player>();

        @Override
        public Plugin findZelChat() {
            return zelChat;
        }

        @Override
        public ClassLoader loaderOf(Plugin plugin) {
            return loader;
        }

        @Override
        public Collection<? extends Player> onlinePlayers() {
            return online;
        }
    }

    /** ZelChat's event registry, recording what registers and dispatching like ZelChat would. */
    private static final class FakeRegistry implements EventRegistry {

        final List<EventListener<?>> listeners = new ArrayList<EventListener<?>>();
        final List<Class<?>> types = new ArrayList<Class<?>>();

        /** When set, runs inside register() before it records anything. */
        volatile Runnable gate;

        @Override
        public <E extends Event> void register(Class<E> eventClass, EventListener<E> listener) {
            Runnable hold = gate;
            if (hold != null) {
                hold.run();
            }
            types.add(eventClass);
            listeners.add(listener);
        }

        @Override
        public <E extends Event> void unregister(Class<E> eventClass, EventListener<E> listener) {
            int index = listeners.indexOf(listener);
            if (index >= 0) {
                listeners.remove(index);
                types.remove(index);
            }
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        void fire(Event event) {
            for (EventListener listener : new ArrayList<EventListener<?>>(listeners)) {
                listener.onEvent(event);
            }
        }
    }

    @BeforeEach
    void setUp() {
        pipeline.observe(relayed::add);
    }

    @AfterEach
    void tearDown() {
        ZelChatProvider.set(null);
    }

    private static Plugin plugin(boolean enabled) {
        Plugin plugin = mock(Plugin.class);
        when(plugin.isEnabled()).thenReturn(enabled);
        return plugin;
    }

    private static Player player(String name, boolean online, boolean staff) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.nameUUIDFromBytes(name.getBytes()));
        when(player.getName()).thenReturn(name);
        when(player.isOnline()).thenReturn(online);
        when(player.hasPermission(ZelChatChannels.STAFF_PERMISSION)).thenReturn(staff);
        return player;
    }

    /** ZelChat publishing its API, as its own onEnable does. */
    private void publishApi() {
        ZelChatAPI api = mock(ZelChatAPI.class);
        when(api.getEventRegistry()).thenReturn(registry);
        ZelChatProvider.set(api);
    }

    private ZelChatChannels channels() {
        return new ZelChatChannels(logger, environment, HANDLES, 0L);
    }

    /** ZelChat installed, enabled, API published, hook attached. */
    private ZelChatChannels active() {
        environment.zelChat = plugin(true);
        environment.online = Arrays.asList(steve, moderator);
        publishApi();
        ZelChatChannels channels = channels();
        channels.attach(pipeline);
        return channels;
    }

    private static AsyncPostMessageDispatchEvent line(Player sender, String text, ChannelType type) {
        ChatPlayer chatPlayer = mock(ChatPlayer.class);
        when(chatPlayer.getBukkitPlayer()).thenReturn(sender);
        return new AsyncPostMessageDispatchEvent(Component.text(text), type, chatPlayer);
    }

    private static List<String> names(Collection<PlayerHandle> handles) {
        List<String> out = new ArrayList<String>();
        for (PlayerHandle handle : handles) {
            out.add(handle.name());
        }
        return out;
    }

    // ── States ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("without ZelChat: not installed, state none, nothing registered")
    void absent() {
        ZelChatChannels channels = channels();
        channels.attach(pipeline);

        assertFalse(channels.installed());
        assertEquals(ChatChannels.State.NONE, channels.state());
        assertTrue(channels.channelNames().isEmpty());
        assertFalse(channels.members("staff").isPresent());
        assertEquals(ChannelHook.UntaggedRelay.RELAY, channels.untaggedRelay(steve));
        assertTrue(registry.listeners.isEmpty());
        assertTrue(logger.records().isEmpty(), "a server without ZelChat hears nothing about it");
    }

    @Test
    @DisplayName("active: both rooms reported, the hook registered once on the real event class")
    void active_reportsBothRooms() {
        ZelChatChannels channels = active();

        assertEquals(ChatChannels.State.ACTIVE, channels.state());
        assertEquals(Arrays.asList("global", "staff"), channels.channelNames());
        assertEquals("zelchat", channels.provider());
        assertEquals(1, registry.listeners.size());
        assertEquals(AsyncPostMessageDispatchEvent.class, registry.types.get(0));

        channels.state();
        assertEquals(1, registry.listeners.size(), "a second registration would relay twice");
    }

    @Test
    @DisplayName("enabled before its API is published: broken with a reason, but not for good")
    void apiPublishedLate() {
        environment.zelChat = plugin(true);
        ZelChatChannels channels = channels();
        channels.attach(pipeline);

        assertEquals(ChatChannels.State.BROKEN, channels.state(),
                "running but unhooked may already be routing staff chat: not one public room");
        assertTrue(registry.listeners.isEmpty());
        assertTrue(channels.brokenReason().contains("has not published its API"),
                channels.brokenReason());
        assertEquals(ChannelHook.UntaggedRelay.BROKEN, channels.untaggedRelay(steve));
        assertTrue(logger.at(LogLevel.SEVERE).isEmpty(), "not recorded as broken for the boot");

        publishApi();

        assertEquals(ChatChannels.State.ACTIVE, channels.state());
        assertEquals(1, registry.listeners.size());
        assertEquals(null, channels.brokenReason());
    }

    @Test
    @DisplayName("a hook still attached after close relays nothing")
    void closedHookIsSilent() {
        ZelChatChannels channels = active();
        List<EventListener<?>> attached = new ArrayList<EventListener<?>>(registry.listeners);
        channels.close();

        // ZelChat keeping a reference past unregister (or dispatching one already in flight).
        registry.listeners.addAll(attached);
        registry.fire(line(steve, "after close", ChannelType.EVERYONE));

        assertTrue(relayed.isEmpty());
    }

    @Test
    @DisplayName("a legacy line while another thread is still registering the hook is dropped")
    void registrationInProgress() throws Exception {
        environment.zelChat = plugin(true);
        environment.online = Arrays.asList(steve);
        publishApi();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        registry.gate = () -> {
            entered.countDown();
            await(release);
        };
        ZelChatChannels channels = channels();
        channels.registrationWaitMillis = 20;

        Thread registering = new Thread(() -> channels.attach(pipeline));
        registering.start();
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS), "registration never started");
            assertEquals(ChannelHook.UntaggedRelay.TRANSIENT, channels.untaggedRelay(steve),
                    "the hook may already be live: relaying untagged could publish staff chat");
            assertFalse(channels.members("global").isPresent());
        } finally {
            release.countDown();
            registering.join(5_000);
        }
        assertEquals(ChannelHook.UntaggedRelay.SKIP, channels.untaggedRelay(steve));
    }

    @Test
    @DisplayName("ZelChat's own getter throwing drops that line, says so, and stays active")
    void ownCodeThrowing() {
        ZelChatChannels channels = active();
        ChatPlayer chatPlayer = mock(ChatPlayer.class);
        when(chatPlayer.getBukkitPlayer()).thenThrow(new IllegalStateException("quit"));
        registry.fire(new AsyncPostMessageDispatchEvent(
                Component.text("secret"), ChannelType.EVERYONE, chatPlayer));

        assertTrue(relayed.isEmpty());
        assertEquals(ChatChannels.State.ACTIVE, channels.state());
        assertTrue(logger.logged(LogLevel.WARN, "ZelChat threw"), logger.records().toString());
        assertFalse(logger.records().toString().contains("secret"), "never with the text");
    }

    /** A ZelChat whose ChannelType lost a room it routes by. */
    private enum RenamedChannelType { EVERYONE, STAFF_ONLY }

    @Test
    @DisplayName("a ChannelType without EVERYONE or STAFF is a moved API, not silence")
    void missingRoomIsAMovedApi() throws Exception {
        ZelChatChannels.Api.requireConstants(ChannelType.class, "EVERYONE", "STAFF");
        org.junit.jupiter.api.Assertions.assertThrows(NoSuchFieldException.class,
                () -> ZelChatChannels.Api.requireConstants(
                        RenamedChannelType.class, "EVERYONE", "STAFF"));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("switched off: none, and legacy chat relays untagged as if it were not there")
    void disabled() {
        environment.zelChat = plugin(false);
        ZelChatChannels channels = channels();
        channels.attach(pipeline);

        assertTrue(channels.installed());
        assertEquals(ChatChannels.State.NONE, channels.state());
        assertEquals(ChannelHook.UntaggedRelay.RELAY, channels.untaggedRelay(steve));
    }

    @Test
    @DisplayName("a ZelChat whose API moved is broken, not none")
    void movedApi() {
        environment.zelChat = plugin(true);
        environment.loader = new ClassLoader(null) {
        };
        ZelChatChannels channels = channels();
        channels.attach(pipeline);

        assertEquals(ChatChannels.State.BROKEN, channels.state());
        assertEquals(ChannelHook.UntaggedRelay.BROKEN, channels.untaggedRelay(steve));
        assertNotNull(channels.brokenReason());
        assertTrue(logger.logged(LogLevel.SEVERE, "ZelChat channel integration disabled"),
                logger.records().toString());
    }

    // ── Outbound ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("public chat is relayed as global, with the component's plain text")
    void publicLine() {
        active();
        registry.fire(line(steve, "hello there", ChannelType.EVERYONE));

        assertEquals(1, relayed.size());
        ChatMessage message = relayed.get(0);
        assertEquals("global", message.channel());
        assertEquals("hello there", message.message());
        assertEquals("Steve", message.senderName());
    }

    @Test
    @DisplayName("staff chat is relayed as staff")
    void staffLine() {
        active();
        registry.fire(line(moderator, "ban him", ChannelType.STAFF));

        assertEquals(1, relayed.size());
        assertEquals("staff", relayed.get(0).channel());
    }

    @Test
    @DisplayName("town chat and private messages are never relayed")
    void customAndPrivate() {
        active();
        registry.fire(line(steve, "town business", ChannelType.CUSTOM));
        registry.fire(line(steve, "psst", ChannelType.PRIVATE));

        assertTrue(relayed.isEmpty());
    }

    @Test
    @DisplayName("a line whose sender is not online here was typed elsewhere: not relayed twice")
    void forwardedLine() {
        active();
        Player elsewhere = player("Elsewhere", false, false);
        registry.fire(line(elsewhere, "from the lobby", ChannelType.EVERYONE));

        assertTrue(relayed.isEmpty());
    }

    @Test
    @DisplayName("a muted player's line is not relayed, read-only")
    void mutedPlayer() {
        active();
        pipeline.register(message -> Verdict.deny(Component.text("muted")), 0, "test");
        registry.fire(line(steve, "let me speak", ChannelType.EVERYONE));

        assertTrue(relayed.isEmpty());
    }

    @Test
    @DisplayName("closing takes the hook off ZelChat's registry")
    void close() {
        ZelChatChannels channels = active();
        channels.close();

        assertTrue(registry.listeners.isEmpty());
        channels.state();
        assertTrue(registry.listeners.isEmpty(), "and nothing registers again after it");
    }

    @Test
    @DisplayName("while hooked, the legacy chat listener relays nothing: ZelChat's event does")
    void legacySkips() {
        ZelChatChannels channels = active();
        assertEquals(ChannelHook.UntaggedRelay.SKIP, channels.untaggedRelay(steve));
    }

    // ── Inbound ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("global reaches everyone online; staff reaches only staff-chat permission holders")
    void members() {
        ZelChatChannels channels = active();

        assertEquals(Arrays.asList("Steve", "Moderator"), names(channels.members("global").get()));
        assertEquals(Arrays.asList("Moderator"), names(channels.members("STAFF").get()),
                "matched case-insensitively: the name comes from a dashboard selection");
        assertFalse(channels.members("towny").isPresent(), "unknown is not 'nobody'");
    }

    @Test
    @DisplayName("nothing is routed by room until the hook is live")
    void membersNeedActive() {
        environment.zelChat = plugin(true);
        environment.online = Arrays.asList(steve, moderator);
        ZelChatChannels channels = channels();
        channels.attach(pipeline);

        assertFalse(channels.members("staff").isPresent());
    }
}
