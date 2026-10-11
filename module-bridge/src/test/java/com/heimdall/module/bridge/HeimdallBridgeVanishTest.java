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
import com.heimdall.core.pipeline.ChatPipeline;
import com.heimdall.core.pipeline.LoginPipeline;
import com.heimdall.core.platform.PlayerHandle;
import com.heimdall.core.platform.Vanish;
import com.heimdall.core.platform.VanishListener;
import com.heimdall.core.remoteconfig.ConfigDocument;
import com.heimdall.core.remoteconfig.RemoteConfig;
import com.heimdall.core.session.PlayerSessionEvents;
import com.heimdall.core.testing.FakePlatform;
import com.heimdall.core.testing.FakePlayer;
import com.heimdall.core.testing.RecordingTunnelBus;
import com.heimdall.core.util.Registration;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Vanished players' joins, leaves and deaths, and mid-session vanish toggles. Departure D88.
 *
 * <p>The same rig as {@link HeimdallBridgeModuleTest}: the real {@link ModuleManager}, the real
 * {@link PlayerSessionEvents} on an inline executor, and the module's own seams for drains and for
 * the deferred-join re-checks, so "the settle window passed" happens when a test says and never
 * because a slow machine took a while. The vanish integration is a fake the test steers directly,
 * because what is under test here is the bridge's session rules; the platform integrations are
 * tested against stand-ins for PremiumVanish's real classes in their own modules.
 */
class HeimdallBridgeVanishTest {

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
    private PlayerSessionEvents sessions;
    private ModuleManager manager;
    private HeimdallBridgeModule module;
    private FakeVanish vanish;
    private int configVersion;

    /** Deferred-join re-checks the module asked for, held until a test runs them. */
    private final List<Runnable> settleChecks = new ArrayList<Runnable>();
    private final List<Long> settleDelays = new ArrayList<Long>();

    /** The module's monotonic clock, advanced by each re-check by the delay it asked for. */
    private final java.util.concurrent.atomic.AtomicLong testNanos =
            new java.util.concurrent.atomic.AtomicLong();

    /** Milliseconds of test time since setUp. */
    private long nowMs() {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(testNanos.get());
    }

    private final FakePlayer steve = FakePlayer.named("Steve");
    private final FakePlayer alex = FakePlayer.named("Alex");

    private void setUp(Vanish integration, Payload settings) {
        executors = new HeimdallExecutors(logger, 1);
        platform = new FakePlatform(ServerRole.STANDALONE, dataDir).withVanish(integration);
        tunnel = new RecordingTunnelBus();
        configVersion = 0;
        remoteConfig = new RemoteConfig(
                logger, dataDir.resolve("remote-config.json"), ConfigDocument.empty());
        applySettings(settings);
        sessions = new PlayerSessionEvents(logger, INLINE);
        manager = new ModuleManager(ModuleEnvironment.builder()
                .logger(logger)
                .executors(executors)
                .tunnel(tunnel)
                .remoteConfig(remoteConfig)
                .loginPipeline(new LoginPipeline(logger))
                .chatPipeline(new ChatPipeline(logger))
                .playerSessions(sessions)
                .platform(platform)
                .build());
        module = new HeimdallBridgeModule();
        settleChecks.clear();
        settleDelays.clear();
        testNanos.set(0L);
        module.nanoClockForTests(() -> testNanos.get());
        // Drains are dropped: every test ships with an explicit flush().
        module.drainSchedulerForTests(new HeimdallBridgeModule.DrainScheduler() {
            @Override
            public void schedule(Runnable drain, long delayMs) {
            }
        });
        module.settleSchedulerForTests(new HeimdallBridgeModule.DrainScheduler() {
            @Override
            public void schedule(Runnable check, long delayMs) {
                settleDelays.add(delayMs);
                settleChecks.add(check);
            }
        });
        manager.register(module);
    }

    /** With PremiumVanish "installed": a fake that asks joins to wait. */
    private void setUpWithPremiumVanish(Payload settings) {
        vanish = new FakeVanish(1500L);
        setUp(vanish, settings);
    }

    /** With a vanish source that answers at once (the metadata convention alone, say). */
    private void setUpWithoutSettle(Payload settings) {
        vanish = new FakeVanish(0L);
        setUp(vanish, settings);
    }

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

