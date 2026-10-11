package com.heimdall.platform.bukkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.heimdall.core.log.RecordingLogger;
import com.heimdall.core.platform.PlayerHandle;
import com.heimdall.core.platform.VanishListener;
import com.heimdall.platform.common.CoreRegistrations;
import com.heimdall.platform.common.VanishSupport;
import de.myzelyam.api.vanish.PlayerAutoVanishOnJoinEvent;
import de.myzelyam.api.vanish.PlayerHideEvent;
import de.myzelyam.api.vanish.PostPlayerHideEvent;
import de.myzelyam.api.vanish.PostPlayerShowEvent;
import de.myzelyam.api.vanish.VanishAPI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.Listener;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link BukkitVanish} against stand-ins for PremiumVanish's real classes (test sources,
 * {@code de.myzelyam.api.vanish}), resolved through the same reflective code production runs.
 * Departure D88.
 */
class BukkitVanishTest {

    private final RecordingLogger logger = new RecordingLogger(true);
    private final CoreRegistrations registrations = CoreRegistrations.untracked();
    private final FakeEnvironment environment = new FakeEnvironment();
    private final List<String> toggles = new ArrayList<String>();

    @AfterEach
    void reset() {
        VanishAPI.INVISIBLE.clear();
        VanishAPI.offlineChecks = 0;
    }

    private BukkitVanish vanish() {
        return new BukkitVanish(logger, registrations, environment,
                new Function<Player, PlayerHandle>() {
                    @Override
                    public PlayerHandle apply(Player player) {
                        return handle(player);
                    }
                });
    }

    private static PlayerHandle handle(Player player) {
        return new BukkitPlayerHandle(player, InlineScheduler.INSTANCE, null);
    }

