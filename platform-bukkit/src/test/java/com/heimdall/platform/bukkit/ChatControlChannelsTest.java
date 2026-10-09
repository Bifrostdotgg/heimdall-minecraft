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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mineacademy.chatcontrol.api.ChannelPostChatEvent;
import org.mineacademy.chatcontrol.model.Channel;
import org.mineacademy.chatcontrol.model.ChannelMode;
import org.mineacademy.chatcontrol.settings.Settings;

/**
 * The reflective ChatControl integration, against stand-ins with ChatControl's exact class names
 * and signatures (see {@code org.mineacademy.chatcontrol} in this source set).
 *
 * <p>The point of the stand-ins is that the production lookups run unmodified: a renamed method
 * here fails the same way a renamed method in a ChatControl release would.
 */
class ChatControlChannelsTest {

    private final RecordingLogger logger = new RecordingLogger(true);
    private final FakeEnvironment environment = new FakeEnvironment();
    private final ChatPipeline pipeline = new ChatPipeline(logger);
    private final List<ChatMessage> relayed = new ArrayList<ChatMessage>();

    private static final Function<Player, PlayerHandle> HANDLES =
            new Function<Player, PlayerHandle>() {
                @Override
                public PlayerHandle apply(Player player) {
                    return new FakePlayer(player.getUniqueId(), player.getName());
                }
            };

    /** Steerable stand-in for the server. */
    private static final class FakeEnvironment implements ChatControlChannels.Environment {

        volatile Plugin chatControl;
        volatile ClassLoader loader = ChatControlChannelsTest.class.getClassLoader();
        volatile RuntimeException registerFailure;
        final List<Class<? extends Event>> registered = new ArrayList<Class<? extends Event>>();
        volatile EventExecutor executor;
        int lookups;

        @Override
        public Plugin findChatControl() {
            lookups++;
            return chatControl;
        }

        @Override
        public ClassLoader loaderOf(Plugin plugin) {
            return loader;
        }

        @Override
        public void register(
                Class<? extends Event> type, Listener listener, EventExecutor executor) {
            if (registerFailure != null) {
                throw registerFailure;
            }
            registered.add(type);
            this.executor = executor;
        }

        /** Fires an event at the registered hook, as Bukkit would. */
        void fire(Event event) throws Exception {
            assertNotNull(executor, "no hook registered");
            executor.execute(null, event);
        }
    }

    @BeforeEach
    void cleanSlate() {
        Channel.reset();
        Settings.Channels.ENABLED = null;
        pipeline.observe(relayed::add);
    }

    @AfterEach
    void tearDown() {
        Channel.reset();
        Settings.Channels.ENABLED = null;
    }

    private static Plugin plugin(boolean enabled) {
        Plugin plugin = mock(Plugin.class);
        when(plugin.isEnabled()).thenReturn(enabled);
        return plugin;
    }

