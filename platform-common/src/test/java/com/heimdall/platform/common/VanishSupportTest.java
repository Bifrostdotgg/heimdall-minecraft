package com.heimdall.platform.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.log.RecordingLogger;
import com.heimdall.core.platform.PlayerHandle;
import com.heimdall.core.platform.VanishListener;
import com.heimdall.core.testing.FakePlayer;
import com.heimdall.core.util.Registration;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The platform-neutral half of the PremiumVanish integration: resolution, the hook's lifecycle,
 * containment. Departure D88. Each platform's reflective half is tested in its own module against
 * stand-ins for PremiumVanish's real classes.
 */
class VanishSupportTest {

    private final RecordingLogger logger = new RecordingLogger(true);
    private final CoreRegistrations registrations = CoreRegistrations.untracked();
    private final FakePlayer steve = FakePlayer.named("Steve");

    /** The join wait's monotonic clock. */
    private final java.util.concurrent.atomic.AtomicLong nanos =
            new java.util.concurrent.atomic.AtomicLong(1_000_000_000L);

    private void advanceMs(long ms) {
        nanos.addAndGet(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(ms));
    }

    /** A platform whose PremiumVanish the test installs, removes, breaks and reloads at will. */
    private final class TestVanish extends VanishSupport {

        volatile boolean installed;
        volatile boolean current = true;
        volatile Throwable bindFailure;
        volatile Throwable queryFailure;
        volatile RuntimeException conventionFailure;
        final Set<UUID> invisible = Collections.newSetFromMap(new ConcurrentHashMap<UUID, Boolean>());
        final Set<UUID> byConvention =
                Collections.newSetFromMap(new ConcurrentHashMap<UUID, Boolean>());
        final AtomicInteger binds = new AtomicInteger();
        final AtomicInteger hooksOpen = new AtomicInteger();
        final AtomicInteger hooksMade = new AtomicInteger();

        TestVanish() {
            this(false);
        }

        /** Whether this proxy's hook manages to register its login and backend listeners. */
        volatile boolean watchBackends = true;
        volatile boolean hookFails;
        volatile boolean online = true;
        private final boolean proxy;

        TestVanish(boolean proxy) {
            super(VanishSupportTest.this.logger, VanishSupportTest.this.registrations, proxy,
                    () -> VanishSupportTest.this.nanos.get());
            this.proxy = proxy;
        }

        @Override
        protected boolean isOnline(UUID player) {
            return online;
        }

        void login(PlayerHandle player) {
            sessionStarted(player.uuid());
        }

        @Override
        protected boolean vanishedByConvention(PlayerHandle player) {
            if (conventionFailure != null) {
                throw conventionFailure;
            }
            return byConvention.contains(player.uuid());
        }

        void connect(PlayerHandle player) {
            backendConnected(player.uuid());
        }

        @Override
        protected Binding bind() throws Throwable {
            binds.incrementAndGet();
            if (bindFailure != null) {
                throw bindFailure;
            }
            if (!installed) {
                return null;
            }
            current = true;
            return new Binding() {
                @Override
                public boolean current() {
                    return current;
                }

                @Override
                public boolean vanished(PlayerHandle player) throws Throwable {
                    if (queryFailure != null) {
                        throw queryFailure;
                    }
                    return invisible.contains(player.uuid());
                }

                @Override
                public Registration hook() {
                    if (hookFails) {
                        throw new IllegalStateException("could not register");
                    }
                    if (proxy && watchBackends) {
                        watchingBackends();
                    }
                    hooksMade.incrementAndGet();
                    hooksOpen.incrementAndGet();
                    return Registration.once(new Runnable() {
                        @Override
                        public void run() {
                            hooksOpen.decrementAndGet();
                        }
                    });
                }
            };
        }

        void fire(PlayerHandle player, boolean vanished, boolean silent) {
            toggled(player, vanished, silent);
        }
    }

    private static final class Recorder implements VanishListener {
        final List<String> seen = new ArrayList<String>();

        @Override
        public void onVanishChanged(
                PlayerHandle player, boolean vanished, boolean silent, long timestampMs) {
            seen.add(player.name() + (vanished ? " hid" : " showed") + (silent ? " silently" : ""));
        }
    }

