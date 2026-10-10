package com.heimdall.platform.bungee;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.heimdall.core.log.RecordingLogger;
import com.heimdall.core.platform.PlayerHandle;
import com.heimdall.core.platform.VanishListener;
import com.heimdall.platform.common.CoreRegistrations;
import com.heimdall.platform.common.VanishSupport;
import de.myzelyam.api.vanish.BungeePlayerHideEvent;
import de.myzelyam.api.vanish.BungeePlayerShowEvent;
import de.myzelyam.api.vanish.BungeeVanishAPI;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.event.PostLoginEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.event.EventBus;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.event.EventPriority;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link BungeeVanish} against a stand-in for PremiumVanish's {@code BungeeVanishAPI} and the
 * {@code vanishApi} event stand-ins, with toggles dispatched through BungeeCord's <strong>real</strong>
 * {@link EventBus}: the typed listener exists only because that bus dispatches on a handler's
 * declared parameter type, so a fake bus would prove nothing about it. Departure D88.
 */
class BungeeVanishTest {

    private final RecordingLogger logger = new RecordingLogger(true);
    private final CoreRegistrations registrations = CoreRegistrations.untracked();
    private final FakeEnvironment environment = new FakeEnvironment();
    private final BungeeText text = new BungeeText();
    private final List<String> toggles = new ArrayList<String>();

    @AfterEach
    void reset() {
        BungeeVanishAPI.INVISIBLE.clear();
    }

    private BungeeVanish vanish() {
        BungeeVanish vanish = new BungeeVanish(logger, registrations, environment,
                new Function<ProxiedPlayer, PlayerHandle>() {
                    @Override
                    public PlayerHandle apply(ProxiedPlayer player) {
                        return new BungeePlayerHandle(player, text);
                    }
                });
        vanish.onToggle(new VanishListener() {
            @Override
            public void onVanishChanged(
                    PlayerHandle player, boolean vanished, boolean silent, long timestampMs) {
                toggles.add(player.name() + (vanished ? " hid" : " showed"));
            }
        });
        return vanish;
    }

    private static ProxiedPlayer player(String name) {
        ProxiedPlayer player = mock(ProxiedPlayer.class);
        when(player.getUniqueId()).thenReturn(UUID.nameUUIDFromBytes(name.getBytes()));
        when(player.getName()).thenReturn(name);
        return player;
    }

    @Test
    @DisplayName("no PremiumVanish: no settle window, no listener, nobody vanished")
    void absentIsTheOldBehaviour() {
        BungeeVanish vanish = vanish();
        ProxiedPlayer steve = player("Steve");
        environment.online.put(steve.getUniqueId(), steve);

        assertEquals(0L, vanish.joinSettleMillis(), "no delay without PremiumVanish");
        assertFalse(vanish.isVanished(new BungeePlayerHandle(steve, text)));
        assertTrue(environment.listeners.isEmpty(),
                "BungeeVanishListener is never even loaded on a proxy without PremiumVanish");
    }

    @Test
    @DisplayName("PremiumVanish: asks joins to wait, answers online, then offline after the quit")
    void presentAnswersOnlineAndGone() {
        environment.install();
        BungeeVanish vanish = vanish();
        ProxiedPlayer steve = player("Steve");
        BungeeVanishAPI.INVISIBLE.add(steve.getUniqueId());
        environment.online.put(steve.getUniqueId(), steve);

        assertEquals(VanishSupport.JOIN_SETTLE_MS, vanish.joinSettleMillis());
        assertTrue(vanish.isVanished(new BungeePlayerHandle(steve, text)));

        environment.online.clear();
        assertTrue(vanish.isVanished(new BungeePlayerHandle(steve, text)),
                "after the quit, getAllInvisiblePlayers still knows");
        assertFalse(vanish.isVanished(new BungeePlayerHandle(player("Alex"), text)));
    }

    @Test
    @DisplayName("hide and show reach the listener through BungeeCord's real event bus")
    void theRealBusDispatchesToTheTypedListener() {
        environment.install();
        vanish();
        ProxiedPlayer steve = player("Steve");

        assertEquals(1, environment.listeners.size());
        environment.bus.post(new BungeePlayerHideEvent(steve));
        environment.bus.post(new BungeePlayerShowEvent(steve));

        assertEquals(Arrays.asList("Steve hid", "Steve showed"), toggles);
    }

    @Test
    @DisplayName("a swap's teardown unregisters the listener from the bus")
    void teardownUnregisters() {
        environment.install();
        vanish();

        registrations.closeAll(logger);
        environment.bus.post(new BungeePlayerHideEvent(player("Steve")));

        assertTrue(environment.listeners.isEmpty());
        assertTrue(toggles.isEmpty());
    }

    @Test
    @DisplayName("the session listener runs last, so a vanish plugin has marked the player first")
    void sessionListenerRunsLast() throws NoSuchMethodException {
        for (Method method : Arrays.asList(
                BungeeSessionListener.class.getMethod("onPostLogin", PostLoginEvent.class),
                BungeeSessionListener.class.getMethod("onDisconnect", PlayerDisconnectEvent.class))) {
            assertEquals(EventPriority.HIGHEST,
                    method.getAnnotation(EventHandler.class).priority(), method.getName());
        }
    }

    /** A proxy with a real event bus, whose PremiumVanish and online players the test controls. */
    private static final class FakeEnvironment implements BungeeVanish.Environment {

        final EventBus bus = new EventBus();
        final Map<UUID, ProxiedPlayer> online = new HashMap<UUID, ProxiedPlayer>();
        final List<Listener> listeners = new ArrayList<Listener>();
        Plugin plugin;

        void install() {
            plugin = mock(Plugin.class);
        }

        @Override
        public Plugin findVanishPlugin() {
            return plugin;
        }

        @Override
        public ClassLoader loaderOf(Plugin vanish) {
            // PremiumVanish's own loader: in this test, the one that has the stand-ins.
            return BungeeVanishTest.class.getClassLoader();
        }

        @Override
        public ProxiedPlayer onlinePlayer(UUID uuid) {
            return online.get(uuid);
        }

        @Override
        public void register(Listener listener) {
            bus.register(listener);
            listeners.add(listener);
        }

        @Override
        public void unregister(Listener listener) {
            bus.unregister(listener);
            listeners.remove(listener);
        }
    }
}
