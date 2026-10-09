package com.heimdall.module.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.concurrent.HeimdallExecutors;
import com.heimdall.core.config.ServerRole;
import com.heimdall.core.json.Payload;
import com.heimdall.core.log.RecordingLogger;
import com.heimdall.core.module.ModuleEnvironment;
import com.heimdall.core.module.ModuleManager;
import com.heimdall.core.pipeline.ChatMessage;
import com.heimdall.core.pipeline.ChatPipeline;
import com.heimdall.core.pipeline.Interceptor;
import com.heimdall.core.pipeline.LoginPipeline;
import com.heimdall.core.pipeline.Verdict;
import com.heimdall.core.platform.ChatChannels;
import com.heimdall.core.platform.PlayerHandle;
import com.heimdall.core.remoteconfig.ConfigDocument;
import com.heimdall.core.remoteconfig.RemoteConfig;
import com.heimdall.core.session.PlayerSessionEvents;
import com.heimdall.core.testing.FakePlatform;
import com.heimdall.core.testing.FakePlayer;
import com.heimdall.core.testing.RecordingTunnelBus;
import com.heimdall.core.tunnel.Capabilities;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Driven through the real {@link ModuleManager}, for the same reason the console module's suite is:
 * the behaviours that matter most — one observer on enable, none after disable, no doubling on
 * re-enable, the {@code bridge.discord} subscription unwound — are as much about how the manager
 * wires this module up as about its own code, and a hand-built {@code ModuleContext} would not
 * exercise that.
 *
 * <p>{@link HeimdallBridgeModule#flush} is called directly rather than waiting on the real
 * one-second scheduler tick; it is package-private for exactly that reason.
 *
 * <p>Chat is fed through the <strong>real</strong> {@link ChatPipeline}, not by calling the observer
 * by hand. Half the relay-only guarantee lives in that class ("observers run only for messages that
 * were allowed"), and a test that bypassed it would prove nothing about the property it depends on.
 */
class HeimdallBridgeModuleTest {

    private static final Executor INLINE = new Executor() {
        @Override
        public void execute(Runnable command) {
            command.run();
        }
    };

    private final RecordingLogger logger = new RecordingLogger(true);

    @TempDir
    Path dataDir;

    private HeimdallExecutors executors;
    private FakePlatform platform;
    private RecordingTunnelBus tunnel;
    private RemoteConfig remoteConfig;
    private ChatPipeline chatPipeline;
    private PlayerSessionEvents sessions;
    private ModuleManager manager;
    private HeimdallBridgeModule module;
    private int configVersion;

    /**
     * Immediate drains the module asked for, held until a test runs them. Every other test then sees
     * the old, deterministic shape: nothing ships until it calls flush() or runDrains().
     */
    private final List<Runnable> pendingDrains = new ArrayList<Runnable>();

    /** Builds the whole rig for a role, since the relay default depends on it. */
    private void setUp(ServerRole role, Payload settings) {
        executors = new HeimdallExecutors(logger, 1);
        platform = new FakePlatform(role, dataDir);
        tunnel = new RecordingTunnelBus();
        configVersion = 0;
        remoteConfig = new RemoteConfig(
                logger, dataDir.resolve("remote-config-" + role + ".json"), ConfigDocument.empty());
        applySettings(settings);
        chatPipeline = new ChatPipeline(logger);
        // Inline, so a join is observable without a latch. The off-thread dispatch is core's
        // property and is pinned in core's own tests.
        sessions = new PlayerSessionEvents(logger, INLINE);
        manager = new ModuleManager(ModuleEnvironment.builder()
                .logger(logger)
                .executors(executors)
                .tunnel(tunnel)
                .remoteConfig(remoteConfig)
                .loginPipeline(new LoginPipeline(logger))
                .chatPipeline(chatPipeline)
                .playerSessions(sessions)
                .platform(platform)
                .build());
        module = new HeimdallBridgeModule();
        pendingDrains.clear();
        module.drainExecutorForTests(new java.util.concurrent.Executor() {
            @Override
            public void execute(Runnable command) {
                pendingDrains.add(command);
            }
        });
        manager.register(module);
    }

    /** Runs the drains requested so far, including any a drain itself requests. */
    private void runDrains() {
        while (!pendingDrains.isEmpty()) {
            pendingDrains.remove(0).run();
        }
    }

    /**
     * Pushes a config document carrying only this module's entry, exactly as {@code config.push}
     * would.
     *
     * <p>The version increments on every call, and that is not decoration: {@code RemoteConfig}
     * drops a push whose version it has already seen — equal counts as seen — so a second call with
     * the same number would be silently ignored and the live-toggle test would pass vacuously.
     */
    private void applySettings(Payload settings) {
        remoteConfig.onConfigPush(Payload.builder()
                .put("version", ++configVersion)
                .put("modules", Payload.builder()
                        .put(HeimdallBridgeModule.ID, Payload.builder()
                                .put("enabled", true)
                                .put("settings", settings == null ? Payload.empty() : settings)
                                .build())
                        .build())
                .build());
    }

    @AfterEach
    void tearDown() {
        if (executors != null) {
            executors.shutdown(1000);
        }
    }

    private void enable() {
        manager.reconcile(Collections.singleton(HeimdallBridgeModule.ID));
    }

    private void disable() {
        manager.reconcile(Collections.<String>emptySet());
    }

    private void sayIn(String channel, String name, String message) {
        chatPipeline.dispatchWithObservers(ChatMessage.inChannel(
                UUID.nameUUIDFromBytes(name.getBytes()), name, message, channel));
    }

    private static Payload chatChannels(String... names) {
        return Payload.builder()
                .putStrings(HeimdallBridgeModule.SETTING_CHAT_CHANNELS, Arrays.asList(names))
                .build();
    }

    /** The {@code bridge.chat} lines across every frame sent. */
    private List<Payload> relayedLines() {
        List<Payload> lines = new ArrayList<Payload>();
        for (RecordingTunnelBus.Sent sent : tunnel.sent(HeimdallBridgeModule.FRAME_CHAT)) {
            lines.addAll(sent.payload().children("lines"));
        }
        return lines;
    }

    /**
     * A chat plugin's channels, steerable from a test.
     *
     * <p>Answers the way the interface promises the real integration does: names and members only
     * while {@code active}, channel lookup case-insensitive.
     */
    private static final class FakeChannels implements ChatChannels {

        private volatile State state = State.ACTIVE;
        private volatile boolean membersIgnoreState;

        /** When set, every call records whether the caller held this monitor. */
        private volatile Object watchedLock;
        private volatile int callsUnderWatchedLock;
        private volatile int calls;

        FakeChannels watching(Object lock) {
            this.watchedLock = lock;
            return this;
        }

        /**
         * When armed, the next {@link #state()} captures the state at entry, signals
         * {@link #entered}, and waits for {@link #release} before returning what it captured: a
         * slow read whose answer is already stale by the time it is used.
         */
        private volatile boolean blockNextState;
        private final java.util.concurrent.CountDownLatch entered =
                new java.util.concurrent.CountDownLatch(1);
        private final java.util.concurrent.CountDownLatch release =
                new java.util.concurrent.CountDownLatch(1);

        FakeChannels blockingNextState() {
            this.blockNextState = true;
            return this;
        }

        private void observeCaller() {
            calls++;
            Object lock = watchedLock;
            if (lock != null && Thread.holdsLock(lock)) {
                callsUnderWatchedLock++;
            }
        }
        private final Map<String, List<PlayerHandle>> channels =
                Collections.synchronizedMap(new LinkedHashMap<String, List<PlayerHandle>>());

        FakeChannels state(State value) {
            this.state = value;
            return this;
        }

        /**
         * Answers members whatever the state says, which the interface forbids. Exists to prove the
         * bridge applies its own ACTIVE gate rather than trusting the integration to.
         */
        FakeChannels lyingAboutMembers() {
            this.membersIgnoreState = true;
            return this;
        }

        FakeChannels channel(String name, PlayerHandle... members) {
            channels.put(name, Arrays.asList(members));
            return this;
        }

        @Override
        public State state() {
            observeCaller();
            State captured = state;
            if (blockNextState) {
                blockNextState = false;
                entered.countDown();
                try {
                    release.await(10, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return captured;
        }

        @Override
        public List<String> channelNames() {
            observeCaller();
            if (state != State.ACTIVE) {
                return Collections.emptyList();
            }
            synchronized (channels) {
                return Collections.unmodifiableList(new ArrayList<String>(channels.keySet()));
            }
        }

        @Override
        public Optional<Collection<PlayerHandle>> members(String channel) {
            if ((state != State.ACTIVE && !membersIgnoreState) || channel == null) {
                return Optional.empty();
            }
            synchronized (channels) {
                for (Map.Entry<String, List<PlayerHandle>> entry : channels.entrySet()) {
                    if (entry.getKey().equalsIgnoreCase(channel)) {
                        return Optional.<Collection<PlayerHandle>>of(entry.getValue());
                    }
                }
            }
            return Optional.empty();
        }
    }

    private void say(String name, String message) {
        chatPipeline.dispatchWithObservers(
                ChatMessage.of(UUID.nameUUIDFromBytes(name.getBytes()), name, message));
    }

    private static Payload relayChat(boolean value) {
        return Payload.builder().put(HeimdallBridgeModule.SETTING_RELAY_CHAT, value).build();
    }

    private static Payload relayEvents(boolean value) {
        return Payload.builder().put(HeimdallBridgeModule.SETTING_RELAY_EVENTS, value).build();
    }

    private static Payload relaySettings(boolean chat, boolean events) {
        return Payload.builder()
                .put(HeimdallBridgeModule.SETTING_RELAY_CHAT, chat)
                .put(HeimdallBridgeModule.SETTING_RELAY_EVENTS, events)
                .build();
    }

    /** One of each kind, which is what "all three are gated" has to be asserted against. */
    private void allThreeKinds() {
        sessions.join(FakePlayer.named("Steve"), 1000L);
        sessions.death(FakePlayer.named("Steve"), "Steve fell from a high place", 1500L);
        sessions.quit(FakePlayer.named("Steve"), 2000L);
    }

    /** The event kinds that reached the wire across every {@code bridge.event} frame sent. */
    private List<String> relayedEventKinds() {
        List<String> kinds = new java.util.ArrayList<String>();
        for (RecordingTunnelBus.Sent sent : tunnel.sent(HeimdallBridgeModule.FRAME_EVENT)) {
            for (Payload event : sent.payload().children("events")) {
                kinds.add(event.string("kind", ""));
            }
        }
        return kinds;
    }

    // ── Identity ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("id, capability and roles")
    void identity() {
        setUp(ServerRole.STANDALONE, null);

        assertEquals("bridge", module.id());
        assertEquals(
                new LinkedHashSet<String>(Arrays.asList(Capabilities.BRIDGE, Capabilities.CHAT_CHANNELS)),
                module.capabilities(),
                "chat-channel awareness is a build capability of the bridge, declared whether or not "
                        + "ChatControl is installed, like status@1 is of health");
        assertEquals("bridge@1", Capabilities.BRIDGE, "the capability string is a wire contract");
        assertEquals("chatchannels@1", Capabilities.CHAT_CHANNELS,
                "the capability string is a wire contract");
        assertEquals(Collections.<ServerRole>emptySet(), module.roles(),
                "relay eligibility is the relayChat SETTING, not a roles() exclusion — a proxy "
                        + "excluded here would be INELIGIBLE with no dashboard toggle able to "
                        + "bring it back");
    }

    // ── relayChat ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("relayChat")
    class RelayChatSetting {

        @Test
        @DisplayName("defaults ON for a standalone server and an enforcer")
        void defaultsOnForBackends() {
            setUp(ServerRole.STANDALONE, null);
            enable();
            assertTrue(module.isObservingChat());

            tearDown();
            setUp(ServerRole.ENFORCER, null);
            enable();
            assertTrue(module.isObservingChat());
        }

        @Test
        @DisplayName("defaults OFF on a gatekeeper, so a proxy does not double every line")
        void defaultsOffOnAProxy() {
            setUp(ServerRole.GATEKEEPER, null);
            enable();

            assertFalse(module.isObservingChat(),
                    "the sanctioned topology is backends relaying their own chat; a proxy relaying "
                            + "as well would send every line twice");
            assertFalse(HeimdallBridgeModule.defaultRelayChat(ServerRole.GATEKEEPER));
            assertTrue(HeimdallBridgeModule.defaultRelayChat(ServerRole.STANDALONE));
            assertTrue(HeimdallBridgeModule.defaultRelayChat(ServerRole.ENFORCER));
        }

        @Test
        @DisplayName("the dashboard can turn it ON for a proxy — proxy-origin relay")
        void aProxyCanBeToldToRelay() {
            setUp(ServerRole.GATEKEEPER, relayChat(true));
            enable();

            assertTrue(module.isObservingChat());

            say("Steve", "hello from the proxy");
            module.flush();
            assertEquals(1, tunnel.sent(HeimdallBridgeModule.FRAME_CHAT).size());
        }

        @Test
        @DisplayName("the dashboard can turn it OFF for a backend")
        void aBackendCanBeToldNotToRelay() {
            setUp(ServerRole.ENFORCER, relayChat(false));
            enable();

            assertFalse(module.isObservingChat());

            say("Steve", "not relayed");
            module.flush();
            assertTrue(tunnel.sent(HeimdallBridgeModule.FRAME_CHAT).isEmpty());
        }

        @Test
        @DisplayName("flipping the setting takes effect without disabling the module")
        void theToggleIsLive() {
            // The failure this pins: a settings change does NOT re-enable a module, so a module
            // that decided once in enable() would be stuck on whatever the setting said then, and
            // the dashboard toggle would look broken until somebody power-cycled the module.
            setUp(ServerRole.ENFORCER, relayChat(false));
            enable();
            assertFalse(module.isObservingChat());

            applySettings(relayChat(true));
            assertTrue(module.isObservingChat(), "turning relay on must not need a module restart");

            say("Steve", "now relayed");
            module.flush();
            assertEquals(1, tunnel.sent(HeimdallBridgeModule.FRAME_CHAT).size());

            applySettings(relayChat(false));
            assertFalse(module.isObservingChat());
            tunnel.clearSent();

            say("Steve", "silent again");
            module.flush();
            assertTrue(tunnel.sent(HeimdallBridgeModule.FRAME_CHAT).isEmpty());
        }

        @Test
        @DisplayName("re-applying the same value does not stack observers")
        void reconcilingIsIdempotent() {
            setUp(ServerRole.STANDALONE, relayChat(true));
            enable();

            applySettings(relayChat(true));
            applySettings(relayChat(true));

            say("Steve", "once");
            module.flush();

            List<Payload> lines = tunnel.sent(HeimdallBridgeModule.FRAME_CHAT)
                    .get(0).payload().children("lines");
            assertEquals(1, lines.size(), "a doubled observer would have queued this line twice");
        }
    }

    // ── relayEvents ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("relayEvents")
    class RelayEventsSetting {

        @Test
        @DisplayName("defaults ON for EVERY role — including the gatekeeper, unlike relayChat")
        void defaultsOnForEveryRole() {
            // The default is flat rather than role-derived, and that is the whole difference from
            // relayChat. It is upgrade continuity that carries it: every enabled instance relayed
            // its events before this setting existed, so a flat true changes nothing. (The bot's
            // duplicate drop is a best-effort backstop, not what makes this safe — it is why the
            // default need not encode a topology the way relayChat's must, no more than that.)
            for (ServerRole role : new ServerRole[] {
                    ServerRole.STANDALONE, ServerRole.ENFORCER, ServerRole.GATEKEEPER }) {
                tearDown();
                setUp(role, null);
                enable();

                allThreeKinds();
                module.flush();

                assertEquals(java.util.Arrays.asList("join", "death", "leave"), relayedEventKinds(),
                        "relayEvents must default ON for " + role + "; anything else would silently "
                                + "stop relaying events for every deployment that upgrades");
            }
        }

        @Test
        @DisplayName("turning it off stops joins, leaves AND deaths — all three, not just one")
        void offStopsAllThreeKinds() {
            // Asserting all three is the point: the gate is one choke point precisely so it cannot
            // be applied to two kinds and forgotten on the third.
            setUp(ServerRole.STANDALONE, relayEvents(false));
            enable();

            allThreeKinds();
            module.flush();

            assertTrue(tunnel.sent(HeimdallBridgeModule.FRAME_EVENT).isEmpty(),
                    "an instance told it is not the event origin must send nothing at all: "
                            + relayedEventKinds());
            assertEquals(0, module.queuedEventCount(),
                    "and nothing may sit in the queue waiting for the setting to come back either");
        }

        @Test
        @DisplayName("a death is gated like the rest — the premium-relevant kind is not special")
        void deathsAreGatedToo() {
            setUp(ServerRole.STANDALONE, relayEvents(false));
            enable();

            sessions.death(FakePlayer.named("Steve"), "Steve was slain by a zombie", 1L);
            module.flush();

            assertTrue(tunnel.sent(HeimdallBridgeModule.FRAME_EVENT).isEmpty(),
                    "a death carries the server's own message and is the kind an owner is most "
                            + "likely to want from exactly one origin; it gets no exemption");
            assertFalse(logger.records().toString().contains("slain by a zombie"),
                    "and declining to relay it must not be the moment it lands in a log: "
                            + logger.records());
        }

        @Test
        @DisplayName("flipping the setting takes effect without disabling the module")
        void theToggleIsLive() {
            // The D79 failure this pins, for the events half: a settings change does NOT re-enable a
            // module, so a gate that read the setting once in enable() would be stuck on whatever it
            // said then and the dashboard toggle would look broken until somebody power-cycled the
            // module. applySettings bumps the config version on every call, so these are real
            // pushes rather than no-ops RemoteConfig would drop as already-seen.
            setUp(ServerRole.STANDALONE, null);
            enable();

            allThreeKinds();
            module.flush();
            assertEquals(3, relayedEventKinds().size(), "the default is on");

            applySettings(relayEvents(false));
            tunnel.clearSent();

            allThreeKinds();
            module.flush();
            assertTrue(tunnel.sent(HeimdallBridgeModule.FRAME_EVENT).isEmpty(),
                    "turning relay off must not need a module restart");

            applySettings(relayEvents(true));

            allThreeKinds();
            module.flush();
            assertEquals(java.util.Arrays.asList("join", "death", "leave"), relayedEventKinds(),
                    "and turning it back on must resume without one either — a one-way toggle is "
                            + "the same bug in the other direction");
        }

        @Test
        @DisplayName("it is independent of relayChat in both directions")
        void theTwoSettingsDoNotImplyEachOther() {
            // Events off, chat on: the "backends relay chat, the proxy announces sessions" topology
            // the setting exists to make expressible.
            setUp(ServerRole.STANDALONE, relaySettings(true, false));
            enable();
            assertTrue(module.isObservingChat());

            say("Steve", "still relayed");
            allThreeKinds();
            module.flush();

            assertEquals(1, tunnel.sent(HeimdallBridgeModule.FRAME_CHAT).size(),
                    "gating events must not touch chat");
            assertTrue(tunnel.sent(HeimdallBridgeModule.FRAME_EVENT).isEmpty());

            // And the other way round, which is the case that already shipped: a gatekeeper
            // relaying no chat is still the only thing that sees a network-wide join.
            tearDown();
            // A different role, so the rig gets its own remote-config cache path rather than
            // reusing the first half's.
            setUp(ServerRole.ENFORCER, relaySettings(false, true));
            enable();
            assertFalse(module.isObservingChat());

            say("Steve", "not relayed");
            allThreeKinds();
            module.flush();

            assertTrue(tunnel.sent(HeimdallBridgeModule.FRAME_CHAT).isEmpty());
            assertEquals(3, relayedEventKinds().size(), "gating chat must not touch events");
        }
    }

    // ── bridge.chat ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("immediate send")
    class ImmediateSend {

        @Test
        @DisplayName("a line asks for a drain at once and ships without waiting for the tick")
        void aLineShipsImmediately() {
            setUp(ServerRole.STANDALONE, null);
            enable();

            say("Steve", "hello");

            assertEquals(1, pendingDrains.size(), "one drain requested, not a one-second wait");
            runDrains();
            assertEquals(1, tunnel.sent(HeimdallBridgeModule.FRAME_CHAT).size());
            assertEquals(0, module.queuedChatCount());
        }

        @Test
        @DisplayName("a burst queues ONE drain and ships as ONE frame")
        void aBurstIsOneFrame() {
            setUp(ServerRole.STANDALONE, null);
            enable();

            say("Steve", "one");
            say("Alex", "two");
            say("Steve", "three");

            assertEquals(1, pendingDrains.size());
            runDrains();
            List<RecordingTunnelBus.Sent> sent = tunnel.sent(HeimdallBridgeModule.FRAME_CHAT);
            assertEquals(1, sent.size());
            assertEquals(3, sent.get(0).payload().children("lines").size());
        }

        @Test
        @DisplayName("a line after a drain ran asks for the next one")
        void theNextLineAsksAgain() {
            setUp(ServerRole.STANDALONE, null);
            enable();

            say("Steve", "one");
            runDrains();
            say("Steve", "two");

            assertEquals(1, pendingDrains.size());
            runDrains();
            assertEquals(2, tunnel.sent(HeimdallBridgeModule.FRAME_CHAT).size());
        }

        @Test
        @DisplayName("a backlog bigger than one frame keeps draining instead of waiting for the tick")
        void aBacklogKeepsDraining() {
            setUp(ServerRole.STANDALONE, null);
            enable();

            for (int i = 0; i < HeimdallBridgeModule.MAX_BATCH + 50; i++) {
                say("Steve", "line " + i);
            }
            runDrains();

            assertEquals(2, tunnel.sent(HeimdallBridgeModule.FRAME_CHAT).size());
            assertEquals(0, module.queuedChatCount());
        }

        @Test
        @DisplayName("joins, leaves and deaths ship immediately too")
        void eventsShipImmediately() {
            setUp(ServerRole.STANDALONE, null);
            enable();

            allThreeKinds();

            assertEquals(1, pendingDrains.size());
            runDrains();
            assertEquals(1, tunnel.sent(HeimdallBridgeModule.FRAME_EVENT).size());
        }

        @Test
        @DisplayName("a drain does not run the inventory poll; only the one-second flush does")
        void drainsLeaveTheInventoryAlone() {
            setUp(ServerRole.STANDALONE, null);
            enable();

            say("Steve", "hello");
            runDrains();

            assertTrue(tunnel.sent(HeimdallBridgeModule.FRAME_CHANNELS).isEmpty(),
                    "the inventory goes out on the flush tick, never on a chat drain");
        }
    }

    @Nested
    @DisplayName("bridge.chat")
    class ChatFrames {

        @Test
        @DisplayName("lines are batched into one frame with uuid/name/msg/ts intact")
        void batchesIntoOneFrame() {
            setUp(ServerRole.STANDALONE, null);
            enable();

            say("Steve", "hello");
            say("Alex", "hi back");
            module.flush();

            List<RecordingTunnelBus.Sent> sent = tunnel.sent(HeimdallBridgeModule.FRAME_CHAT);
            assertEquals(1, sent.size());
            List<Payload> lines = sent.get(0).payload().children("lines");
            assertEquals(2, lines.size());
            assertEquals("Steve", lines.get(0).string("name", ""));
            assertEquals("hello", lines.get(0).string("msg", ""));
            assertEquals(
                    UUID.nameUUIDFromBytes("Steve".getBytes()).toString(),
                    lines.get(0).string("uuid", ""));
            assertTrue(lines.get(0).longValue("ts", -1L) > 0L, "every line carries its own time");
            assertEquals("hi back", lines.get(1).string("msg", ""));
        }

        @Test
        @DisplayName("player text crosses the wire VERBATIM — untrimmed, unformatted, unedited")
        void textIsVerbatim() {
            setUp(ServerRole.STANDALONE, null);
            enable();

            // Leading and trailing spaces, a section sign, an ampersand code, and a run of inner
            // whitespace. Every one of those is something a well-meaning relay might "clean up",
            // and the bot owns rendering — see departure D79.
            String typed = "  §chello   &aworld  ";
            say("Steve", typed);
            module.flush();

            assertEquals(typed,
                    tunnel.sent(HeimdallBridgeModule.FRAME_CHAT).get(0)
                            .payload().children("lines").get(0).string("msg", ""),
                    "the plugin never formats or trims; a relay that silently edited what a player "
                            + "typed is worse than one that does not relay at all");
        }

        @Test
        @DisplayName("a message the pipeline BLOCKED is never relayed")
        void blockedMessagesAreNotRelayed() {
            setUp(ServerRole.STANDALONE, null);
            enable();
            chatPipeline.register(new Interceptor<ChatMessage>() {
                @Override
                public Verdict intercept(ChatMessage context) {
                    return Verdict.deny(Component.text("no"));
                }
            }, 0, "test");

            say("Steve", "something censored");
            module.flush();

            assertTrue(tunnel.sent(HeimdallBridgeModule.FRAME_CHAT).isEmpty(),
                    "relaying a censored message to Discord would put it in front of a wider "
                            + "audience than it started with");
        }
    }

    // ── bridge.event ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("bridge.event")
    class EventFrames {

        @Test
        @DisplayName("join, leave and death batch into one frame, in order")
        void allThreeKindsBatch() {
            setUp(ServerRole.STANDALONE, null);
            enable();

            sessions.join(FakePlayer.named("Steve"), 1000L);
            sessions.death(FakePlayer.named("Steve"), "Steve fell from a high place", 1500L);
            sessions.quit(FakePlayer.named("Steve"), 2000L);
            module.flush();

            List<RecordingTunnelBus.Sent> sent = tunnel.sent(HeimdallBridgeModule.FRAME_EVENT);
            assertEquals(1, sent.size());
            List<Payload> events = sent.get(0).payload().children("events");
            assertEquals(3, events.size());
            assertEquals("join", events.get(0).string("kind", ""));
            assertEquals(1000L, events.get(0).longValue("ts", -1L));
            assertEquals("death", events.get(1).string("kind", ""));
            assertEquals("Steve fell from a high place", events.get(1).string("detail", ""));
            assertEquals("leave", events.get(2).string("kind", ""));
            assertEquals("Steve", events.get(2).string("name", ""));
        }

        @Test
        @DisplayName("detail is ABSENT for join and leave, not empty or null")
        void detailIsOmittedWhereThereIsNone() {
            setUp(ServerRole.STANDALONE, null);
            enable();

            sessions.join(FakePlayer.named("Steve"), 1L);
            sessions.quit(FakePlayer.named("Steve"), 2L);
            module.flush();

            List<Payload> events = tunnel.sent(HeimdallBridgeModule.FRAME_EVENT)
                    .get(0).payload().children("events");
            assertFalse(events.get(0).has("detail"), "an absent key and an empty string are "
                    + "different answers on this wire");
            assertFalse(events.get(1).has("detail"));
        }

        @Test
        @DisplayName("a suppressed death message omits detail rather than sending an empty one")
        void aSuppressedDeathMessageOmitsDetail() {
            setUp(ServerRole.STANDALONE, null);
            enable();

            sessions.death(FakePlayer.named("Steve"), null, 1L);
            module.flush();

            List<Payload> events = tunnel.sent(HeimdallBridgeModule.FRAME_EVENT)
                    .get(0).payload().children("events");
            assertEquals("death", events.get(0).string("kind", ""));
            assertFalse(events.get(0).has("detail"),
                    "the bot distinguishes 'there was no death message' from 'the death message "
                            + "was empty'; a suppressed one is the first");
        }

        @Test
        @DisplayName("events relay even when relayChat is off — the setting is about chat")
        void eventsAreNotGatedByRelayChat() {
            setUp(ServerRole.GATEKEEPER, null);
            enable();
            assertFalse(module.isObservingChat());

            sessions.join(FakePlayer.named("Steve"), 1L);
            module.flush();

            assertEquals(1, tunnel.sent(HeimdallBridgeModule.FRAME_EVENT).size(),
                    "a proxy that relays no chat is still the only thing that sees a network-wide "
                            + "join; the events half has its own setting, relayEvents, which "
                            + "defaults on for every role");
        }
    }

    // ── Batching, bounds and the offline case ────────────────────────────────

    @Nested
    @DisplayName("bounds")
    class Bounds {

        @Test
        @DisplayName("a batch over 200 is capped per flush, and the remainder waits")
        void capsPerFlush() {
            setUp(ServerRole.STANDALONE, null);
            enable();

            for (int i = 0; i < 250; i++) {
                say("Steve", "line " + i);
            }

            module.flush();
            assertEquals(HeimdallBridgeModule.MAX_BATCH,
                    tunnel.sent(HeimdallBridgeModule.FRAME_CHAT).get(0)
                            .payload().children("lines").size());

            module.flush();
            assertEquals(50,
                    tunnel.sent(HeimdallBridgeModule.FRAME_CHAT).get(1)
                            .payload().children("lines").size(),
                    "the leftover must not be dropped");
        }

        @Test
        @DisplayName("under a flood the OLDEST lines are dropped, and the queue stays bounded")
        void dropsOldestUnderFlood() {
            setUp(ServerRole.STANDALONE, null);
            enable();

            // 600 into a 500-slot queue: the first 100 must be gone, and the survivors must start
            // at line 100. Asserting only the count would pass on drop-NEWEST, which is the
            // opposite policy — a relay that went permanently silent the moment it fell behind.
            for (int i = 0; i < 600; i++) {
                say("Steve", "line " + i);
            }

            assertEquals(HeimdallBridgeModule.MAX_QUEUE_SIZE, module.queuedChatCount());
            module.flush();

            List<Payload> lines = tunnel.sent(HeimdallBridgeModule.FRAME_CHAT)
                    .get(0).payload().children("lines");
            assertEquals("line 100", lines.get(0).string("msg", ""),
                    "what a flood makes valuable is the present; dropping the newest would go "
                            + "silent under exactly the load somebody is watching");
            assertEquals("line 299", lines.get(lines.size() - 1).string("msg", ""));
        }

        @Test
        @DisplayName("a disconnected flush still DRAINS a batch — it does not leave it queued")
        void aDisconnectedFlushStillDrains() {
            // The direct assertion, and the one a "bounded queue" check cannot make: with 300
            // queued and MAX_BATCH 200, a flush against a dead tunnel must leave exactly 100. A
            // flush that returned early on !isConnected() would leave all 300 — and the queue would
            // still be "bounded", because drop-oldest bounds it regardless. That is why the bound
            // alone is not the test.
            setUp(ServerRole.STANDALONE, null);
            tunnel.connected(false);
            enable();

            for (int i = 0; i < 300; i++) {
                say("Steve", "line " + i);
            }
            assertEquals(300, module.queuedChatCount());

            module.flush();

            assertEquals(300 - HeimdallBridgeModule.MAX_BATCH, module.queuedChatCount(),
                    "draining is unconditional; only the SEND is conditional");
            assertTrue(tunnel.sent().isEmpty(), "nothing may be sent while disconnected");
        }

        @Test
        @DisplayName("while disconnected the queue is drained and discarded, never grown")
        void drainsAndDiscardsWhileDisconnected() {
            setUp(ServerRole.STANDALONE, null);
            tunnel.connected(false);
            enable();

            for (int round = 0; round < 10; round++) {
                for (int i = 0; i < 300; i++) {
                    say("Steve", "line " + i);
                }
                sessions.join(FakePlayer.named("Steve"), round + 1L);
                module.flush();
            }

            assertTrue(tunnel.sent().isEmpty(), "nothing may be sent while disconnected");
            assertTrue(module.queuedChatCount() <= HeimdallBridgeModule.MAX_QUEUE_SIZE,
                    "the queue must stay bounded across many disconnected flushes");
            assertTrue(module.queuedEventCount() <= HeimdallBridgeModule.MAX_QUEUE_SIZE);
        }

        @Test
        @DisplayName("reconnecting relays only what was said after the reconnect")
        void reconnectSendsOnlyNewLines() {
            setUp(ServerRole.STANDALONE, null);
            tunnel.connected(false);
            enable();

            say("Steve", "lost forever");
            module.flush();
            assertTrue(tunnel.sent().isEmpty());

            tunnel.connected(true);
            say("Steve", "survives");
            module.flush();

            List<Payload> lines = tunnel.sent(HeimdallBridgeModule.FRAME_CHAT)
                    .get(0).payload().children("lines");
            assertEquals(1, lines.size(),
                    "five minutes of backlog arriving in one burst is worse than losing it: the "
                            + "chat already happened in front of the people it was addressed to");
            assertEquals("survives", lines.get(0).string("msg", ""));
        }

        @Test
        @DisplayName("flush with nothing queued sends nothing")
        void emptyFlushIsSilent() {
            setUp(ServerRole.STANDALONE, null);
            enable();
            // The first flush after enable reports the channel inventory once, by design; that frame
            // is covered by its own suite. What this asserts is that a flush with nothing new has
            // nothing to say.
            module.flush();
            tunnel.clearSent();

            module.flush();

            assertTrue(tunnel.sent().isEmpty());
        }
    }

    // ── bridge.discord ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("bridge.discord")
    class DiscordDelivery {

        private Payload messages(String... texts) {
            java.util.List<Payload> out = new java.util.ArrayList<Payload>();
            for (String text : texts) {
                out.add(Payload.builder().put("text", text).put("ts", 1L).build());
            }
            return Payload.builder().putChildren("messages", out).build();
        }

        @Test
        @DisplayName("a rendered legacy-§ line reaches every online player, with its colours")
        void deliveredToEveryone() {
            setUp(ServerRole.STANDALONE, null);
            FakePlayer steve = platform.join(FakePlayer.named("Steve"));
            FakePlayer alex = platform.join(FakePlayer.named("Alex"));
            enable();

            tunnel.push(HeimdallBridgeModule.FRAME_DISCORD, messages("§b[Discord] §fsomeone: hi"));

            assertEquals(Collections.singletonList("[Discord] someone: hi"), steve.messageText(),
                    "the § codes are PARSED, not shown — the bot sends a finished legacy string "
                            + "and Msg.legacy renders it");
            assertEquals(Collections.singletonList("[Discord] someone: hi"), alex.messageText());
        }

        @Test
        @DisplayName("several messages in one frame are all delivered, in order")
        void aBatchIsDeliveredInOrder() {
            setUp(ServerRole.STANDALONE, null);
            FakePlayer steve = platform.join(FakePlayer.named("Steve"));
            enable();

            tunnel.push(HeimdallBridgeModule.FRAME_DISCORD, messages("first", "second", "third"));

            assertEquals(java.util.Arrays.asList("first", "second", "third"), steve.messageText());
        }

        @Test
        @DisplayName("an empty text is skipped rather than shown as a blank line")
        void emptyTextIsSkipped() {
            setUp(ServerRole.STANDALONE, null);
            FakePlayer steve = platform.join(FakePlayer.named("Steve"));
            enable();

            tunnel.push(HeimdallBridgeModule.FRAME_DISCORD, messages("", "real"));

            assertEquals(Collections.singletonList("real"), steve.messageText());
        }

        @Test
        @DisplayName("an oversized frame is capped, and the drop is reported by count")
        void anOversizedFrameIsCapped() {
            // Defence in depth rather than a threat model — the peer is the guild's own bot, and it
            // coalesces before it sends. The bound is here because this is the one loop whose work
            // is multiplicative (messages × players, each a main-thread task on Bukkit), and
            // because every OTHER path in this module states and tests a bound: an unbounded one in
            // the middle of them is a sentence a future reader believes rather than code.
            setUp(ServerRole.STANDALONE, null);
            FakePlayer steve = platform.join(FakePlayer.named("Steve"));
            enable();

            String[] texts = new String[HeimdallBridgeModule.MAX_INBOUND_MESSAGES + 25];
            for (int i = 0; i < texts.length; i++) {
                texts[i] = "line " + i;
            }
            tunnel.push(HeimdallBridgeModule.FRAME_DISCORD, messages(texts));

            assertEquals(HeimdallBridgeModule.MAX_INBOUND_MESSAGES, steve.messageText().size(),
                    "the whole frame would be messages × players main-thread tasks from one socket "
                            + "read");
            assertEquals("line 0", steve.messageText().get(0));
            assertEquals("line " + (HeimdallBridgeModule.MAX_INBOUND_MESSAGES - 1),
                    steve.messageText().get(HeimdallBridgeModule.MAX_INBOUND_MESSAGES - 1),
                    "the first N are kept, not an arbitrary window: the bot sent them in order");
            assertTrue(logger.records().toString().contains("dropping 25"),
                    "a silent cap is indistinguishable from a quiet channel: " + logger.records());
            assertFalse(logger.records().toString().contains("line 60"),
                    "and the report is a COUNT — it must not name the messages it dropped");
        }

        @Test
        @DisplayName("a frame exactly at the cap is relayed whole, with no warning")
        void aFrameAtTheCapIsNotTruncated() {
            setUp(ServerRole.STANDALONE, null);
            FakePlayer steve = platform.join(FakePlayer.named("Steve"));
            enable();

            String[] texts = new String[HeimdallBridgeModule.MAX_INBOUND_MESSAGES];
            for (int i = 0; i < texts.length; i++) {
                texts[i] = "line " + i;
            }
            tunnel.push(HeimdallBridgeModule.FRAME_DISCORD, messages(texts));

            assertEquals(HeimdallBridgeModule.MAX_INBOUND_MESSAGES, steve.messageText().size());
            assertFalse(logger.records().toString().contains("dropping"),
                    "an off-by-one here would warn on every ordinary busy frame");
        }

        @Test
        @DisplayName("with nobody online it is a no-op, not a failure")
        void nobodyOnlineIsFine() {
            setUp(ServerRole.STANDALONE, null);
            enable();

            tunnel.push(HeimdallBridgeModule.FRAME_DISCORD, messages("into the void"));

            assertFalse(logger.records().toString().contains("into the void"),
                    "and the content is not logged on the way past either");
        }

        @Test
        @DisplayName("the subscription is gone once the module is disabled")
        void disableUnsubscribes() {
            setUp(ServerRole.STANDALONE, null);
            FakePlayer steve = platform.join(FakePlayer.named("Steve"));
            enable();
            assertEquals(1, tunnel.subscriberCount(HeimdallBridgeModule.FRAME_DISCORD));

            disable();

            assertEquals(0, tunnel.subscriberCount(HeimdallBridgeModule.FRAME_DISCORD),
                    "a 'disabled' module still showing players Discord messages is exactly the "
                            + "failure the tracked-registration design exists to prevent");
            tunnel.push(HeimdallBridgeModule.FRAME_DISCORD, messages("should not arrive"));
            assertTrue(steve.messageText().isEmpty());
        }

        @Test
        @DisplayName("the handler is not subscribed on the socket's reading thread")
        void subscribesOnTheDefaultExecutor() {
            setUp(ServerRole.STANDALONE, null);
            enable();

            // null means "the default", which is heimdall-io. Naming a specific executor here
            // would be the bug: the main server thread would serialise every delivery behind the
            // tick loop, and the socket thread would stop the tunnel reading.
            assertEquals(null, tunnel.subscribedExecutor(HeimdallBridgeModule.FRAME_DISCORD));
        }
    }

    // ── Chat-plugin channels ─────────────────────────────────────────────────

    @Nested
    @DisplayName("channel lines: the chatChannels allowlist and the wire key")
    class ChannelOutbound {

        @Test
        @DisplayName("an allowed channel line carries its channel on the wire")
        void channelKeyOnTheWire() {
            setUp(ServerRole.STANDALONE, chatChannels("global"));
            enable();

            sayIn("global", "Steve", "hello all");
            module.flush();

            List<Payload> lines = relayedLines();
            assertEquals(1, lines.size());
            assertEquals("global", lines.get(0).string("channel", ""));
            assertEquals("hello all", lines.get(0).string("msg", ""));
        }

        @Test
        @DisplayName("an ordinary line OMITS the channel key: not null, not empty")
        void channelKeyOmittedWhenAbsent() {
            setUp(ServerRole.STANDALONE, chatChannels("global"));
            enable();

            say("Steve", "plain chat");
            module.flush();

            List<Payload> lines = relayedLines();
            assertEquals(1, lines.size());
            assertFalse(lines.get(0).has("channel"),
                    "the bot reads presence as 'this came from a channel'; an empty string would be "
                            + "a channel with no name: " + lines.get(0));
        }

        @Test
        @DisplayName("with NO chatChannels setting, every channel line is dropped and plain chat is not")
        void absentSettingDropsEveryChannelLine() {
            setUp(ServerRole.STANDALONE, null);
            enable();

            sayIn("staff", "Mod", "ban him");
            sayIn("global", "Steve", "hi");
            say("Alex", "plain chat");
            module.flush();

            List<Payload> lines = relayedLines();
            assertEquals(1, lines.size(),
                    "fail closed: a server the bot never told about channels relays none of them");
            assertEquals("plain chat", lines.get(0).string("msg", ""));
        }

        @Test
        @DisplayName("an empty chatChannels list is the same as an absent one")
        void emptySettingDropsEveryChannelLine() {
            setUp(ServerRole.STANDALONE, chatChannels());
            enable();

            sayIn("global", "Steve", "hi");
            module.flush();

            assertTrue(relayedLines().isEmpty());
        }

        @Test
        @DisplayName("a channel not in the list stays in the game; a listed one is relayed")
        void onlyListedChannelsRelay() {
            setUp(ServerRole.STANDALONE, chatChannels("global", "trade"));
            enable();

            sayIn("staff", "Mod", "the staff-only line");
            sayIn("global", "Steve", "hi");
            sayIn("trade", "Alex", "wts diamonds");
            module.flush();

            List<String> relayed = new ArrayList<String>();
            for (Payload line : relayedLines()) {
                relayed.add(line.string("channel", "") + ":" + line.string("msg", ""));
            }
            assertEquals(Arrays.asList("global:hi", "trade:wts diamonds"), relayed,
                    "a staff channel nobody mapped must never reach Discord");
        }

        @Test
        @DisplayName("the allowlist compares case-insensitively, and the line keeps its own spelling")
        void allowlistIsCaseInsensitive() {
            setUp(ServerRole.STANDALONE, chatChannels("GLOBAL"));
            enable();

            sayIn("Global", "Steve", "hi");
            module.flush();

            List<Payload> lines = relayedLines();
            assertEquals(1, lines.size());
            assertEquals("Global", lines.get(0).string("channel", ""),
                    "the wire carries the chat plugin's own name, not the setting's spelling");
        }

        @Test
        @DisplayName("the allowlist is read live: a config push takes effect with no re-enable")
        void allowlistIsLive() {
            setUp(ServerRole.STANDALONE, chatChannels());
            enable();

            sayIn("global", "Steve", "before");
            applySettings(chatChannels("global"));
            sayIn("global", "Steve", "after");
            applySettings(chatChannels());
            sayIn("global", "Steve", "after removal");
            module.flush();

            List<String> relayed = new ArrayList<String>();
            for (Payload line : relayedLines()) {
                relayed.add(line.string("msg", ""));
            }
            assertEquals(Collections.singletonList("after"), relayed);
        }

        @Test
        @DisplayName("a dropped channel line is not logged, by content or at all")
        void droppedLinesAreNotLogged() {
            setUp(ServerRole.STANDALONE, null);
            enable();

            sayIn("staff", "Mod", "correct-horse-battery-staple");
            module.flush();

            assertFalse(logger.records().toString().contains("correct-horse"),
                    "chat content in a log file is the storage this feature promises not to do: "
                            + logger.records());
        }
    }

    @Nested
    @DisplayName("bridge.discord routing by channel")
    class ChannelInbound {

        private Payload message(String text, String channel) {
            Payload.Builder one = Payload.builder().put("text", text).put("ts", 1L);
            if (channel != null) {
                one.put("channel", channel);
            }
            return Payload.builder()
                    .putChildren("messages", Collections.singletonList(one.build()))
                    .build();
        }

        @Test
        @DisplayName("ACTIVE + a known channel: only that channel's members see it")
        void activeKnownChannelReachesMembersOnly() {
            setUp(ServerRole.STANDALONE, null);
            FakePlayer mod = platform.join(FakePlayer.named("Mod"));
            FakePlayer steve = platform.join(FakePlayer.named("Steve"));
            platform.withChatChannels(new FakeChannels().channel("staff", mod));
            enable();

            tunnel.push(HeimdallBridgeModule.FRAME_DISCORD, message("[Discord] staff note", "staff"));

            assertEquals(Collections.singletonList("[Discord] staff note"), mod.messageText());
            assertTrue(steve.messageText().isEmpty(),
                    "a staff channel's Discord side must not reach the whole server");
        }

        @Test
        @DisplayName("ACTIVE + a channel lookup that ignores case still routes")
        void activeChannelMatchIgnoresCase() {
            setUp(ServerRole.STANDALONE, null);
            FakePlayer mod = platform.join(FakePlayer.named("Mod"));
            platform.withChatChannels(new FakeChannels().channel("Staff", mod));
            enable();

            tunnel.push(HeimdallBridgeModule.FRAME_DISCORD, message("note", "staff"));

            assertEquals(Collections.singletonList("note"), mod.messageText());
        }

        @Test
        @DisplayName("ACTIVE + an unknown channel: dropped, never widened to everyone")
        void activeUnknownChannelIsDropped() {
            setUp(ServerRole.STANDALONE, null);
            FakePlayer steve = platform.join(FakePlayer.named("Steve"));
            platform.withChatChannels(new FakeChannels().channel("global", steve));
            enable();

            tunnel.push(HeimdallBridgeModule.FRAME_DISCORD, message("into a deleted channel", "staff"));

            assertTrue(steve.messageText().isEmpty());
            assertTrue(logger.records().toString().contains("dropped 1"),
                    "a drop is counted, so silence is never mistaken for quiet: "
                            + logger.records());
            assertFalse(logger.records().toString().contains("deleted channel"),
                    "and the count never names the message");
        }

        @Test
        @DisplayName("a channel message on a server with NO channels in play is dropped")
        void channelMessageWithoutChannelsIsDropped() {
            setUp(ServerRole.STANDALONE, null);
            FakePlayer steve = platform.join(FakePlayer.named("Steve"));
            enable();

            tunnel.push(HeimdallBridgeModule.FRAME_DISCORD, message("for staff", "staff"));

            assertTrue(steve.messageText().isEmpty(),
                    "a message addressed to a channel that cannot be resolved is not everybody's");
        }

        @Test
        @DisplayName("a channel message while the hook is BROKEN is dropped")
        void channelMessageWhileBrokenIsDropped() {
            setUp(ServerRole.STANDALONE, null);
            FakePlayer mod = platform.join(FakePlayer.named("Mod"));
            platform.withChatChannels(
                    new FakeChannels().channel("staff", mod).state(ChatChannels.State.BROKEN));
            enable();

            tunnel.push(HeimdallBridgeModule.FRAME_DISCORD, message("for staff", "staff"));

            assertTrue(mod.messageText().isEmpty());
        }

        private void assertLyingIntegrationIgnored(ChatChannels.State state) {
            setUp(ServerRole.STANDALONE, null);
            FakePlayer mod = platform.join(FakePlayer.named("Mod"));
            platform.withChatChannels(
                    new FakeChannels().channel("staff", mod).state(state).lyingAboutMembers());
            enable();

            tunnel.push(HeimdallBridgeModule.FRAME_DISCORD, message("for staff", "staff"));

            assertTrue(mod.messageText().isEmpty(),
                    "an integration that hands out members while " + state + " must not be "
                            + "believed: routing by channel needs the hook to be live");
        }

        @Test
        @DisplayName("the bridge's own ACTIVE gate: members offered while BROKEN are ignored")
        void activeGateIgnoresBrokenMembers() {
            assertLyingIntegrationIgnored(ChatChannels.State.BROKEN);
        }

        @Test
        @DisplayName("the bridge's own ACTIVE gate: members offered while NONE are ignored")
        void activeGateIgnoresNoneMembers() {
            assertLyingIntegrationIgnored(ChatChannels.State.NONE);
        }

        @Test
        @DisplayName("NO channel on a server with no channels: everybody online, as before")
        void noChannelNoneBroadcasts() {
            setUp(ServerRole.STANDALONE, null);
            FakePlayer steve = platform.join(FakePlayer.named("Steve"));
            FakePlayer alex = platform.join(FakePlayer.named("Alex"));
            enable();

            tunnel.push(HeimdallBridgeModule.FRAME_DISCORD, message("hello", null));

            assertEquals(Collections.singletonList("hello"), steve.messageText());
            assertEquals(Collections.singletonList("hello"), alex.messageText());
            assertTrue(logger.records().toString().contains(
                            "relayed 1 discord message(s) to 2 online player(s)"),
                    "the connected smoke asserts on this exact wording: " + logger.records());
        }

        @Test
        @DisplayName("NO channel on a server whose channels are ACTIVE: dropped")
        void noChannelActiveIsDropped() {
            setUp(ServerRole.STANDALONE, null);
            FakePlayer steve = platform.join(FakePlayer.named("Steve"));
            platform.withChatChannels(new FakeChannels().channel("global", steve));
            enable();

            tunnel.push(HeimdallBridgeModule.FRAME_DISCORD, message("to nobody in particular", null));

            assertTrue(steve.messageText().isEmpty(),
                    "on a channelled server 'everybody' is not an audience anybody picked");
        }

        @Test
        @DisplayName("NO channel while the hook is BROKEN: dropped, fail closed")
        void noChannelBrokenIsDropped() {
            setUp(ServerRole.STANDALONE, null);
            FakePlayer steve = platform.join(FakePlayer.named("Steve"));
            platform.withChatChannels(new FakeChannels().state(ChatChannels.State.BROKEN));
            enable();

            tunnel.push(HeimdallBridgeModule.FRAME_DISCORD, message("hello", null));

            assertTrue(steve.messageText().isEmpty());
        }

        @Test
        @DisplayName("the inbound cap still holds for channel-routed frames")
        void capHoldsForChannelFrames() {
            setUp(ServerRole.STANDALONE, null);
            FakePlayer mod = platform.join(FakePlayer.named("Mod"));
            platform.withChatChannels(new FakeChannels().channel("staff", mod));
            enable();

            List<Payload> many = new ArrayList<Payload>();
            for (int i = 0; i < HeimdallBridgeModule.MAX_INBOUND_MESSAGES + 5; i++) {
                many.add(Payload.builder().put("text", "n" + i).put("channel", "staff").build());
            }
            tunnel.push(HeimdallBridgeModule.FRAME_DISCORD,
                    Payload.builder().putChildren("messages", many).build());

            assertEquals(HeimdallBridgeModule.MAX_INBOUND_MESSAGES, mod.messageText().size());
        }
    }

    @Nested
    @DisplayName("bridge.channels inventory")
    class ChannelInventory {

        private List<Payload> inventories() {
            List<Payload> out = new ArrayList<Payload>();
            for (RecordingTunnelBus.Sent sent : tunnel.sent(HeimdallBridgeModule.FRAME_CHANNELS)) {
                out.add(sent.payload());
            }
            return out;
        }

        private void pollOnce() {
            for (int i = 0; i < HeimdallBridgeModule.INVENTORY_POLL_FLUSHES; i++) {
                module.flush();
            }
        }

        /** Enables, lets the first flush make the requested report, then forgets what was sent. */
        private void enableAndSettle() {
            enable();
            module.flush();
            tunnel.clearSent();
        }

        private Object inventoryLock() throws Exception {
            java.lang.reflect.Field field =
                    HeimdallBridgeModule.class.getDeclaredField("inventoryLock");
            field.setAccessible(true);
            return field.get(module);
        }

        @Test
        @DisplayName("the first flush after enable reports 'none' when there is no chat plugin")
        void enableReportsNone() {
            setUp(ServerRole.STANDALONE, null);
            enable();
            assertTrue(inventories().isEmpty(), "requested at enable, made on the flush");

            module.flush();

            List<Payload> sent = inventories();
            assertEquals(1, sent.size());
            assertEquals("none", sent.get(0).string("state", ""));
            assertTrue(sent.get(0).hasArray("channels"), "an empty array, not an absent key");
            assertTrue(sent.get(0).strings("channels").isEmpty());
        }

        @Test
        @DisplayName("the first flush after enable reports 'active' with the channel names")
        void enableReportsActive() {
            setUp(ServerRole.STANDALONE, null);
            platform.withChatChannels(new FakeChannels().channel("global").channel("staff"));
            enable();
            module.flush();

            List<Payload> sent = inventories();
            assertEquals(1, sent.size());
            assertEquals("active", sent.get(0).string("state", ""));
            assertEquals(Arrays.asList("global", "staff"), sent.get(0).strings("channels"));
        }

        @Test
        @DisplayName("'broken' is reported with an empty channel list")
        void enableReportsBroken() {
            setUp(ServerRole.STANDALONE, null);
            platform.withChatChannels(
                    new FakeChannels().channel("global").state(ChatChannels.State.BROKEN));
            enable();
            module.flush();

            List<Payload> sent = inventories();
            assertEquals("broken", sent.get(0).string("state", ""));
            assertTrue(sent.get(0).strings("channels").isEmpty());
        }

        @Test
        @DisplayName("a reconnect resends it on the next flush, even though nothing changed")
        void reconnectResends() {
            setUp(ServerRole.STANDALONE, null);
            platform.withChatChannels(new FakeChannels().channel("global"));
            enableAndSettle();

            tunnel.disconnected();
            tunnel.reconnected();
            assertTrue(inventories().isEmpty(), "never from the mode listener itself");

            module.flush();

            List<Payload> sent = inventories();
            assertEquals(1, sent.size(),
                    "what was sent before went to a socket that is gone; the bot may have restarted");
            assertEquals("active", sent.get(0).string("state", ""));
        }

        @Test
        @DisplayName("the mode listener and enable never call the integration (deadlock rule)")
        void listenersNeverCallTheIntegration() {
            // The negotiator calls mode listeners while holding its own monitor, and enable can run
            // under the module manager's lock. The real integration can reach Bukkit's getPlugin,
            // synchronized on the plugin manager, which the server holds while disabling plugins and
            // tearing the tunnel down. So neither may call it: they only request a report.
            setUp(ServerRole.STANDALONE, null);
            FakeChannels channels = new FakeChannels().channel("global");
            platform.withChatChannels(channels);

            enable();
            tunnel.disconnected();
            tunnel.reconnected();
            tunnel.mode(com.heimdall.core.tunnel.ProtocolMode.V2_COMPAT);

            assertEquals(0, channels.calls,
                    "the integration was called synchronously from enable or a mode change");

            module.flush();
            assertTrue(channels.calls > 0);
            assertEquals(1, inventories().size(), "one report for everything requested");
        }

        @Test
        @DisplayName("enabled while disconnected: nothing sent, then sent on the flush after connect")
        void enabledWhileDisconnected() {
            setUp(ServerRole.STANDALONE, null);
            tunnel.connected(false);
            enable();
            module.flush();
            assertTrue(inventories().isEmpty());

            tunnel.connected(true);
            module.flush();

            assertEquals(1, inventories().size());
        }

        @Test
        @DisplayName("a poll sends only when the state or the channel list changed")
        void pollSendsOnlyOnChange() {
            setUp(ServerRole.STANDALONE, null);
            FakeChannels channels = new FakeChannels().channel("global");
            platform.withChatChannels(channels);
            enableAndSettle();

            pollOnce();
            assertTrue(inventories().isEmpty(), "unchanged, so nothing to say");

            channels.channel("trade");
            pollOnce();
            assertEquals(1, inventories().size(), "a ChatControl reload added a channel");
            assertEquals(Arrays.asList("global", "trade"), inventories().get(0).strings("channels"));

            tunnel.clearSent();
            channels.state(ChatChannels.State.NONE);
            pollOnce();
            assertEquals(1, inventories().size(), "channels were switched off");
            assertEquals("none", inventories().get(0).string("state", ""));

            tunnel.clearSent();
            pollOnce();
            assertTrue(inventories().isEmpty());
        }

        @Test
        @DisplayName("the poll runs every few flushes, not on every one")
        void pollCadence() {
            setUp(ServerRole.STANDALONE, null);
            FakeChannels channels = new FakeChannels().channel("global");
            platform.withChatChannels(channels);
            enableAndSettle();

            channels.channel("trade");
            for (int i = 0; i < HeimdallBridgeModule.INVENTORY_POLL_FLUSHES - 1; i++) {
                module.flush();
            }
            assertTrue(inventories().isEmpty());
            module.flush();
            assertEquals(1, inventories().size());
        }

        @Test
        @DisplayName("the integration is never called while inventoryLock is held")
        void integrationIsNotCalledUnderTheLock() throws Exception {
            setUp(ServerRole.STANDALONE, null);
            FakeChannels channels = new FakeChannels().channel("global");
            platform.withChatChannels(channels);
            channels.watching(inventoryLock());

            enable();
            module.flush();
            channels.channel("trade");
            pollOnce();
            tunnel.disconnected();
            tunnel.reconnected();
            module.flush();

            assertTrue(channels.calls > 0, "the fake must actually have been asked");
            assertEquals(0, channels.callsUnderWatchedLock,
                    "state() or channelNames() ran while inventoryLock was held");
            assertEquals(3, inventories().size(), "enable, the change, and the reconnect");
        }

        @Test
        @DisplayName("forgetting what the bot was told never waits on inventoryLock")
        void forgetIsLockFree() throws Exception {
            // forgetInventory runs from the mode listener (under the negotiator's monitor) and from
            // disable (possibly under the server's plugin-manager monitor). If it ever took
            // inventoryLock, a report holding that lock around a socket send could stall both.
            setUp(ServerRole.STANDALONE, null);
            enableAndSettle();
            final Object lock = inventoryLock();
            final java.util.concurrent.CountDownLatch held = new java.util.concurrent.CountDownLatch(1);
            final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
            Thread holder = new Thread(() -> {
                synchronized (lock) {
                    held.countDown();
                    try {
                        done.await(10, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }, "lock-holder");
            holder.start();
            assertTrue(held.await(10, java.util.concurrent.TimeUnit.SECONDS));
            try {
                Thread forgetter = new Thread(() -> {
                    tunnel.disconnected();
                    disable();
                }, "forgetter");
                forgetter.start();
                forgetter.join(2000);
                assertFalse(forgetter.isAlive(),
                        "a disconnect or a disable blocked on inventoryLock");
            } finally {
                done.countDown();
                holder.join(10000);
            }
        }

        @Test
        @DisplayName("an older read that finishes after a newer report is not sent")
        void olderReadIsNotSentAfterNewer() throws Exception {
            setUp(ServerRole.STANDALONE, null);
            FakeChannels channels = new FakeChannels().channel("global");
            platform.withChatChannels(channels);
            enableAndSettle();

            // A slow read starts while the state is ACTIVE...
            channels.blockingNextState();
            Thread slow = new Thread(() -> module.reportInventory(true), "slow-inventory-read");
            slow.start();
            assertTrue(channels.entered.await(10, java.util.concurrent.TimeUnit.SECONDS));

            // ...the state changes and a newer read reports it...
            channels.state(ChatChannels.State.NONE);
            module.reportInventory(true);
            assertEquals("none", inventories().get(inventories().size() - 1).string("state", ""));

            // ...and then the slow one arrives with what it saw at the start.
            channels.release.countDown();
            slow.join(10000);

            List<Payload> sent = inventories();
            assertEquals("none", sent.get(sent.size() - 1).string("state", ""),
                    "the bot must be left on the newest answer, not the slowest: " + sent);
            assertEquals(1, sent.size(), "the stale read is dropped, not sent");
        }

        @Test
        @DisplayName("X, a slow read of Y, then X again: the bot stays on X")
        void flipFlopDoesNotSendTheStaleMiddle() throws Exception {
            setUp(ServerRole.STANDALONE, null);
            FakeChannels channels = new FakeChannels().channel("global");
            platform.withChatChannels(channels);
            enableAndSettle();

            // The bot holds X (active). A poll starts while the state is briefly Y (none)...
            channels.state(ChatChannels.State.NONE);
            channels.blockingNextState();
            Thread slow = new Thread(() -> module.reportInventory(false), "slow-inventory-read");
            slow.start();
            assertTrue(channels.entered.await(10, java.util.concurrent.TimeUnit.SECONDS));

            // ...it flips back to X, and a newer poll finds nothing changed...
            channels.state(ChatChannels.State.ACTIVE);
            module.reportInventory(false);

            // ...then the slow poll arrives with Y.
            channels.release.countDown();
            slow.join(10000);

            assertTrue(inventories().isEmpty(),
                    "the newer read confirmed X; sending the older Y would leave the bot wrong: "
                            + inventories());
        }

        @Test
        @DisplayName("disable stops the reconnect resend: the mode listener is unwound")
        void disableUnwindsTheModeListener() {
            setUp(ServerRole.STANDALONE, null);
            int before = tunnel.modeListenerCount();
            enable();
            assertEquals(before + 1, tunnel.modeListenerCount());

            disable();
            tunnel.clearSent();
            tunnel.disconnected();
            tunnel.reconnected();
            module.flush();

            assertEquals(before, tunnel.modeListenerCount());
            assertTrue(inventories().isEmpty());
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("lifecycle")
    class Lifecycle {

        @Test
        @DisplayName("disable removes the chat observer and empties both queues")
        void disableCleansUp() {
            setUp(ServerRole.STANDALONE, null);
            enable();
            say("Steve", "queued");
            sessions.join(FakePlayer.named("Steve"), 1L);
            assertTrue(module.queuedChatCount() > 0);

            disable();

            assertEquals(0, chatPipeline.observerCount());
            assertFalse(module.isObservingChat());
            assertEquals(0, module.queuedChatCount());
            assertEquals(0, module.queuedEventCount());
            assertEquals(0, sessions.joinListenerCount());
            assertEquals(0, sessions.deathListenerCount());
        }

        @Test
        @DisplayName("after disable, a stray flush sends nothing")
        void aStrayFlushIsSilent() {
            setUp(ServerRole.STANDALONE, null);
            enable();
            disable();
            tunnel.clearSent();

            say("Steve", "nobody is listening");
            // Enough flushes to cross an inventory poll too: a disabled module reports nothing.
            for (int i = 0; i < HeimdallBridgeModule.INVENTORY_POLL_FLUSHES; i++) {
                module.flush();
            }

            assertTrue(tunnel.sent().isEmpty());
        }

        @Test
        @DisplayName("enable, disable, enable leaves exactly one of everything")
        void reEnablingDoesNotDouble() {
            setUp(ServerRole.STANDALONE, null);
            enable();
            disable();
            enable();

            assertEquals(1, chatPipeline.observerCount());
            assertEquals(1, sessions.joinListenerCount());
            assertEquals(1, sessions.quitListenerCount());
            assertEquals(1, sessions.deathListenerCount());
            assertEquals(1, tunnel.subscriberCount(HeimdallBridgeModule.FRAME_DISCORD));

            say("Steve", "once");
            module.flush();
            assertEquals(1, tunnel.sent(HeimdallBridgeModule.FRAME_CHAT)
                    .get(0).payload().children("lines").size(),
                    "a doubled observer would have delivered this one line to two live consumers");
        }
    }

    // ── Relay-only ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("nothing this module logs carries a message body")
    void nothingLogsChatContent() {
        setUp(ServerRole.STANDALONE, null);
        enable();

        String secret = "correct-horse-battery-staple";
        for (int i = 0; i < 600; i++) {
            say("Steve", secret + " " + i);
        }
        tunnel.connected(false);
        module.flush();
        tunnel.connected(true);
        module.flush();
        disable();

        assertFalse(logger.records().toString().contains(secret),
                "chat content reaching a log file is exactly the storage this feature promises "
                        + "not to do — including on the drop, disconnect and teardown paths, which "
                        + "is where it is usually lost: " + logger.records());
    }

    @Test
    @DisplayName("the module exposes no way to read a queued message back")
    void theModuleHandsNoMessageBack() {
        // The same executable claim ChatPipeline makes about itself, one layer out: if a getLast, a
        // history or a drain-to-list ever appears here, this fails. The whole generic return type is
        // matched, so List<ChatLine> and Optional<ChatLine> are caught as readily as a bare one —
        // their erasures are List and Optional, which a check on the raw return type would wave
        // straight through.
        //
        // Naming the two value types works HERE and would not work on FrameBatcher: see the next
        // test.
        java.util.List<String> accessors = new java.util.ArrayList<String>();
        for (java.lang.reflect.Method method : HeimdallBridgeModule.class.getDeclaredMethods()) {
            if (method.isSynthetic()) {
                continue;
            }
            String returned = method.getGenericReturnType().toString();
            if (returned.contains("ChatLine") || returned.contains("SessionEvent")) {
                accessors.add("HeimdallBridgeModule." + method.getName());
            }
        }
        assertEquals(Collections.<String>emptyList(), accessors,
                "the bounded queue is the only holding point, and nothing on the module may hand an "
                        + "item back out of it");
    }

    @Test
    @DisplayName("FrameBatcher returns nothing but void, boolean and int — an allow-list, not a "
            + "name match")
    void theBatcherHandsNothingBack() {
        // FrameBatcher is the class that actually HOLDS chat, and it is the one a name-matching
        // guard cannot police: its queue is a ConcurrentLinkedQueue<T>, so the forbidden accessor
        // — `T peek()`, `List<T> drain()` — has a return type that prints as "T" and
        // "java.util.List<T>". Neither contains the string "ChatLine", so the check above would
        // pass on precisely the one class the rule is about.
        //
        // So this is an ALLOW-LIST rather than a deny-list, which is the only formulation that
        // cannot be outrun by a return type nobody thought of. Everything this class legitimately
        // answers is a primitive: how many are queued, and whether a flush sent anything. A future
        // method that genuinely needs to return something else fails here and has to say why in a
        // review — which is the whole point, and is the same trade ChatPipeline's own guard makes.
        java.util.Set<Class<?>> allowed = new java.util.HashSet<Class<?>>(
                java.util.Arrays.asList(void.class, boolean.class, int.class, long.class));

        java.util.List<String> offenders = new java.util.ArrayList<String>();
        for (java.lang.reflect.Method method : FrameBatcher.class.getDeclaredMethods()) {
            if (method.isSynthetic()) {
                continue;
            }
            if (!allowed.contains(method.getReturnType())) {
                offenders.add("FrameBatcher." + method.getName() + " -> "
                        + method.getGenericReturnType());
            }
        }
        assertEquals(Collections.<String>emptyList(), offenders,
                "FrameBatcher's queue is the single place a chat line rests, and nothing here may "
                        + "return anything that could carry one out — including behind a type "
                        + "variable, which is exactly what a name-matching guard cannot see");
    }
}