    @Test
    @DisplayName("absent: no settle window, nobody vanished, no hook, and asking again retries")
    void absentIsNone() {
        TestVanish vanish = new TestVanish();

        assertEquals(0L, vanish.joinWaitMillis(steve, 0L), "no PremiumVanish must mean no delay at all");
        assertFalse(vanish.isVanished(steve));
        assertNull(vanish.brokenReason(), "a miss (it may load after us) is never a failure");
        assertNotNull(vanish.onToggle(new Recorder()));
        assertEquals(0, vanish.hooksMade.get());
        int before = vanish.binds.get();

        vanish.isVanished(steve);
        assertTrue(vanish.binds.get() > before,
                "a missing plugin is looked for again: it may simply have loaded second");
    }

    @Test
    @DisplayName("the metadata-style convention answers with no plugin at all")
    void conventionNeedsNoPlugin() {
        TestVanish vanish = new TestVanish();
        vanish.byConvention.add(steve.uuid());

        assertTrue(vanish.isVanished(steve));
        assertEquals(0L, vanish.joinWaitMillis(steve, 0L), "the convention never asks a join to wait");
    }

    @Test
    @DisplayName("installed later: found on the next call, hooked once, and asks joins to wait")
    void foundWhenItArrives() {
        TestVanish vanish = new TestVanish();
        vanish.prepare();
        assertFalse(vanish.present());

        vanish.installed = true;
        vanish.invisible.add(steve.uuid());

        assertTrue(vanish.isVanished(steve));
        assertEquals(VanishSupport.JOIN_SETTLE_MS, vanish.joinWaitMillis(steve, 0L));
        vanish.isVanished(steve);
        assertEquals(1, vanish.hooksMade.get(), "one hook, however many calls");
        assertEquals(1, registrations.size(), "and it is tracked against the core generation");
    }

    @Test
    @DisplayName("toggles reach every listener; one that throws does not stop the rest")
    void togglesAreDeliveredAndContained() {
        TestVanish vanish = new TestVanish();
        vanish.installed = true;
        Recorder first = new Recorder();
        Recorder last = new Recorder();
        vanish.onToggle(first);
        vanish.onToggle(new VanishListener() {
            @Override
            public void onVanishChanged(
                    PlayerHandle player, boolean vanished, boolean silent, long timestampMs) {
                throw new IllegalStateException("a broken listener");
            }
        });
        Registration lastHandle = vanish.onToggle(last);

        vanish.fire(steve, true, true);
        lastHandle.close();
        vanish.fire(steve, false, false);

        assertEquals(java.util.Arrays.asList("Steve hid silently", "Steve showed"), first.seen);
        assertEquals(Collections.singletonList("Steve hid silently"), last.seen,
                "a closed subscription hears nothing more");
    }

    @Test
    @DisplayName("a swap's teardown takes the hook off even if close() never ran")
    void generationTeardownClosesTheHook() {
        TestVanish vanish = new TestVanish();
        vanish.installed = true;
        vanish.prepare();
        assertEquals(1, vanish.hooksOpen.get());

        registrations.closeAll(logger);

        assertEquals(0, vanish.hooksOpen.get(),
                "the hook is core code on the platform; a swap must take it off (D87)");
    }

    @Test
    @DisplayName("close() takes the hook off, silences toggles, and nothing registers afterwards")
    void closeIsFinal() {
        TestVanish vanish = new TestVanish();
        vanish.installed = true;
        Recorder recorder = new Recorder();
        vanish.onToggle(recorder);
        assertEquals(1, vanish.hooksOpen.get());

        vanish.close();
        vanish.fire(steve, true, false);
        vanish.isVanished(steve);
        vanish.onToggle(new Recorder());

        assertEquals(0, vanish.hooksOpen.get());
        assertEquals(1, vanish.hooksMade.get(), "no hook after close");
        assertTrue(recorder.seen.isEmpty());
        assertEquals(0L, vanish.joinWaitMillis(steve, 0L));
    }

