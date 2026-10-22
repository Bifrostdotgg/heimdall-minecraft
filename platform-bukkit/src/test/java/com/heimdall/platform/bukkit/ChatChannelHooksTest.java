package com.heimdall.platform.bukkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.heimdall.core.log.RecordingLogger;
import com.heimdall.core.pipeline.ChatMessage;
import com.heimdall.core.pipeline.ChatPipeline;
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
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mineacademy.chatcontrol.api.ChannelPostChatEvent;
import org.mineacademy.chatcontrol.model.Channel;
import org.mineacademy.chatcontrol.settings.Settings;

/**
 * Choosing between ChatControl and ZelChat: whichever is installed answers, neither is "none", and
 * both at once is broken with every relay vetoed. Departure D92.
 */
class ChatChannelHooksTest {

    private final RecordingLogger logger = new RecordingLogger(true);
    private final ChatPipeline pipeline = new ChatPipeline(logger);
    private final List<ChatMessage> relayed = new ArrayList<ChatMessage>();

    private static final Function<Player, PlayerHandle> HANDLES =
            new Function<Player, PlayerHandle>() {
                @Override
                public PlayerHandle apply(Player player) {
                    return new FakePlayer(player.getUniqueId(), player.getName());
                }
            };

    private volatile Plugin chatControl;
    private volatile Plugin zelChat;
    private volatile EventExecutor chatControlHook;
    private final List<EventListener<?>> zelChatHooks = new ArrayList<EventListener<?>>();

    private final Player steve = player("Steve");

    private final ChatControlChannels.Environment chatControlEnvironment =
            new ChatControlChannels.Environment() {
                @Override
                public Plugin findChatControl() {
                    return chatControl;
                }

                @Override
                public ClassLoader loaderOf(Plugin plugin) {
                    return ChatChannelHooksTest.class.getClassLoader();
                }

                @Override
                public void register(Class<? extends org.bukkit.event.Event> type,
                        Listener listener, EventExecutor executor) {
                    chatControlHook = executor;
                }
            };

    private final ZelChatChannels.Environment zelChatEnvironment =
            new ZelChatChannels.Environment() {
                @Override
                public Plugin findZelChat() {
                    return zelChat;
                }

                @Override
                public ClassLoader loaderOf(Plugin plugin) {
                    return ChatChannelHooksTest.class.getClassLoader();
                }

                @Override
                public Collection<? extends Player> onlinePlayers() {
                    return Arrays.asList(steve);
                }
            };

    @BeforeEach
    void setUp() {
        Channel.reset();
        Settings.Channels.ENABLED = null;
        pipeline.observe(relayed::add);
        ZelChatAPI api = mock(ZelChatAPI.class);
        when(api.getEventRegistry()).thenReturn(new EventRegistry() {
            @Override
            public <E extends Event> void register(Class<E> type, EventListener<E> listener) {
                zelChatHooks.add(listener);
            }

            @Override
            public <E extends Event> void unregister(Class<E> type, EventListener<E> listener) {
                zelChatHooks.remove(listener);
            }
        });
        ZelChatProvider.set(api);
    }

    @AfterEach
    void tearDown() {
        Channel.reset();
        Settings.Channels.ENABLED = null;
        ZelChatProvider.set(null);
    }

    private static Plugin plugin() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.isEnabled()).thenReturn(true);
        return plugin;
    }

    private static Player player(String name) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.nameUUIDFromBytes(name.getBytes()));
        when(player.getName()).thenReturn(name);
        when(player.isOnline()).thenReturn(true);
        return player;
    }

    private ChatChannelHooks hooks() {
        ChatChannelHooks hooks = new ChatChannelHooks(
                new ChatControlChannels(logger, chatControlEnvironment, HANDLES, 0L),
                new ZelChatChannels(logger, zelChatEnvironment, HANDLES, 0L));
        hooks.attach(pipeline);
        return hooks;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void fireZelChat(Player sender, ChannelType type) {
        ChatPlayer chatPlayer = mock(ChatPlayer.class);
        when(chatPlayer.getBukkitPlayer()).thenReturn(sender);
        AsyncPostMessageDispatchEvent event =
                new AsyncPostMessageDispatchEvent(Component.text("hi"), type, chatPlayer);
        for (EventListener listener : new ArrayList<EventListener<?>>(zelChatHooks)) {
            listener.onEvent(event);
        }
    }

    @Test
    @DisplayName("neither installed: none, nothing named, legacy chat relays as always")
    void neither() {
        ChatChannelHooks hooks = hooks();

        assertFalse(hooks.installed());
        assertEquals(ChatChannels.State.NONE, hooks.state());
        assertNull(hooks.provider());
        assertEquals(ChannelHook.UntaggedRelay.RELAY, hooks.untaggedRelay(steve));
    }

    @Test
    @DisplayName("ZelChat alone answers for itself")
    void zelChatOnly() {
        zelChat = plugin();
        ChatChannelHooks hooks = hooks();

        assertEquals(ChatChannels.State.ACTIVE, hooks.state());
        assertEquals("zelchat", hooks.provider());
        assertEquals(Arrays.asList("global", "staff"), hooks.channelNames());
        assertEquals("ZelChat", hooks.pluginName());
    }

    @Test
    @DisplayName("ChatControl alone answers for itself")
    void chatControlOnly() {
        chatControl = plugin();
        Settings.Channels.ENABLED = Boolean.TRUE;
        Channel.create("staff");
        ChatChannelHooks hooks = hooks();

        assertEquals(ChatChannels.State.ACTIVE, hooks.state());
        assertEquals("chatcontrol", hooks.provider());
        assertEquals(Arrays.asList("staff"), hooks.channelNames());
    }

    @Test
    @DisplayName("both installed: broken, named in the reason, and neither hook relays anything")
    void both() throws Exception {
        chatControl = plugin();
        zelChat = plugin();
        Settings.Channels.ENABLED = Boolean.TRUE;
        Channel staff = Channel.create("staff");
        ChatChannelHooks hooks = hooks();

        assertTrue(hooks.installed());
        assertEquals(ChatChannels.State.BROKEN, hooks.state());
        assertEquals("multiple", hooks.provider(), "so the bot does not read it as ChatControl");
        assertTrue(hooks.channelNames().isEmpty());
        assertFalse(hooks.members("staff").isPresent());
        assertEquals(ChannelHook.UntaggedRelay.BROKEN, hooks.untaggedRelay(steve));
        assertTrue(hooks.brokenReason().contains("ChatControl and ZelChat"), hooks.brokenReason());

        fireZelChat(steve, ChannelType.EVERYONE);
        chatControlHook.execute(null, new ChannelPostChatEvent(staff, steve, "hello", false));

        assertTrue(relayed.isEmpty(),
                "two plugins routing chat: which audience a line is for cannot be told");
    }

    @Test
    @DisplayName("a disabled ChatControl jar beside a working ZelChat is not a conflict")
    void disabledJarIsNotAConflict() {
        Plugin disabled = mock(Plugin.class);
        when(disabled.isEnabled()).thenReturn(false);
        chatControl = disabled;
        zelChat = plugin();
        ChatChannelHooks hooks = hooks();

        assertEquals(ChatChannels.State.ACTIVE, hooks.state());
        assertEquals("zelchat", hooks.provider());
        fireZelChat(steve, ChannelType.EVERYONE);
        assertEquals(1, relayed.size());
    }
}
