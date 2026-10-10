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
                toggles.add(player.name() + (vanished ? " hid" : " showed"));
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

        assertEquals(0L, vanish.joinSettleMillis(), "no delay without PremiumVanish");
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

        assertEquals(VanishSupport.JOIN_SETTLE_MS, vanish.joinSettleMillis(),
                "a proxy learns a player's state a moment after PostLoginEvent");
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

        assertEquals(Arrays.asList(VelocityPlayerHideEvent.class, VelocityPlayerShowEvent.class),
                new ArrayList<Class<?>>(environment.handlers.keySet()));
        environment.fire(new VelocityPlayerHideEvent(steve));
        environment.fire(new VelocityPlayerShowEvent(steve));

        assertEquals(Arrays.asList("Steve hid", "Steve showed"), toggles);
    }

    @Test
    @DisplayName("a swap's teardown removes both handlers")
    void teardownRemovesTheHandlers() {
        environment.instance = new Object() { };
        vanish();
        assertEquals(2, environment.handlers.size());

        registrations.closeAll(logger);

        assertTrue(environment.handlers.isEmpty(),
                "Velocity cannot enumerate a plugin's handlers, so the tracked teardown is all there is");
    }

    /** A proxy whose PremiumVanish instance, online players and event bus the test controls. */
    private static final class FakeEnvironment implements VelocityVanish.Environment {

        /** Any object whose class loader can see the stand-ins: the test's own. */
        Object instance;
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
        @SuppressWarnings("unchecked")
        public <E> Registration listen(final Class<E> type, EventHandler<E> handler) {
            handlers.put(type, (EventHandler<Object>) handler);
            return Registration.once(() -> handlers.remove(type));
        }
    }
}