    @Test
    @DisplayName("an API that moved is logged once, behaves as absent, and is not retried")
    void aMovedApiFailsOpenOnce() {
        TestVanish vanish = new TestVanish();
        vanish.installed = true;
        vanish.bindFailure = new NoSuchMethodException("isInvisible");

        assertFalse(vanish.isVanished(steve));
        assertEquals(0L, vanish.joinWaitMillis(steve, 0L));
        int binds = vanish.binds.get();
        vanish.isVanished(steve);

        assertEquals(binds, vanish.binds.get(), "a class that was not there will not be there now");
        assertNotNull(vanish.brokenReason());
        String log = logger.records().toString();
        assertTrue(log.contains("PremiumVanish integration disabled"), log);
    }

    @Test
    @DisplayName("PremiumVanish's own code throwing answers 'vanished' for that call, and is not broken")
    void aThrowingCallFailsClosed() {
        TestVanish vanish = new TestVanish();
        vanish.installed = true;
        vanish.queryFailure = new InvocationTargetException(new IllegalStateException("db down"));

        assertTrue(vanish.isVanished(steve),
                "one missing join message beats publishing a hidden staff member");
        vanish.queryFailure = new IllegalStateException("not reflective at all");
        assertTrue(vanish.isVanished(steve), "anything but a moved API is one call's problem too");
        vanish.queryFailure = null;

        assertFalse(vanish.isVanished(steve), "and the next call asks again");
        assertNull(vanish.brokenReason());
    }

    @Test
    @DisplayName("a moved method at query time breaks the integration, which then answers absent")
    void aMovedMethodAtQueryTimeIsBroken() {
        TestVanish vanish = new TestVanish();
        vanish.installed = true;
        vanish.queryFailure = new NoSuchMethodError("isInvisible");

        assertFalse(vanish.isVanished(steve));
        assertNotNull(vanish.brokenReason());
        assertEquals(0L, vanish.joinWaitMillis(steve, 0L));
    }

    @Test
    @DisplayName("a non-reflective throw while looking for the plugin is retried, not broken")
    void aThrowingLookupIsRetried() {
        TestVanish vanish = new TestVanish();
        vanish.installed = true;
        vanish.bindFailure = new IllegalStateException("plugin manager busy");

        assertFalse(vanish.present());
        assertNull(vanish.brokenReason());
        vanish.bindFailure = null;

        assertTrue(vanish.present(), "the next call looks again and finds it");
    }

    @Test
    @DisplayName("a metadata value that throws answers 'vanished' for that call")
    void aThrowingConventionFailsClosed() {
        TestVanish vanish = new TestVanish();
        vanish.conventionFailure = new IllegalStateException("lazy value threw");

        assertTrue(vanish.isVanished(steve));
    }

    // ── The join wait ────────────────────────────────────────────────────────

    @Test
    @DisplayName("a backend waits the settle window from the join")
    void aBackendWaitsFromTheJoin() {
        TestVanish vanish = new TestVanish(false);
        vanish.installed = true;

        assertEquals(VanishSupport.JOIN_SETTLE_MS, vanish.joinWaitMillis(steve, 0L));
        assertEquals(500L, vanish.joinWaitMillis(steve, 1000L));
        assertEquals(0L, vanish.joinWaitMillis(steve, 2000L));
    }

    @Test
    @DisplayName("a proxy waits from the first backend connection, not from the join")
    void aProxyWaitsFromTheBackend() {
        TestVanish vanish = new TestVanish(true);
        vanish.installed = true;
        vanish.login(steve);

        assertTrue(vanish.awaitingBackend(steve),
                "no backend yet: the proxy's PremiumVanish cannot know");
        assertEquals(VanishSupport.BACKEND_RECHECK_MS, vanish.joinWaitMillis(steve, 0L));
        advanceMs(3000L);

        vanish.connect(steve);
        assertFalse(vanish.awaitingBackend(steve));
        advanceMs(500L);
        assertEquals(1000L, vanish.joinWaitMillis(steve, 3500L),
                "the settle window now runs from the backend connection");
        vanish.connect(steve);
        advanceMs(1000L);
        assertEquals(0L, vanish.joinWaitMillis(steve, 4500L),
                "a second connection (a server switch) does not extend it");
    }

    @Test
    @DisplayName("a proxy watching backends has no cap: an auth limbo of a minute is still waiting")
    void aLimboHasNoCap() {
        TestVanish vanish = new TestVanish(true);
        vanish.installed = true;
        vanish.login(steve);
        advanceMs(60_000L);

        assertTrue(vanish.awaitingBackend(steve));
        assertEquals(VanishSupport.BACKEND_RECHECK_MS, vanish.joinWaitMillis(steve, 60_000L),
                "any cap would announce a vanished staff member who happened to be slow");
    }