    private static Payload announceToggles(boolean value) {
        return Payload.builder()
                .put(HeimdallBridgeModule.SETTING_ANNOUNCE_VANISH_TOGGLES, value)
                .build();
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

    /** Runs the next queued re-check, once the delay it asked for has passed. */
    private void runOneSettleCheck() {
        long delay = settleDelays.get(settleDelays.size() - settleChecks.size());
        testNanos.addAndGet(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(delay));
        settleChecks.remove(0).run();
    }

    /** Runs re-checks until none are queued, including the ones a check queues itself. */
    private int runSettleChecks() {
        int ran = 0;
        while (!settleChecks.isEmpty()) {
            runOneSettleCheck();
            ran++;
        }
        return ran;
    }

    /** "kind:name@ts" for every event shipped, in order. */
    private List<String> shipped() {
        module.flush();
        List<String> out = new ArrayList<String>();
        for (RecordingTunnelBus.Sent sent : tunnel.sent(HeimdallBridgeModule.FRAME_EVENT)) {
            for (Payload event : sent.payload().children("events")) {
                out.add(event.string("kind", "") + ":" + event.string("name", "")
                        + "@" + event.longValue("ts", -1L));
            }
        }
        return out;
    }

    // ── Without PremiumVanish ────────────────────────────────────────────────

    @Nested
    @DisplayName("without a vanish plugin")
    class WithoutVanish {

        @Test
        @DisplayName("Vanish.NONE: exactly the old behaviour, posted at once, no timer at all")
        void noIntegrationIsTheOldBehaviour() {
            setUp(Vanish.NONE, null);
            enable();
            assertFalse(module.isWatchingVanish(), "NONE has nothing to subscribe to");

            sessions.join(steve, 1000L);
            sessions.death(steve, "Steve fell from a high place", 1500L);
            sessions.quit(steve, 2000L);

            assertTrue(settleDelays.isEmpty(),
                    "without PremiumVanish a join must not wait for anything: " + settleDelays);
            assertEquals(Arrays.asList("join:Steve@1000", "death:Steve@1500", "leave:Steve@2000"),
                    shipped());
            assertEquals(0, module.sessionCount(), "a quit ends the session record");
        }

        @Test
        @DisplayName("a source with no settle window (metadata only) still never delays a join")
        void aZeroSettleWindowNeverDefers() {
            setUpWithoutSettle(null);
            enable();

            sessions.join(steve, 1000L);

            assertTrue(settleDelays.isEmpty());
            assertEquals(Collections.singletonList("join:Steve@1000"), shipped(),
                    "posted in the join's own call, before any flush could have waited for it");
        }

        @Test
        @DisplayName("an integration that throws: the module still enables, and the check fails closed")
        void aThrowingIntegrationFailsClosed() {
            setUp(new Vanish() {
                @Override
                public long joinWaitMillis(PlayerHandle player, long elapsedMs) {
                    throw new IllegalStateException("broken");
                }

                @Override
                public boolean isVanished(PlayerHandle player) {
                    throw new LinkageError("moved");
                }

                @Override
                public Registration onToggle(VanishListener listener) {
                    throw new IllegalStateException("broken");
                }
            }, null);
            // A throwing onToggle must not stop the module enabling either.
            enable();

            sessions.join(steve, 1L);
            sessions.quit(steve, 2L);

            assertTrue(shipped().isEmpty(), "a check that could not answer counts as vanished: one "
                    + "missing post beats publishing a hidden staff member");
            assertTrue(settleDelays.isEmpty(), "and a wait that could not answer does not wait");
        }

        @Test
        @DisplayName("a quit from an earlier session does not end the newer one")
        void aStaleQuitLeavesTheNewerSessionAlone() {
            // heimdall-io does not order notifications, so a quick reconnect can deliver the second
            // join before the first quit. The first quit still posts; the newer session survives it.
            setUp(Vanish.NONE, null);
            enable();

            sessions.join(steve, 100L);
            sessions.join(steve, 300L);
            sessions.quit(steve, 200L);
            assertEquals(1, module.sessionCount(), "the session from the second join is still open");
            sessions.quit(steve, 400L);

            assertEquals(Arrays.asList(
                    "join:Steve@100", "join:Steve@300", "leave:Steve@200", "leave:Steve@400"),
                    shipped());
            assertEquals(0, module.sessionCount());
        }
    }

    // ── Suppression ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("a vanished player")
    class Suppression {

        @Test
        @DisplayName("joining vanished: no join, no death, no leave")
        void nothingIsRelayedForAVanishedSession() {
            setUpWithoutSettle(null);
            enable();
            vanish.hide(steve);

            sessions.join(steve, 1000L);
            sessions.death(steve, "Steve was slain by a zombie", 1500L);
            sessions.quit(steve, 2000L);

            assertTrue(shipped().isEmpty(), "the server hid this session; Discord must not see it");
            assertFalse(logger.records().toString().contains("slain by a zombie"),
                    "and declining to relay a death must not be the moment it lands in a log");
        }

        @Test
        @DisplayName("other players are unaffected")
        void onlyTheVanishedPlayerIsSuppressed() {
            setUpWithoutSettle(null);
            enable();
            vanish.hide(steve);

            sessions.join(steve, 1L);
            sessions.join(alex, 2L);
            sessions.quit(alex, 3L);
            sessions.quit(steve, 4L);

            assertEquals(Arrays.asList("join:Alex@2", "leave:Alex@3"), shipped());
        }

        @Test
        @DisplayName("a suppressed join keeps the leave quiet even after the vanish has gone")
        void aQuietJoinMeansAQuietLeave() {
            // The state can be cleared before the quit fires (a plugin that drops its record at
            // disconnect, or a silent reappearance). A leave without its join is the bug.
            setUpWithoutSettle(null);
            enable();
            vanish.hide(steve);
            sessions.join(steve, 1L);

            vanish.vanished.clear();
            sessions.quit(steve, 2L);

            assertTrue(shipped().isEmpty(), "Discord never saw this player join: " + shipped());
        }

        @Test
        @DisplayName("visible at join, vanished at quit: the join posts, the leave does not")
        void vanishedAtQuitSuppressesTheLeave() {
            setUpWithoutSettle(null);
            enable();
            sessions.join(steve, 1L);

            vanish.vanished.add(steve.uuid());
            sessions.death(steve, "Steve drowned", 2L);
            sessions.quit(steve, 3L);

            assertEquals(Collections.singletonList("join:Steve@1"), shipped(),
                    "a vanished player's death and leave are never relayed (decision 2)");
        }

        @Test
        @DisplayName("a leave with no session to go on is still suppressed for a vanished player")
        void aSessionlessLeaveIsSuppressed() {
            // A player online before the listeners existed and missed by the seed: only the vanish
            // plugin can say, and it says hidden.
            setUpWithoutSettle(null);
            enable();
            vanish.hide(steve);

            sessions.quit(steve, 1L);

            assertTrue(shipped().isEmpty());
            assertEquals(0, module.sessionCount());
        }

        @Test
        @DisplayName("a death with no session to go on is still suppressed for a vanished player")
        void aSessionlessDeathIsSuppressed() {
            setUpWithoutSettle(null);
            enable();
            vanish.hide(steve);

            sessions.death(steve, "Steve tried to swim in lava", 1L);

            assertTrue(shipped().isEmpty());
        }

        @Test
        @DisplayName("a quiet session's leave does not ask the vanish plugin at all")
        void aQuietLeaveSkipsTheLookup() {
            // On a proxy the lookup for a departed player is getAllInvisiblePlayers, which can be a
            // database query. A session already known to be quiet needs no answer.
            setUpWithoutSettle(null);
            enable();
            vanish.hide(steve);
            sessions.join(steve, 1L);
            int checks = vanish.checks.get();

            sessions.quit(steve, 2L);

            assertEquals(checks, vanish.checks.get());
            assertTrue(shipped().isEmpty());
        }

        @Test
        @DisplayName("a previous session's quit, delivered after a quiet rejoin, keeps the new session")
        void aStaleQuitLeavesAQuietSessionAlone() {
            // heimdall-io does not order notifications: the first session's quit can arrive after
            // the second session's (quiet) join. It must not end the quiet session, or the real
            // leave later has nothing to go on and is judged by the vanish plugin alone.
            setUpWithoutSettle(null);
            enable();
            sessions.join(steve, 100L);
            vanish.hide(steve);
            sessions.join(steve, 300L);
            vanish.vanished.clear();

            sessions.quit(steve, 200L);
            assertEquals(1, module.sessionCount(), "the quiet session from the second join survives");
            sessions.quit(steve, 400L);

            assertEquals(Collections.singletonList("join:Steve@100"), shipped(),
                    "the stale quit is judged by the newer, quiet session, and the real leave "
                            + "of a quiet session is never posted");
        }

        @Test
        @DisplayName("a hide seen before the join notification decides that join")
        void anEarlyToggleBelongsToTheJoin() {
            // PremiumVanish's auto-vanish-on-join fires inside the platform's join event, ahead of
            // the session notification. By the time the join is handled the plugin may have nothing
            // to say (here: cleared), but the toggle already did.
            setUpWithoutSettle(null);
            enable();
            vanish.toggle(steve, true, true, 1L);
            vanish.vanished.clear();

            sessions.join(steve, 2L);
            sessions.quit(steve, 3L);

            assertTrue(shipped().isEmpty(), "auto-vanish on join must keep the session quiet");
        }
    }

    // ── The deferred join ────────────────────────────────────────────────────

    @Nested
    @DisplayName("with PremiumVanish, a join waits for the settle window")
    class DeferredJoin {

        @Test
        @DisplayName("a visible player's join posts after the window, with the join's own timestamp")
        void aVisibleJoinPostsAfterTheWindow() {
            setUpWithPremiumVanish(null);
            enable();

            sessions.join(steve, 1000L);
            assertTrue(shipped().isEmpty(), "nothing before the window has passed");

            int checks = runSettleChecks();

            assertEquals(6, checks, "1500 ms at one re-check every 250 ms");
            for (Long delay : settleDelays) {
                assertEquals(HeimdallBridgeModule.SETTLE_CHECK_MS, delay.longValue());
            }
            assertEquals(Collections.singletonList("join:Steve@1000"), shipped(),
                    "the original timestamp, so the bot's duplicate drop keys it like any other copy");
        }

        @Test
        @DisplayName("marked vanished part-way through the window: never posted")
        void aLateMarkIsCaughtByTheRecheck() {
            setUpWithPremiumVanish(null);
            enable();

            sessions.join(steve, 1000L);
            runOneSettleCheck();
            runOneSettleCheck();
            // The proxy learns the state from a backend, without an event.
            vanish.vanished.add(steve.uuid());
            runSettleChecks();
            sessions.quit(steve, 5000L);

            assertTrue(shipped().isEmpty(), "a late mark must suppress the join and its leave");
        }

        @Test
        @DisplayName("a hide event inside the window decides at once, and later checks do nothing")
        void aHideInsideTheWindowDecides() {
            setUpWithPremiumVanish(null);
            enable();

            sessions.join(steve, 1000L);
            vanish.toggle(steve, true, false, 1100L);
            vanish.vanished.clear();
            runSettleChecks();
            sessions.quit(steve, 5000L);

            assertTrue(shipped().isEmpty(),
                    "a hide that is part of the join is not a mid-session toggle: " + shipped());
        }

        @Test
        @DisplayName("a quick join and leave inside the window post both, join first, once each")
        void aQuickJoinAndLeaveStayPaired() {
            setUpWithPremiumVanish(null);
            enable();

            sessions.join(steve, 1000L);
            runOneSettleCheck();
            sessions.quit(steve, 1200L);
            runSettleChecks();

            assertEquals(Arrays.asList("join:Steve@1000", "leave:Steve@1200"), shipped(),
                    "never a leave without its join, never a join after its leave, never twice");
        }

        @Test
        @DisplayName("a quick join and leave of a vanished player post nothing")
        void aQuickVanishedVisitPostsNothing() {
            setUpWithPremiumVanish(null);
            enable();

            sessions.join(steve, 1000L);
            vanish.vanished.add(steve.uuid());
            sessions.quit(steve, 1200L);
            runSettleChecks();

            assertTrue(shipped().isEmpty());
        }

        @Test
        @DisplayName("a death inside the window posts the join first")
        void aDeathInsideTheWindowDecidesTheJoin() {
            setUpWithPremiumVanish(null);
            enable();

            sessions.join(steve, 1000L);
            sessions.death(steve, "Steve fell out of the world", 1100L);
            runSettleChecks();

            assertEquals(Arrays.asList("join:Steve@1000", "death:Steve@1100"), shipped());
        }

        @Test
        @DisplayName("vanished at the join itself: decided at once, no timer")
        void vanishedAtJoinNeedsNoWindow() {
            setUpWithPremiumVanish(null);
            enable();
            vanish.hide(steve);

            sessions.join(steve, 1000L);

            assertTrue(settleDelays.isEmpty(), "nothing left to wait for");
            assertTrue(shipped().isEmpty());
        }

        @Test
        @DisplayName("a disable inside the window drops the join; a check that runs later posts nothing")
        void aDisableDropsAPendingJoin() {
            setUpWithPremiumVanish(null);
            enable();

            sessions.join(steve, 1000L);
            disable();
            runSettleChecks();

            assertTrue(tunnel.sent(HeimdallBridgeModule.FRAME_EVENT).isEmpty());
            assertEquals(0, module.sessionCount());
        }
    }

    // ── announceVanishToggles ────────────────────────────────────────────────

    @Nested
    @DisplayName("announceVanishToggles")
    class AnnounceToggles {

        @Test
        @DisplayName("defaults OFF: vanishing and reappearing mid-session post nothing")
        void offByDefault() {
            setUpWithoutSettle(null);
            enable();
            sessions.join(steve, 1L);

            vanish.toggle(steve, true, false, 2L);
            vanish.toggle(steve, false, false, 3L);
            sessions.quit(steve, 4L);

            assertEquals(Arrays.asList("join:Steve@1", "leave:Steve@4"), shipped(),
                    "visible at the quit, and Discord saw the join, so the real leave posts");
        }

        @Test
        @DisplayName("off: joined vanished and reappeared silently, the leave stays quiet")
        void offKeepsAQuietSessionQuiet() {
            setUpWithoutSettle(null);
            enable();
            vanish.hide(steve);
            sessions.join(steve, 1L);

            vanish.toggle(steve, false, false, 2L);
            sessions.death(steve, "Steve hit the ground too hard", 3L);
            sessions.quit(steve, 4L);

            assertTrue(shipped().isEmpty(), "Discord was never told this player is here, so it is "
                    + "told nothing about them until it is: " + shipped());
        }

        @Test
        @DisplayName("on: vanish posts a leave, reappear posts a join, the real leave posts after")
        void onMirrorsWhatPlayersSee() {
            setUpWithoutSettle(announceToggles(true));
            enable();
            sessions.join(steve, 1L);

            vanish.toggle(steve, true, false, 2L);
            vanish.toggle(steve, false, false, 3L);
            sessions.quit(steve, 4L);

            assertEquals(Arrays.asList(
                    "join:Steve@1", "leave:Steve@2", "join:Steve@3", "leave:Steve@4"), shipped(),
                    "the same kinds a real session uses, so the bot needs no change");
        }

        @Test
        @DisplayName("on: joined vanished, a reappearance posts the join, then the leave posts")
        void onAnnouncesAReappearanceAfterAQuietJoin() {
            setUpWithoutSettle(announceToggles(true));
            enable();
            vanish.hide(steve);
            sessions.join(steve, 1L);

            vanish.toggle(steve, false, false, 2L);
            sessions.quit(steve, 3L);

            assertEquals(Arrays.asList("join:Steve@2", "leave:Steve@3"), shipped());
        }

        @Test
        @DisplayName("on: leaving while vanished after a mirrored vanish posts no second leave")
        void onNeverPostsTwoLeaves() {
            setUpWithoutSettle(announceToggles(true));
            enable();
            sessions.join(steve, 1L);

            vanish.toggle(steve, true, false, 2L);
            sessions.death(steve, "Steve burned", 3L);
            sessions.quit(steve, 4L);

            assertEquals(Arrays.asList("join:Steve@1", "leave:Steve@2"), shipped());
        }

        @Test
        @DisplayName("on: a quiet session getting another hide never posts a leave")
        void aQuietSessionNeverPostsALeaveForAHide() {
            setUpWithoutSettle(announceToggles(true));
            enable();
            vanish.hide(steve);
            sessions.join(steve, 1L);

            vanish.toggle(steve, true, false, 2L);
            sessions.quit(steve, 3L);

            assertTrue(shipped().isEmpty(), "Discord was never told Steve is here: " + shipped());
        }

        @Test
        @DisplayName("on: a silent toggle is not announced, because players in game saw nothing")
        void onIgnoresASilentToggle() {
            setUpWithoutSettle(announceToggles(true));
            enable();
            sessions.join(steve, 1L);

            vanish.toggle(steve, true, true, 2L);
            vanish.toggle(steve, false, true, 3L);
            sessions.quit(steve, 4L);

            assertEquals(Arrays.asList("join:Steve@1", "leave:Steve@4"), shipped());
        }

        @Test
        @DisplayName("on, but relayEvents off: a toggle posts nothing")
        void relayEventsGatesToggles() {
            setUpWithoutSettle(Payload.builder()
                    .put(HeimdallBridgeModule.SETTING_ANNOUNCE_VANISH_TOGGLES, true)
                    .put(HeimdallBridgeModule.SETTING_RELAY_EVENTS, false)
                    .build());
            enable();
            sessions.join(steve, 1L);

            vanish.toggle(steve, true, false, 2L);
            vanish.toggle(steve, false, false, 3L);

            assertTrue(shipped().isEmpty(), "toggles go through the same relayEvents choke point");
        }

        @Test
        @DisplayName("the setting is read live: a dashboard flip applies to the next toggle")
        void theSettingIsLive() {
            setUpWithoutSettle(null);
            enable();
            sessions.join(steve, 1L);
            vanish.toggle(steve, true, false, 2L);
            vanish.toggle(steve, false, false, 3L);

            applySettings(announceToggles(true));
            vanish.toggle(steve, true, false, 4L);

            applySettings(announceToggles(false));
            vanish.toggle(steve, false, false, 5L);
            sessions.quit(steve, 6L);

            assertEquals(Arrays.asList("join:Steve@1", "leave:Steve@4"), shipped(),
                    "on for the second vanish only; Discord then thinks Steve left, so the silent "
                            + "reappearance and the real quit stay quiet");
        }

        @Test
        @DisplayName("turned on after a silent vanish: reappearing does not repeat a join Discord has")
        void noDuplicateJoinAfterTheSettingIsTurnedOn() {
            setUpWithoutSettle(null);
            enable();
            sessions.join(steve, 1L);
            vanish.toggle(steve, true, false, 2L);

            applySettings(announceToggles(true));
            vanish.toggle(steve, false, false, 3L);
            sessions.quit(steve, 4L);

            assertEquals(Arrays.asList("join:Steve@1", "leave:Steve@4"), shipped());
        }
    }

    // ── On a proxy ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("on a proxy, the wait is anchored to the backend connection")
    class OnAProxy {

        private void setUpProxy(Payload settings) {
            setUpWithPremiumVanish(settings);
            vanish.proxy = true;
        }

        /** Enables, then puts Steve on the proxy, as a real login would before the join arrives. */
        private void enableWithSteveOnline() {
            enable();
            platform.join(steve);
        }

        private void connectToBackendNow(PlayerHandle player) {
            vanish.backendAtElapsedMs.put(player.uuid(), nowMs());
        }

        /** Runs {@code count} re-checks. */
        private void runChecks(int count) {
            for (int i = 0; i < count; i++) {
                runOneSettleCheck();
            }
        }

        @Test
        @DisplayName("a visible join posts 1.5 s after the backend connection, not after the join")
        void postsAfterTheBackendWindow() {
            setUpProxy(null);
            enableWithSteveOnline();

            sessions.join(steve, 1000L);
            runChecks(3);
            assertTrue(shipped().isEmpty(), "3 s in and no backend yet: still waiting");
            connectToBackendNow(steve);
            runSettleChecks();

            assertEquals(Collections.singletonList("join:Steve@1000"), shipped());
            assertEquals(4500L, nowMs(), "decided 1.5 s after the connection at 3 s");
        }

        @Test
        @DisplayName("waiting for a backend re-checks once a second, not four times")
        void theBackendWaitIsSlow() {
            setUpProxy(null);
            enableWithSteveOnline();

            sessions.join(steve, 1000L);
            runChecks(5);

            for (Long delay : settleDelays) {
                assertEquals(HeimdallBridgeModule.BACKEND_RECHECK_MS, delay.longValue());
            }
        }

        @Test
        @DisplayName("an auth limbo past 10 s, then a backend, then vanished: nothing is ever posted")
        void aLongLimboThenVanishedPostsNothing() {
            // The cap this replaced would have announced this staff member at 10 s.
            setUpProxy(null);
            enableWithSteveOnline();

            sessions.join(steve, 1000L);
            runChecks(30);
            assertTrue(shipped().isEmpty(), "30 s of limbo, and still undecided");
            connectToBackendNow(steve);
            runOneSettleCheck();
            // The backend's PremiumVanish has now told the proxy's.
            vanish.vanished.add(steve.uuid());
            runSettleChecks();
            sessions.quit(steve, 90_000L);

            assertTrue(shipped().isEmpty(), shipped().toString());
        }

        @Test
        @DisplayName("an auth limbo past 10 s, then a backend, still visible: the join posts after it")
        void aLongLimboThenVisiblePostsAfterTheBackend() {
            setUpProxy(null);
            enableWithSteveOnline();

            sessions.join(steve, 1000L);
            runChecks(30);
            connectToBackendNow(steve);
            runSettleChecks();

            assertEquals(Collections.singletonList("join:Steve@1000"), shipped());
            assertEquals(31_500L, nowMs());
        }

        @Test
        @DisplayName("leaving before ever reaching a backend posts neither the join nor the leave")
        void aDisconnectInLimboPostsNothing() {
            setUpProxy(null);
            enableWithSteveOnline();

            sessions.join(steve, 1000L);
            runChecks(15);
            sessions.quit(steve, 16_000L);
            runSettleChecks();

            assertTrue(shipped().isEmpty(), "nobody in game saw this session: " + shipped());
            assertEquals(0, module.sessionCount());
        }

        @Test
        @DisplayName("marked vanished after the backend connection: never posted")
        void aMarkAfterTheBackendIsCaught() {
            setUpProxy(null);
            enableWithSteveOnline();

            sessions.join(steve, 1000L);
            runChecks(3);
            connectToBackendNow(steve);
            runOneSettleCheck();
            vanish.vanished.add(steve.uuid());
            runSettleChecks();
            sessions.quit(steve, 9000L);

            assertTrue(shipped().isEmpty(), "this is the leak a join-anchored window let through");
        }

        @Test
        @DisplayName("the hook retired mid-limbo (a swap, a shutdown): the next check posts nothing")
        void aRetiredHookNeverAnnouncesALimboPlayer() {
            // The reviewer's probe: the generation's teardown takes the hook off before the modules
            // stop, so a re-check can fire in between. Its wait now answers "decide now", but the
            // player is still awaiting a backend, and that must win.
            setUpProxy(null);
            enableWithSteveOnline();

            sessions.join(steve, 1000L);
            runChecks(15);
            vanish.retired = true;
            runChecks(3);

            assertTrue(shipped().isEmpty(), "a staff member in limbo must not be announced: "
                    + shipped());
            assertEquals(1, module.sessionCount(), "still held, waiting for a backend");
        }

        @Test
        @DisplayName("the scheduler refusing at shutdown decides a limbo join quiet, for good")
        void aRefusedRecheckDecidesQuiet() {
            // A refused re-check is the last one there will be, so a join still awaiting a backend is
            // decided quiet then, not left pending (where a later connection would post it) and not
            // posted.
            setUpProxy(null);
            enableWithSteveOnline();
            final java.util.concurrent.atomic.AtomicBoolean refuseNext =
                    new java.util.concurrent.atomic.AtomicBoolean();
            module.settleSchedulerForTests(new HeimdallBridgeModule.DrainScheduler() {
                @Override
                public void schedule(Runnable check, long delayMs) {
                    if (refuseNext.getAndSet(false)) {
                        throw new java.util.concurrent.RejectedExecutionException("shutting down");
                    }
                    settleDelays.add(delayMs);
                    settleChecks.add(check);
                }
            });

            sessions.join(steve, 1000L);
            runChecks(2);
            refuseNext.set(true);
            runChecks(1);
            connectToBackendNow(steve);
            runSettleChecks();

            assertTrue(shipped().isEmpty(), shipped().toString());
            assertTrue(settleChecks.isEmpty(), "decided, so nothing is left re-checking");
        }

        @Test
        @DisplayName("a quit that overtakes its join: no leave, and the orphaned join is dropped")
        void aQuitOvertakingItsJoinLeavesNothing() {
            // heimdall-io does not order notifications. A plugin kicking at PostLogin, or an instant
            // drop, can deliver the quit first; the join then arrives for somebody already gone.
            setUpProxy(null);
            enable();

            sessions.quit(steve, 1001L);
            sessions.join(steve, 1000L);
            runChecks(1);

            assertTrue(shipped().isEmpty(),
                    "no leave for a player who never reached a backend, and no join: " + shipped());
            assertEquals(0, module.sessionCount(), "the orphan is not re-checked for ever");
            assertTrue(settleChecks.isEmpty(), "and nothing is left scheduled");
        }

        @Test
        @DisplayName("a player in limbo across a swap: held, and posted once they reach a backend")
        void aLimboPlayerSeededAtEnableIsHeld() {
            setUpProxy(null);
            platform.join(steve);
            enable();
            assertEquals(1, module.sessionCount());

            runChecks(5);
            assertTrue(shipped().isEmpty(), "still in limbo: nothing yet");
            connectToBackendNow(steve);
            runSettleChecks();

            assertEquals(1, shipped().size(), "the join the previous core never posted: " + shipped());
            assertTrue(shipped().get(0).startsWith("join:Steve@"));
        }

        @Test
        @DisplayName("a player in limbo across a swap who leaves without a backend: no leave posted")
        void aLimboPlayerSeededAtEnableLeavesQuietly() {
            setUpProxy(null);
            platform.join(steve);
            enable();

            runChecks(2);
            sessions.quit(steve, System.currentTimeMillis() + 60_000L);

            assertTrue(shipped().isEmpty(), "a leave with no join is exactly what this prevents: "
                    + shipped());
        }

        @Test
        @DisplayName("backend hook unavailable: decided as visible at the 10 s cap, as before")
        void withoutTheHookTheCapPosts() {
            setUpProxy(null);
            vanish.backendHook = false;
            enableWithSteveOnline();

            sessions.join(steve, 1000L);
            runSettleChecks();

            assertEquals(Collections.singletonList("join:Steve@1000"), shipped());
            assertEquals(FakeVanish.CAP_MS, nowMs());
        }

        @Test
        @DisplayName("a hide before the decision is part of the join: no join, and never a leave")
        void aHideBeforeTheDecisionSuppressesTheJoin() {
            // Proxies deliver every toggle as silent (they cannot tell); with the setting on, a hide
            // arriving while the join is still undecided must neither post a leave nor let the join
            // through.
            setUpProxy(announceToggles(true));
            enableWithSteveOnline();

            sessions.join(steve, 1000L);
            runOneSettleCheck();
            vanish.toggle(steve, true, true, 1300L);
            vanish.vanished.clear();
            connectToBackendNow(steve);
            runSettleChecks();
            sessions.quit(steve, 20_000L);

            assertTrue(shipped().isEmpty(), shipped().toString());
        }

        @Test
        @DisplayName("with the setting on, a proxy's toggles (always silent) announce nothing")
        void aProxyNeverAnnouncesToggles() {
            // The backend that announced the vanish in game relays its own leave; the proxy's copy
            // would land at a different time and slip past the bot's duplicate drop.
            setUpProxy(announceToggles(true));
            enableWithSteveOnline();
            sessions.join(steve, 1000L);
            connectToBackendNow(steve);
            runSettleChecks();

            vanish.toggle(steve, true, true, 20_000L);
            vanish.toggle(steve, false, true, 21_000L);
            sessions.quit(steve, 22_000L);

            assertEquals(Arrays.asList("join:Steve@1000", "leave:Steve@22000"), shipped());
        }
    }

    // ── Players already online, and teardown ─────────────────────────────────

    @Nested
    @DisplayName("players online at enable, and teardown")
    class Lifecycle {

        @Test
        @DisplayName("players online at enable are seeded: a hidden one stays quiet, a visible one leaves")
        void onlinePlayersAreSeeded() {
            setUpWithoutSettle(announceToggles(true));
            platform.join(steve);
            platform.join(alex);
            vanish.hide(steve);
            enable();
            assertEquals(2, module.sessionCount());

            vanish.vanished.clear();
            sessions.quit(steve, 1L);
            vanish.toggle(alex, true, false, 2L);
            sessions.quit(alex, 3L);

            assertEquals(Collections.singletonList("leave:Alex@2"), shipped(),
                    "a hot swap or a mid-session enable must not lose who Discord thinks is here");
        }

        @Test
        @DisplayName("disable closes the toggle subscription and forgets every session")
        void disableTearsDown() {
            setUpWithoutSettle(announceToggles(true));
            enable();
            assertTrue(module.isWatchingVanish());
            assertEquals(1, vanish.listenerCount());
            sessions.join(steve, 1L);

            disable();

            assertFalse(module.isWatchingVanish());
            assertEquals(0, vanish.listenerCount(), "a disabled module must not hear toggles");
            assertEquals(0, module.sessionCount());
            tunnel.clearSent();
            vanish.toggle(steve, true, false, 2L);
            assertTrue(tunnel.sent(HeimdallBridgeModule.FRAME_EVENT).isEmpty());
        }

        @Test
        @DisplayName("re-enable subscribes exactly once, not once per cycle")
        void reEnableDoesNotDoubleSubscribe() {
            setUpWithoutSettle(announceToggles(true));
            enable();
            disable();
            enable();

            assertEquals(1, vanish.listenerCount());
            sessions.join(steve, 1L);
            vanish.toggle(steve, true, false, 2L);
            assertEquals(Arrays.asList("join:Steve@1", "leave:Steve@2"), shipped(),
                    "one leave, not one per enable cycle");
        }
    }

    // ── The fake ─────────────────────────────────────────────────────────────

    /** A vanish integration the test steers: who is hidden, how long joins wait, and toggles. */
    static final class FakeVanish implements Vanish {

        final Set<UUID> vanished = Collections.newSetFromMap(new ConcurrentHashMap<UUID, Boolean>());
        private final long settleMillis;

        /**
         * Proxy mode, as {@code VanishSupport} does it: the settle window counts from the player's
         * first backend connection (test-time milliseconds since the join), capped from the join.
         */
        boolean proxy;

        /** Whether the proxy's backend hook is live: no cap, and a join waits for the connection. */
        boolean backendHook = true;

        /**
         * The hook has been taken off (a swap's teardown, close, a broken integration): the wait
         * answers "decide now", as VanishSupport's would with no binding, while a player it was
         * already waiting for is still awaiting.
         */
        boolean retired;
        final java.util.Map<UUID, Long> backendAtElapsedMs = new ConcurrentHashMap<UUID, Long>();
        static final long CAP_MS = 10_000L;
        static final long RECHECK_MS = 1000L;
        private final CopyOnWriteArrayList<VanishListener> listeners =
                new CopyOnWriteArrayList<VanishListener>();
        final AtomicInteger checks = new AtomicInteger();

        FakeVanish(long settleMillis) {
            this.settleMillis = settleMillis;
        }

        void hide(PlayerHandle player) {
            vanished.add(player.uuid());
        }

        /** Changes the state and fires the event, as PremiumVanish does. */
        void toggle(PlayerHandle player, boolean hidden, boolean silent, long timestampMs) {
            if (hidden) {
                vanished.add(player.uuid());
            } else {
                vanished.remove(player.uuid());
            }
            for (VanishListener listener : listeners) {
                listener.onVanishChanged(player, hidden, silent, timestampMs);
            }
        }

        int listenerCount() {
            return listeners.size();
        }

        @Override
        public long joinWaitMillis(PlayerHandle player, long elapsedMs) {
            if (settleMillis <= 0L || retired) {
                return 0L;
            }
            if (!proxy) {
                return Math.max(0L, settleMillis - elapsedMs);
            }
            Long connected = backendAtElapsedMs.get(player.uuid());
            if (connected == null) {
                return backendHook ? RECHECK_MS : Math.max(0L, CAP_MS - elapsedMs);
            }
            return Math.max(0L, settleMillis - (elapsedMs - connected));
        }

        @Override
        public boolean awaitingBackend(PlayerHandle player) {
            return proxy && settleMillis > 0L && backendHook
                    && !backendAtElapsedMs.containsKey(player.uuid());
        }

        @Override
        public boolean isVanished(PlayerHandle player) {
            checks.incrementAndGet();
            return vanished.contains(player.uuid());
        }

        @Override
        public Registration onToggle(final VanishListener listener) {
            listeners.add(listener);
            return Registration.once(new Runnable() {
                @Override
                public void run() {
                    listeners.remove(listener);
                }
            });
        }
    }
}
