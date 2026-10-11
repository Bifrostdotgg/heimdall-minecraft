package com.heimdall.platform.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.heimdall.core.log.RecordingLogger;
import com.heimdall.core.platform.PlayerHandle;
import com.heimdall.core.platform.VanishListener;
import com.heimdall.core.util.Registration;
import com.heimdall.platform.common.CoreRegistrations;
import com.heimdall.platform.common.VanishSupport;
import com.velocitypowered.api.event.EventHandler;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import de.myzelyam.api.vanish.VelocityPlayerHideEvent;
import de.myzelyam.api.vanish.VelocityPlayerShowEvent;
import de.myzelyam.api.vanish.VelocityVanishAPI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link VelocityVanish} against stand-ins for PremiumVanish's Velocity classes (test sources),
 * resolved through the same reflective code production runs. Departure D88.
 */
class VelocityVanishTest {

    private final RecordingLogger logger = new RecordingLogger(true);
    private final CoreRegistrations registrations = CoreRegistrations.untracked();
    private final FakeEnvironment environment = new FakeEnvironment();
    private final VelocityText text = new VelocityText(logger);
    private final List<String> toggles = new ArrayList<String>();

    @AfterEach
    void reset() {
        VelocityVanishAPI.INVISIBLE.clear();
    }

    private VelocityVanish vanish() {
        VelocityVanish vanish = new VelocityVanish(logger, registrations, environment,
                player -> new VelocityPlayerHandle(player, text));
        vanish.onToggle(new VanishListener() {
            @Override
            public void onVanishChanged(
                    PlayerHandle player, boolean vanished, boolean silent, long timestampMs) {
                toggles.add(player.name() + (vanished ? " hid" : " showed")
                        + (silent ? " silently" : ""));
            }
        });
        return vanish;
    }