    @Test
    @DisplayName("a proxy whose backend hook is not registered falls back to the cap")
    void withoutTheHookTheCapApplies() {
        TestVanish vanish = new TestVanish(true);
        vanish.installed = true;
        vanish.watchBackends = false;

        assertFalse(vanish.awaitingBackend(steve));
        assertEquals(VanishSupport.PROXY_JOIN_CAP_MS, vanish.joinWaitMillis(steve, 0L));
        assertEquals(0L, vanish.joinWaitMillis(steve, VanishSupport.PROXY_JOIN_CAP_MS));
    }

    @Test
    @DisplayName("a hook that fails to register falls back to the cap too")
    void aFailedHookFallsBackToTheCap() {
        TestVanish vanish = new TestVanish(true);
        vanish.installed = true;
        vanish.hookFails = true;

        assertFalse(vanish.awaitingBackend(steve));
        assertEquals(0L, vanish.joinWaitMillis(steve, VanishSupport.PROXY_JOIN_CAP_MS));
    }

    @Test
    @DisplayName("taking the hook off (a swap) ends the uncapped wait, since nothing would see a connection")
    void teardownEndsTheUncappedWait() {
        TestVanish vanish = new TestVanish(true);
        vanish.installed = true;
        vanish.prepare();
        assertTrue(vanish.awaitingBackend(steve));

        registrations.closeAll(logger);

        assertFalse(vanish.awaitingBackend(steve));
    }

    @Test
    @DisplayName("a login starts a new session: the previous session's connection no longer counts")
    void aLoginForgetsTheLastSession() {
        TestVanish vanish = new TestVanish(true);
        vanish.installed = true;
        vanish.connect(steve);
        assertFalse(vanish.awaitingBackend(steve));

        // A quick rejoin, however soon, and however late the join reaches the bridge.
        vanish.login(steve);
        assertTrue(vanish.awaitingBackend(steve));
        advanceMs(200L);
        vanish.connect(steve);

        assertEquals(VanishSupport.JOIN_SETTLE_MS, vanish.joinWaitMillis(steve, 5000L),
                "the new session's connection is the anchor, kept rather than the old one");
    }

    @Test
    @DisplayName("a connection is not forgotten at disconnect, so a quit can still tell it happened")
    void aConnectionOutlivesTheSession() {
        TestVanish vanish = new TestVanish(true);
        vanish.installed = true;
        vanish.login(steve);
        vanish.connect(steve);
        vanish.online = false;
        advanceMs(120_000L);

        assertFalse(vanish.awaitingBackend(steve),
                "the bridge decides a quit after the disconnect event; it must still see this");
    }

    @Test
    @DisplayName("remembered connections are bounded: past the limit, offline players' are dropped")
    void rememberedConnectionsAreBounded() {
        TestVanish vanish = new TestVanish(true);
        vanish.installed = true;
        vanish.online = false;

        for (int i = 0; i <= VanishSupport.MAX_REMEMBERED_CONNECTIONS + 10; i++) {
            vanish.connect(FakePlayer.named("player" + i));
        }

        assertTrue(vanish.rememberedConnections() <= VanishSupport.MAX_REMEMBERED_CONNECTIONS,
                "remembered: " + vanish.rememberedConnections());
    }

    @Test
    @DisplayName("without PremiumVanish a proxy does not wait either")
    void aProxyWithoutPremiumVanishDoesNotWait() {
        TestVanish vanish = new TestVanish(true);

        assertEquals(0L, vanish.joinWaitMillis(steve, 0L));
    }

    @Test
    @DisplayName("disabled or reloaded: the old hook comes off and the new copy is hooked")
    void aReloadIsFollowed() {
        TestVanish vanish = new TestVanish();
        vanish.installed = true;
        vanish.prepare();
        assertEquals(1, vanish.hooksOpen.get());

        vanish.current = false;
        vanish.prepare();

        assertEquals(2, vanish.hooksMade.get());
        assertEquals(1, vanish.hooksOpen.get(), "the stale hook is off, the fresh one is on");
    }
}