    private static Player player(String name, boolean online, Boolean vanishedMetadata) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.nameUUIDFromBytes(name.getBytes()));
        when(player.getName()).thenReturn(name);
        when(player.isOnline()).thenReturn(online);
        List<MetadataValue> values = new ArrayList<MetadataValue>();
        if (vanishedMetadata != null) {
            MetadataValue value = mock(MetadataValue.class);
            when(value.asBoolean()).thenReturn(vanishedMetadata);
            values.add(value);
        }
        when(player.getMetadata(BukkitVanish.METADATA_KEY)).thenReturn(values);
        return player;
    }

    private void record(BukkitVanish vanish) {
        vanish.onToggle(new VanishListener() {
            @Override
            public void onVanishChanged(
                    PlayerHandle player, boolean vanished, boolean silent, long timestampMs) {
                toggles.add(player.name() + (vanished ? " hid" : " showed")
                        + (silent ? " silently" : ""));
            }
        });
    }

    // ── Without PremiumVanish ────────────────────────────────────────────────

    @Test
    @DisplayName("no PremiumVanish: no settle window, nothing registered, not vanished")
    void absentIsTheOldBehaviour() {
        BukkitVanish vanish = vanish();
        record(vanish);

        assertEquals(0L, vanish.joinWaitMillis(handle(player("Steve", true, null)), 0L), "no delay without PremiumVanish");
        assertFalse(vanish.isVanished(handle(player("Steve", true, null))));
        assertTrue(environment.registered.isEmpty(), "nothing hooked on a server without it");
        assertNull(vanish.brokenReason());
    }

    @Test
    @DisplayName("the vanished metadata alone suppresses, with no plugin found and no delay")
    void metadataIsASecondSource() {
        BukkitVanish vanish = vanish();

        assertTrue(vanish.isVanished(handle(player("Steve", true, true))),
                "SuperVanish, EssentialsX, CMI and VanishNoPacket set it");
        assertFalse(vanish.isVanished(handle(player("Alex", true, false))),
                "a false value is not vanished");
        assertEquals(0L, vanish.joinWaitMillis(handle(player("Steve", true, null)), 0L), "the metadata convention never asks a join to wait");
    }

    @Test
    @DisplayName("the metadata is read from the handle's own Player, so it still answers at a quit")
    void metadataAnswersAfterTheQuit() {
        BukkitVanish vanish = vanish();

        assertTrue(vanish.isVanished(handle(player("Steve", false, true))));
    }

    @Test
    @DisplayName("an installed but disabled PremiumVanish is treated as absent")
    void aDisabledPluginIsAbsent() {
        environment.install(false);
        BukkitVanish vanish = vanish();

        assertEquals(0L, vanish.joinWaitMillis(handle(player("Steve", true, null)), 0L));
        assertTrue(environment.registered.isEmpty());
    }

    // ── With PremiumVanish ───────────────────────────────────────────────────

    @Test
    @DisplayName("PremiumVanish: asks joins to wait, and answers for online players")
    void presentAnswersOnline() {
        environment.install(true);
        BukkitVanish vanish = vanish();
        Player steve = player("Steve", true, null);
        VanishAPI.INVISIBLE.add(steve.getUniqueId());

        assertEquals(VanishSupport.JOIN_SETTLE_MS, vanish.joinWaitMillis(handle(player("Steve", true, null)), 0L));
        assertTrue(vanish.isVanished(handle(steve)));
        assertFalse(vanish.isVanished(handle(player("Alex", true, null))));
        assertEquals(0, VanishAPI.offlineChecks, "an online player needs no offline lookup");
    }

    @Test
    @DisplayName("a player who has left is asked about with isInvisibleOffline")
    void goneUsesTheOfflineLookup() {
        environment.install(true);
        BukkitVanish vanish = vanish();
        Player steve = player("Steve", false, null);
        VanishAPI.INVISIBLE.add(steve.getUniqueId());

        assertTrue(vanish.isVanished(handle(steve)));
        assertEquals(1, VanishAPI.offlineChecks,
                "PremiumVanish documents isInvisible as unreliable offline on MySQL");
    }

    @Test
    @DisplayName("hooks the post-change events and auto-vanish at MONITOR, and reads silence")
    void hooksTheEvents() {
        environment.install(true);
        BukkitVanish vanish = vanish();
        record(vanish);
        Player steve = player("Steve", true, null);

        assertEquals(Arrays.<Class<?>>asList(PostPlayerHideEvent.class, PostPlayerShowEvent.class,
                PlayerAutoVanishOnJoinEvent.class), new ArrayList<Class<?>>(
                        environment.registered.keySet()));
        environment.fire(new PostPlayerHideEvent(steve, false));
        environment.fire(new PostPlayerShowEvent(steve, true));
        environment.fire(new PlayerAutoVanishOnJoinEvent(steve));

        assertEquals(Arrays.asList("Steve hid", "Steve showed silently", "Steve hid silently"),
                toggles, "auto-vanish is part of a join, so it is delivered as silent");
    }

    @Test
    @DisplayName("an older PremiumVanish without the post-change events falls back to the pre-change ones")
    void fallsBackToThePreChangeEvents() {
        environment.install(true);
        environment.hidden = Arrays.asList(BukkitVanish.POST_HIDE_EVENT, BukkitVanish.POST_SHOW_EVENT);
        BukkitVanish vanish = vanish();
        record(vanish);

        assertTrue(environment.registered.containsKey(PlayerHideEvent.class),
                "registered: " + environment.registered.keySet());
        environment.fire(new PlayerHideEvent(player("Steve", true, null), true));

        assertEquals(Collections.singletonList("Steve hid silently"), toggles);
    }

    @Test
    @DisplayName("a PremiumVanish with no events at all still answers, and only the toggles are lost")
    void missingEventsCostOnlyTheToggles() {
        environment.install(true);
        environment.hidden = Arrays.asList(BukkitVanish.POST_HIDE_EVENT, BukkitVanish.POST_SHOW_EVENT,
                BukkitVanish.HIDE_EVENT, BukkitVanish.SHOW_EVENT, BukkitVanish.AUTO_VANISH_EVENT);
        BukkitVanish vanish = vanish();
        record(vanish);
        Player steve = player("Steve", true, null);
        VanishAPI.INVISIBLE.add(steve.getUniqueId());

        assertTrue(vanish.isVanished(handle(steve)),
                "the query API is what keeps vanished joins out; it must survive missing events");
        assertEquals(VanishSupport.JOIN_SETTLE_MS, vanish.joinWaitMillis(handle(steve), 0L));
        assertNull(vanish.brokenReason());
        assertTrue(environment.registered.isEmpty());
        assertTrue(logger.records().toString().contains("not seen as it happens"),
                "one warning saying what is lost: " + logger.records());
    }

    @Test
    @DisplayName("a reloaded PremiumVanish is followed: the old hook comes off, the new copy is hooked")
    void aReloadIsFollowed() {
        environment.install(true);
        Plugin first = environment.plugin;
        BukkitVanish vanish = vanish();
        record(vanish);
        Listener firstListener = environment.listeners.get(0);

        when(first.isEnabled()).thenReturn(false);
        environment.install(true);
        vanish.isVanished(handle(player("Steve", true, null)));

        assertEquals(1, environment.listeners.size(), "exactly one live hook");
        assertTrue(environment.listeners.get(0) != firstListener,
                "the old copy's hook is off and the new copy's is on");
    }

    @Test
    @DisplayName("a metadata value that throws answers 'vanished' for that call")
    void aThrowingMetadataValueFailsClosed() {
        Player steve = player("Steve", true, null);
        MetadataValue broken = mock(MetadataValue.class);
        when(broken.asBoolean()).thenThrow(new IllegalStateException("lazy value threw"));
        when(steve.getMetadata(BukkitVanish.METADATA_KEY))
                .thenReturn(Collections.singletonList(broken));

        assertTrue(vanish().isVanished(handle(steve)));
    }

    @Test
    @DisplayName("closing (a swap or a disable) unregisters the hook and stops toggles")
    void closeUnregisters() {
        environment.install(true);
        BukkitVanish vanish = vanish();
        record(vanish);
        assertEquals(1, environment.listeners.size());

        registrations.closeAll(logger);

        assertTrue(environment.listeners.isEmpty(), "the core generation's teardown takes it off");
        vanish.close();
        environment.fire(new PostPlayerHideEvent(player("Steve", true, null), false));
        assertTrue(toggles.isEmpty());
    }

    @Test
    @DisplayName("an API that moved is reported once and behaves as absent")
    void aMovedApiIsAbsent() {
        environment.install(true);
        environment.hidden = Collections.singletonList(BukkitVanish.API_CLASS);
        BukkitVanish vanish = vanish();

        assertEquals(0L, vanish.joinWaitMillis(handle(player("Steve", true, null)), 0L));
        assertTrue(vanish.brokenReason() != null);
        assertTrue(environment.registered.isEmpty());
    }

    // ── The server, faked ────────────────────────────────────────────────────

    /** A plugin manager that records registrations, with PremiumVanish's loader steerable. */
    private static final class FakeEnvironment implements BukkitVanish.Environment {

        Plugin plugin;
        List<String> hidden = Collections.emptyList();
        final Map<Class<? extends Event>, EventExecutor> registered =
                new LinkedHashMap<Class<? extends Event>, EventExecutor>();
        final List<Listener> listeners = new ArrayList<Listener>();
        private Listener registeredWith;

        void install(boolean enabled) {
            plugin = mock(Plugin.class);
            when(plugin.isEnabled()).thenReturn(enabled);
            when(plugin.getName()).thenReturn("PremiumVanish");
        }

        void fire(Event event) {
            EventExecutor executor = registered.get(event.getClass());
            if (executor != null && listeners.contains(registeredWith)) {
                try {
                    executor.execute(registeredWith, event);
                } catch (Exception failed) {
                    throw new AssertionError(failed);
                }
            }
        }

        @Override
        public Plugin findVanishPlugin() {
            return plugin;
        }

        @Override
        public ClassLoader loaderOf(Plugin vanish) {
            // PremiumVanish's own loader, with the names a test chose to remove made unfindable.
            return new ClassLoader(BukkitVanishTest.class.getClassLoader()) {
                @Override
                protected Class<?> loadClass(String name, boolean resolve)
                        throws ClassNotFoundException {
                    if (hidden.contains(name)) {
                        throw new ClassNotFoundException(name);
                    }
                    return super.loadClass(name, resolve);
                }
            };
        }

        @Override
        public void register(Class<? extends Event> type, Listener listener, EventExecutor executor) {
            registered.put(type, executor);
            registeredWith = listener;
            if (!listeners.contains(listener)) {
                listeners.add(listener);
            }
        }

        @Override
        public void unregister(Listener listener) {
            listeners.remove(listener);
        }
    }
}