    private static Player player(String name) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.nameUUIDFromBytes(name.getBytes()));
        when(player.getUsername()).thenReturn(name);
        return player;
    }

    @Test
    @DisplayName("no PremiumVanish: no settle window, nothing registered, nobody vanished")
    void absentIsTheOldBehaviour() {
        VelocityVanish vanish = vanish();
        Player steve = player("Steve");
        environment.online.put(steve.getUniqueId(), steve);

        assertEquals(0L, vanish.joinWaitMillis(new VelocityPlayerHandle(player("Steve"), text), 0L), "no delay without PremiumVanish");
        assertFalse(vanish.isVanished(new VelocityPlayerHandle(steve, text)));
        assertTrue(environment.handlers.isEmpty());
    }

    @Test
    @DisplayName("PremiumVanish: asks joins to wait, answers online, then offline after the quit")
    void presentAnswersOnlineAndGone() {
        environment.instance = new Object() { };
        VelocityVanish vanish = vanish();
        Player steve = player("Steve");
        VelocityVanishAPI.INVISIBLE.add(steve.getUniqueId());
        environment.online.put(steve.getUniqueId(), steve);

        assertEquals(VanishSupport.PROXY_JOIN_CAP_MS,
                vanish.joinWaitMillis(new VelocityPlayerHandle(steve, text), 0L),
                "no backend yet: a proxy waits up to the cap for one");
        assertTrue(vanish.isVanished(new VelocityPlayerHandle(steve, text)));

        environment.online.clear();
        assertTrue(vanish.isVanished(new VelocityPlayerHandle(steve, text)),
                "after the quit, getAllInvisiblePlayers still knows");
        assertFalse(vanish.isVanished(new VelocityPlayerHandle(player("Alex"), text)));
    }

    @Test
    @DisplayName("hide and show events are registered by class and delivered")
    void hooksTheEvents() {
        environment.instance = new Object() { };
        vanish();
        Player steve = player("Steve");

        assertEquals(Arrays.asList(ServerPostConnectEvent.class, VelocityPlayerHideEvent.class,
                VelocityPlayerShowEvent.class), new ArrayList<Class<?>>(environment.handlers.keySet()));
        environment.fire(new VelocityPlayerHideEvent(steve));
        environment.fire(new VelocityPlayerShowEvent(steve));

        assertEquals(Arrays.asList("Steve hid silently", "Steve showed silently"), toggles,
                "a proxy cannot tell an announced toggle from a silent one, so it never announces");
    }

    @Test
    @DisplayName("a swap's teardown removes both handlers")
    void teardownRemovesTheHandlers() {
        environment.instance = new Object() { };
        vanish();

        assertEquals(3, environment.handlers.size());
        registrations.closeAll(logger);

        assertTrue(environment.handlers.isEmpty(),
                "Velocity cannot enumerate a plugin's handlers, so the tracked teardown is all there is");
    }

    @Test
    @DisplayName("the join wait runs from the first backend connection")
    void theWaitIsAnchoredToTheBackend() {
        environment.instance = new Object() { };
        VelocityVanish vanish = vanish();
        Player steve = player("Steve");
        VelocityPlayerHandle handle = new VelocityPlayerHandle(steve, text);
        assertEquals(VanishSupport.PROXY_JOIN_CAP_MS, vanish.joinWaitMillis(handle, 0L));

        environment.fire(new ServerPostConnectEvent(steve, null));
        // The join was a second ago; the backend connection is now.
        long wait = vanish.joinWaitMillis(handle, 1000L);

        assertTrue(wait > 1000L && wait <= VanishSupport.JOIN_SETTLE_MS,
                "the settle window starts at the backend connection: " + wait);
    }

    @Test
    @DisplayName("a PremiumVanish from before the Velocity events still keeps vanished joins out")
    void missingEventsCostOnlyTheToggles() {
        // VelocityVanishAPI shipped in 2.9.0 (November 2023), the events only in March 2025.
        environment.instance = new Object() { };
        environment.hidden = Arrays.asList(VelocityVanish.HIDE_EVENT, VelocityVanish.SHOW_EVENT);
        VelocityVanish vanish = vanish();
        Player steve = player("Steve");
        environment.online.put(steve.getUniqueId(), steve);
        VelocityVanishAPI.INVISIBLE.add(steve.getUniqueId());

        assertTrue(vanish.isVanished(new VelocityPlayerHandle(steve, text)),
                "breaking the whole integration here would relay every vanished join again");
        assertEquals(java.util.Collections.<Class<?>>singletonList(ServerPostConnectEvent.class),
                new ArrayList<Class<?>>(environment.handlers.keySet()),
                "the backend anchor is still registered; the toggles cannot be");
        assertTrue(vanish.brokenReason() == null);
        assertTrue(logger.records().toString().contains("not seen as it happens"),
                logger.records().toString());
    }

    @Test
    @DisplayName("PremiumVanish loading after Heimdall: prepare() misses, the first use finds it")
    void aLateLoadIsFoundOnFirstUse() {
        VelocityVanish vanish = vanish();
        vanish.prepare();
        assertFalse(vanish.present());

        environment.instance = new Object() { };

        assertTrue(vanish.present());
        assertTrue(vanish.brokenReason() == null);
    }

    /** A proxy whose PremiumVanish instance, online players and event bus the test controls. */
    private static final class FakeEnvironment implements VelocityVanish.Environment {

        /** Any object whose class loader can see the stand-ins: the test's own. */
        Object instance;
        List<String> hidden = java.util.Collections.emptyList();
        final Map<UUID, Player> online = new HashMap<UUID, Player>();
        final Map<Class<?>, EventHandler<Object>> handlers =
                new LinkedHashMap<Class<?>, EventHandler<Object>>();

        @SuppressWarnings("unchecked")
        void fire(Object event) {
            EventHandler<Object> handler = handlers.get(event.getClass());
            if (handler != null) {
                handler.execute(event);
            }
        }

        @Override
        public Object findVanishPlugin() {
            return instance;
        }

        @Override
        public Optional<Player> onlinePlayer(UUID uuid) {
            return Optional.ofNullable(online.get(uuid));
        }

        @Override
        public ClassLoader loaderOf(Object plugin) {
            // PremiumVanish's own loader, with the names a test chose to remove made unfindable.
            return new ClassLoader(plugin.getClass().getClassLoader()) {
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
        @SuppressWarnings("unchecked")
        public <E> Registration listen(final Class<E> type, EventHandler<E> handler) {
            handlers.put(type, (EventHandler<Object>) handler);
            return Registration.once(() -> handlers.remove(type));
        }
    }
}