    private static Player player(String name) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.nameUUIDFromBytes(name.getBytes()));
        when(player.getName()).thenReturn(name);
        return player;
    }

    private ChatControlChannels channels() {
        return new ChatControlChannels(logger, environment, HANDLES, 0L);
    }

    /** ChatControl installed, enabled, channels on, hook attached. */
    private ChatControlChannels active() {
        environment.chatControl = plugin(true);
        Settings.Channels.ENABLED = Boolean.TRUE;
        ChatControlChannels channels = channels();
        channels.attach(pipeline);
        return channels;
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
    @DisplayName("without ChatControl: not installed, state none, nothing registered")
    void absent() {
        ChatControlChannels channels = channels();
        channels.attach(pipeline);

        assertFalse(channels.installed());
        assertEquals(ChatChannels.State.NONE, channels.state());
        assertTrue(channels.channelNames().isEmpty());
        assertFalse(channels.members("staff").isPresent());
        assertTrue(environment.registered.isEmpty());
        assertTrue(logger.records().isEmpty(), "a server without ChatControl hears nothing about it");
    }

    @Test
    @DisplayName("a ChatControl that appears later is picked up: a miss is never cached for good")
    void retriesWhileMissing() {
        ChatControlChannels channels = channels();
        channels.attach(pipeline);
        assertFalse(channels.installed());

        environment.chatControl = plugin(true);
        Settings.Channels.ENABLED = Boolean.TRUE;

        assertTrue(channels.installed());
        assertEquals(ChatChannels.State.ACTIVE, channels.state());
        assertEquals(1, environment.registered.size(), "and the hook registers on that later call");
    }

    @Test
    @DisplayName("a miss is re-asked at most once per retry interval, not once per chat line")
    void missesAreThrottled() {
        ChatControlChannels channels =
                new ChatControlChannels(logger, environment, HANDLES, 60_000_000_000L);

        for (int i = 0; i < 50; i++) {
            channels.installed();
        }

        assertEquals(1, environment.lookups,
                "getPlugin is synchronized on the plugin manager; asking per line would contend "
                        + "with every synchronous event the main thread fires");
    }

    @Test
    @DisplayName("installed but not enabled yet: none, and nothing is touched")
    void installedButNotEnabled() {
        environment.chatControl = plugin(false);
        Settings.Channels.ENABLED = Boolean.TRUE;
        ChatControlChannels channels = channels();
        channels.attach(pipeline);

        assertTrue(channels.installed(), "presence is what moves relay out of the NORMAL handler");
        assertEquals(ChatChannels.State.NONE, channels.state());
        assertTrue(environment.registered.isEmpty());
    }

    @Test
    @DisplayName("channels switched off in ChatControl: none, but the hook is already in place")
    void channelsOff() {
        environment.chatControl = plugin(true);
        Settings.Channels.ENABLED = Boolean.FALSE;
        Channel.create("global");
        ChatControlChannels channels = channels();
        channels.attach(pipeline);

        assertEquals(ChatChannels.State.NONE, channels.state());
        assertTrue(channels.channelNames().isEmpty(), "names only while active");
        assertEquals(1, environment.registered.size(),
                "registered regardless, so a reload that turns channels on needs nothing more");

        Settings.Channels.ENABLED = Boolean.TRUE;
        assertEquals(ChatChannels.State.ACTIVE, channels.state());
        assertEquals(1, environment.registered.size(), "and never twice");
    }

    @Test
    @DisplayName("settings not loaded yet (ENABLED is null): none, not a crash")
    void settingsNotLoaded() {
        environment.chatControl = plugin(true);
        ChatControlChannels channels = channels();
        channels.attach(pipeline);

        assertEquals(ChatChannels.State.NONE, channels.state());
        assertEquals(ChatControlChannels.UntaggedRelay.RELAY,
                channels.untaggedRelay(player("Steve")),
                "and isUsingChannels, which would throw on a null ENABLED, is not called");
    }

    @Test
    @DisplayName("before the pipeline is attached, nothing claims to be active")
    void notActiveUntilHooked() {
        environment.chatControl = plugin(true);
        Settings.Channels.ENABLED = Boolean.TRUE;
        ChatControlChannels channels = channels();

        assertEquals(ChatChannels.State.NONE, channels.state(),
                "active promises a live hook, and there is nothing to feed one yet");
        channels.attach(pipeline);
        assertEquals(ChatChannels.State.ACTIVE, channels.state());
    }

    @Test
    @DisplayName("active: the names are ChatControl's, verbatim and in order")
    void activeNames() {
        Channel.create("global");
        Channel.create("Staff Chat");
        ChatControlChannels channels = active();

        assertEquals(ChatChannels.State.ACTIVE, channels.state());
        assertEquals(Arrays.asList("global", "Staff Chat"), channels.channelNames());
    }

    @Test
    @DisplayName("members are matched case-insensitively and wrapped as handles")
    void activeMembers() {
        Player mod = player("Mod");
        Player admin = player("Admin");
        Channel.create("global");
        Channel.create("Staff").member(mod, ChannelMode.WRITE).member(admin, ChannelMode.READ);
        ChatControlChannels channels = active();

        Optional<Collection<PlayerHandle>> members = channels.members("staff");

        assertTrue(members.isPresent(),
                "ChatControl's own lookup is exact here, so this proves the canonical spelling is "
                        + "resolved first");
        assertEquals(Arrays.asList("Mod", "Admin"), names(members.get()),
                "members in any mode, reading as well as writing");
        assertTrue(channels.members("global").get().isEmpty(),
                "a channel with nobody in it is present and empty");
        assertFalse(channels.members("nope").isPresent(), "an unknown channel is absent");
        assertFalse(channels.members(null).isPresent());
    }

    // ── The hook ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a channel line is relayed with its channel, through the observers")
    void hookRelaysWithChannel() throws Exception {
        Player steve = player("Steve");
        Channel staff = Channel.create("staff");
        active();

        environment.fire(new ChannelPostChatEvent(staff, steve, "  §chello  ", false));

        assertEquals(1, relayed.size());
        assertEquals("staff", relayed.get(0).channel());
        assertEquals("  §chello  ", relayed.get(0).message(), "verbatim, as everywhere else");
        assertEquals("Steve", relayed.get(0).senderName());
        assertEquals(Collections.<Class<?>>singletonList(ChannelPostChatEvent.class),
                new ArrayList<Class<?>>(environment.registered));
    }

    @Test
    @DisplayName("a silently cancelled line is never relayed")
    void silentlyCancelledIsNotRelayed() throws Exception {
        Channel staff = Channel.create("staff");
        active();

        environment.fire(new ChannelPostChatEvent(staff, player("Steve"), "spam", true));

        assertTrue(relayed.isEmpty(),
                "ChatControl showed it to the sender only; Discord must not see more than the "
                        + "server did");
    }

    @Test
    @DisplayName("only a player is chat: console posts into a channel are not relayed")
    void nonPlayerSenderIsNotRelayed() throws Exception {
        Channel staff = Channel.create("staff");
        active();

        environment.fire(new ChannelPostChatEvent(staff, mock(CommandSender.class), "hi", false));

        assertTrue(relayed.isEmpty());
    }

    @Test
    @DisplayName("a muted player's channel line is not relayed, and ChatControl's event is untouched")
    void deniedIsNotRelayedAndNotCancelled() throws Exception {
        pipeline.register(message -> Verdict.deny(Component.text("muted")), 0, "test");
        Channel staff = Channel.create("staff");
        active();

        ChannelPostChatEvent event = new ChannelPostChatEvent(staff, player("Steve"), "hi", false);
        environment.fire(event);

        assertTrue(relayed.isEmpty());
        assertFalse(event.isCancelled(),
                "the verdict is read-only here: ChatControl already decided to deliver it");
    }

    @Test
    @DisplayName("an event of another type reaching the executor is ignored")
    void foreignEventIgnored() throws Exception {
        active();

        environment.fire(mock(Event.class));

        assertTrue(relayed.isEmpty());
    }

    // ── Untagged decisions ───────────────────────────────────────────────────

    @Test
    @DisplayName("the legacy listener relays a non-channel player, and skips a channel player")
    void untaggedRelayDecisions() {
        Player inChannel = player("InChannel");
        Player outside = player("Outside");
        Channel.using(inChannel, true);
        ChatControlChannels channels = active();

        assertEquals(ChatControlChannels.UntaggedRelay.SKIP, channels.untaggedRelay(inChannel));
        assertEquals(ChatControlChannels.UntaggedRelay.RELAY, channels.untaggedRelay(outside));

        Settings.Channels.ENABLED = Boolean.FALSE;
        assertEquals(ChatControlChannels.UntaggedRelay.RELAY, channels.untaggedRelay(inChannel),
                "with channels off, ChatControl routes nobody into one");
    }

    // ── Broken ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an API that moved is BROKEN, logged once, and fails closed everywhere")
    void missingApiIsBroken() {
        environment.chatControl = plugin(true);
        environment.loader = new ClassLoader(null) {
        };
        Settings.Channels.ENABLED = Boolean.TRUE;
        ChatControlChannels channels = channels();
        channels.attach(pipeline);

        for (int i = 0; i < 5; i++) {
            assertEquals(ChatChannels.State.BROKEN, channels.state());
        }
        assertTrue(channels.installed());
        assertTrue(channels.channelNames().isEmpty());
        assertFalse(channels.members("staff").isPresent());
        assertEquals(ChatControlChannels.UntaggedRelay.BROKEN,
                channels.untaggedRelay(player("Steve")));
        assertEquals(1, logger.at(LogLevel.SEVERE).size(),
                "one error naming the cause, not one per call: " + logger.records());
        assertTrue(channels.brokenReason().contains("ClassNotFoundException"),
                channels.brokenReason());
    }

    @Test
    @DisplayName("a hook that cannot be registered is BROKEN")
    void registrationFailureIsBroken() {
        environment.chatControl = plugin(true);
        environment.registerFailure = new IllegalStateException("plugin not enabled");
        Settings.Channels.ENABLED = Boolean.TRUE;
        ChatControlChannels channels = channels();
        channels.attach(pipeline);

        assertEquals(ChatChannels.State.BROKEN, channels.state());
        assertTrue(logger.logged(LogLevel.SEVERE, "ChatControl channel integration disabled"));
    }

    // ── Reloads ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ChatControl reloaded by a plugin manager: BROKEN, with a reason that says restart")
    void reloadedIsBroken() {
        ChatControlChannels channels = active();
        assertEquals(ChatChannels.State.ACTIVE, channels.state());

        when(environment.chatControl.isEnabled()).thenReturn(false);
        environment.chatControl = plugin(true);

        assertEquals(ChatChannels.State.BROKEN, channels.state(),
                "the hook listens on the old copy's event class; the new copy would route staff "
                        + "lines unobserved while this said none");
        assertTrue(channels.installed(), "and relay stays out of the NORMAL handler");
        assertTrue(channels.brokenReason().contains("reloaded"), channels.brokenReason());
        assertTrue(channels.brokenReason().contains("restart"), channels.brokenReason());
    }

    @Test
    @DisplayName("ChatControl unloaded after being hooked: BROKEN, not none")
    void unloadedIsBroken() {
        ChatControlChannels channels = active();

        when(environment.chatControl.isEnabled()).thenReturn(false);
        environment.chatControl = null;

        assertEquals(ChatChannels.State.BROKEN, channels.state());
        assertTrue(channels.brokenReason().contains("unloaded"), channels.brokenReason());
    }

    @Test
    @DisplayName("ChatControl switched off in place: none, and nothing is reported broken")
    void disabledInPlaceIsNone() {
        ChatControlChannels channels = active();
        Plugin same = environment.chatControl;

        when(same.isEnabled()).thenReturn(false);
        assertEquals(ChatChannels.State.NONE, channels.state(),
                "the same copy, not routing chat; switched back on it is the same classes");

        when(same.isEnabled()).thenReturn(true);
        assertEquals(ChatChannels.State.ACTIVE, channels.state());
        assertTrue(logger.at(LogLevel.SEVERE).isEmpty(), logger.records().toString());
    }

    @Test
    @DisplayName("a replacement before anything was bound is simply adopted")
    void replacedBeforeBindingIsAdopted() {
        environment.chatControl = plugin(false);
        Settings.Channels.ENABLED = Boolean.TRUE;
        ChatControlChannels channels = channels();
        channels.attach(pipeline);
        assertEquals(ChatChannels.State.NONE, channels.state());

        environment.chatControl = plugin(true);

        assertEquals(ChatChannels.State.ACTIVE, channels.state(),
                "nothing was bound to the first copy, so there is nothing stale to protect");
    }

    // ── One-call failures ────────────────────────────────────────────────────

    @Test
    @DisplayName("ChatControl's own code throwing while listing members fails that call only")
    void throwingMembersIsTransient() {
        Player mod = player("Mod");
        Channel.create("staff").member(mod, ChannelMode.WRITE);
        ChatControlChannels channels = active();

        Channel.failOnlinePlayers = new IllegalStateException("correct-horse quit mid-lookup");
        assertFalse(channels.members("staff").isPresent(), "fail closed for this call");
        assertFalse(channels.members("staff").isPresent());
        assertEquals(ChatChannels.State.ACTIVE, channels.state(), "and only this call");

        Channel.failOnlinePlayers = null;
        assertEquals(Collections.singletonList("Mod"), names(channels.members("staff").get()));

        assertEquals(1, logger.at(LogLevel.WARN).size(),
                "rate-limited: one warning for the burst, not one per call: " + logger.records());
        assertTrue(logger.at(LogLevel.SEVERE).isEmpty(), logger.records().toString());
        assertFalse(logger.records().toString().contains("correct-horse"),
                "the exception's class is named, never its message: " + logger.records());
    }

    @Test
    @DisplayName("isUsingChannels throwing answers TRANSIENT, not BROKEN")
    void throwingUsingIsTransient() {
        ChatControlChannels channels = active();

        Channel.failUsing = new IllegalStateException("no player cache yet");

        assertEquals(ChatControlChannels.UntaggedRelay.TRANSIENT,
                channels.untaggedRelay(player("Steve")));
        assertEquals(ChatChannels.State.ACTIVE, channels.state());
    }

    @Test
    @DisplayName("a channel event whose getter throws drops that line, and the next one relays")
    void throwingGetterDropsOneLine() throws Exception {
        Channel staff = Channel.create("staff");
        ChatControlChannels channels = active();

        ChannelPostChatEvent.failGetMessage = new IllegalStateException("boom");
        try {
            environment.fire(new ChannelPostChatEvent(staff, player("Steve"), "first", false));
        } finally {
            ChannelPostChatEvent.failGetMessage = null;
        }
        environment.fire(new ChannelPostChatEvent(staff, player("Steve"), "second", false));

        assertEquals(1, relayed.size());
        assertEquals("second", relayed.get(0).message());
        assertEquals(ChatChannels.State.ACTIVE, channels.state());
    }

    @Test
    @DisplayName("listing channels throwing keeps the last good names, so a poll sends nothing new")
    void throwingNamesKeepsTheLastInventory() {
        Channel.create("global");
        Channel.create("staff");
        ChatControlChannels channels = active();
        assertEquals(Arrays.asList("global", "staff"), channels.channelNames());

        Channel.failChannelNames = new IllegalStateException("mid-reload");

        assertEquals(ChatChannels.State.ACTIVE, channels.state());
        assertEquals(Arrays.asList("global", "staff"), channels.channelNames(),
                "{active, []} would tell the bot every channel vanished");
    }

    @Test
    @DisplayName("listing channels throwing before any good read answers empty, not a guess")
    void throwingNamesWithNoHistoryIsEmpty() {
        Channel.create("global");
        ChatControlChannels channels = active();
        Channel.failChannelNames = new IllegalStateException("not loaded yet");

        assertTrue(channels.channelNames().isEmpty());
        assertEquals(ChatChannels.State.ACTIVE, channels.state());
    }

    @Test
    @DisplayName("a listing that breaks the integration answers empty, never the last good names")
    void brokenListingIsEmpty() {
        Channel.create("global");
        ChatControlChannels channels = active();
        assertEquals(Collections.singletonList("global"), channels.channelNames());

        // Heimdall's own handling of the answer fails (not ChatControl's code), which is a
        // permanent failure rather than a one-call one.
        Channel.namesOverride = Collections.singletonList(new Object() {
            @Override
            public String toString() {
                throw new IllegalStateException("not a name");
            }
        });

        assertTrue(channels.channelNames().isEmpty(),
                "a broken integration has no inventory; the last good one would be a guess");
        assertEquals(ChatChannels.State.BROKEN, channels.state());
    }

    @Test
    @DisplayName("a reflective failure that is not ChatControl's own code throwing is permanent")
    void nonInvocationFailuresArePermanent() {
        for (Throwable moved : Arrays.<Throwable>asList(
                new IllegalArgumentException("object is not an instance of declaring class"),
                new IllegalAccessException("not public"),
                new NoSuchMethodError("getOnlinePlayers"),
                new ClassCastException("not a Channel"),
                new NullPointerException("static method became an instance method"))) {
            logger.clear();
            ChatControlChannels channels = active();

            assertTrue(channels.fail("reading something", moved), moved.toString());
            assertEquals(ChatChannels.State.BROKEN, channels.state(), moved.toString());
            assertEquals(ChatChannels.State.BROKEN, channels.state(), "and it stays broken");
        }
    }

    @Test
    @DisplayName("only an InvocationTargetException is a one-call failure")
    void invocationTargetIsTransient() {
        ChatControlChannels channels = active();

        assertFalse(channels.fail("reading something", new java.lang.reflect
                .InvocationTargetException(new IllegalStateException("player quit"))));
        assertEquals(ChatChannels.State.ACTIVE, channels.state());
    }

    @Test
    @DisplayName("an Error from Heimdall's own pipeline is not blamed on ChatControl")
    void pipelineErrorDoesNotBreakTheIntegration() throws Exception {
        pipeline.register(message -> {
            throw new AssertionError("a Heimdall bug");
        }, 0, "test");
        Channel staff = Channel.create("staff");
        ChatControlChannels channels = active();

        environment.fire(new ChannelPostChatEvent(staff, player("Steve"), "hi", false));

        assertEquals(ChatChannels.State.ACTIVE, channels.state(),
                "pausing relay for the rest of the boot and naming ChatControl would be wrong twice");
        assertTrue(logger.logged(LogLevel.SEVERE, "the chat pipeline threw"),
                logger.records().toString());
    }

    @Test
    @DisplayName("nothing it logs carries a message body")
    void logsCarryNoChatText() throws Exception {
        Channel staff = Channel.create("staff");
        active();

        environment.fire(new ChannelPostChatEvent(staff, player("Steve"),
                "correct-horse-battery-staple", false));

        assertFalse(logger.records().toString().contains("correct-horse"),
                logger.records().toString());
    }
}
