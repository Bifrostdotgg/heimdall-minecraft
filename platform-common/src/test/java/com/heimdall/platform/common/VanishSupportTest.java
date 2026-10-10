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

    /** A platform whose PremiumVanish the test installs, removes, breaks and reloads at will. */
    private final class TestVanish extends VanishSupport {

        volatile boolean installed;
        volatile boolean current = true;
        volatile Throwable bindFailure;
        volatile Throwable queryFailure;
        final Set<UUID> invisible = Collections.newSetFromMap(new ConcurrentHashMap<UUID, Boolean>());
        final Set<UUID> byConvention =
                Collections.newSetFromMap(new ConcurrentHashMap<UUID, Boolean>());
        final AtomicInteger binds = new AtomicInteger();
        final AtomicInteger hooksOpen = new AtomicInteger();
        final AtomicInteger hooksMade = new AtomicInteger();

        TestVanish() {
            super(VanishSupportTest.this.logger, VanishSupportTest.this.registrations);
        }

        @Override
        protected boolean vanishedByConvention(PlayerHandle player) {
            return byConvention.contains(player.uuid());
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

        assertEquals(0L, vanish.joinSettleMillis(), "no PremiumVanish must mean no delay at all");
        assertFalse(vanish.isVanished(steve));
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
        assertEquals(0L, vanish.joinSettleMillis(), "the convention never asks a join to wait");
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
        assertEquals(VanishSupport.JOIN_SETTLE_MS, vanish.joinSettleMillis());
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
        assertEquals(0L, vanish.joinSettleMillis());
    }

    @Test
    @DisplayName("an API that moved is logged once, behaves as absent, and is not retried")
    void aMovedApiFailsOpenOnce() {
        TestVanish vanish = new TestVanish();
        vanish.installed = true;
        vanish.bindFailure = new NoSuchMethodException("isInvisible");

        assertFalse(vanish.isVanished(steve));
        assertEquals(0L, vanish.joinSettleMillis());
        int binds = vanish.binds.get();
        vanish.isVanished(steve);

        assertEquals(binds, vanish.binds.get(), "a class that was not there will not be there now");
        assertNotNull(vanish.brokenReason());
        String log = logger.records().toString();
        assertTrue(log.contains("PremiumVanish integration disabled"), log);
    }

    @Test
    @DisplayName("PremiumVanish's own code throwing is one call's problem, not a broken integration")
    void aThrowingCallIsTransient() {
        TestVanish vanish = new TestVanish();
        vanish.installed = true;
        vanish.invisible.add(steve.uuid());
        vanish.queryFailure = new InvocationTargetException(new IllegalStateException("db down"));

        assertFalse(vanish.isVanished(steve), "a failed check answers 'not vanished'");
        vanish.queryFailure = null;

        assertTrue(vanish.isVanished(steve), "and the next call asks again");
        assertNull(vanish.brokenReason());
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
