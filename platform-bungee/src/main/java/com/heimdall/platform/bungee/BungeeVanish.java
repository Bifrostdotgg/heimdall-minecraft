package com.heimdall.platform.bungee;

import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.platform.PlayerHandle;
import com.heimdall.core.util.Registration;
import com.heimdall.platform.common.CoreRegistrations;
import com.heimdall.platform.common.VanishSupport;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.UUID;
import java.util.function.Function;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;

/**
 * Vanish on a BungeeCord proxy: PremiumVanish's {@code BungeeVanishAPI}, reached reflectively.
 * Departure D88.
 *
 * <p>The same shape as {@code VelocityVanish}, for the same reason: the proxy announces joins too and
 * sees them first, so a backend-only suppression would still leak through it. Queries are
 * reflective through PremiumVanish's own class loader. The event hook is the exception, and is
 * {@link BungeeVanishListener}'s alone; see there for why BungeeCord forces it.
 *
 * <p>BungeeCord has no plugin unloading and cannot tell whether a toggle was silent, so a binding
 * never goes stale and every toggle is delivered as announced.
 */
final class BungeeVanish extends VanishSupport {

    /** The name PremiumVanish registers under in its {@code bungee.yml}. */
    static final String PLUGIN_NAME = "PremiumVanish";

    static final String API_CLASS = "de.myzelyam.api.vanish.BungeeVanishAPI";
    static final String HIDE_EVENT = "de.myzelyam.api.vanish.BungeePlayerHideEvent";
    static final String SHOW_EVENT = "de.myzelyam.api.vanish.BungeePlayerShowEvent";

    /** What this class needs from the proxy, as a seam for the tests. */
    interface Environment {

        /** PremiumVanish, or {@code null} when it is not loaded. */
        Plugin findVanishPlugin();

        /** The class loader the plugin's own classes live in. */
        ClassLoader loaderOf(Plugin plugin);

        /** The player with this UUID if they are still on the proxy, else {@code null}. */
        ProxiedPlayer onlinePlayer(UUID uuid);

        /** Registers {@code listener}'s handlers. */
        void register(Listener listener);

        /** Unregisters exactly {@code listener}. */
        void unregister(Listener listener);
    }

    private final Environment environment;
    private final Function<ProxiedPlayer, PlayerHandle> handles;

    BungeeVanish(
            HeimdallLogger logger,
            CoreRegistrations registrations,
            Environment environment,
            Function<ProxiedPlayer, PlayerHandle> handles) {
        super(logger, registrations);
        this.environment = environment;
        this.handles = handles;
    }

    /** The production wiring: the proxy's plugin manager, with listeners owned by {@code plugin}. */
    static BungeeVanish create(
            HeimdallLogger logger,
            CoreRegistrations registrations,
            Plugin plugin,
            ProxyServer proxy,
            final BungeeText text) {
        return new BungeeVanish(logger, registrations, new ProxyEnvironment(plugin, proxy),
                new Function<ProxiedPlayer, PlayerHandle>() {
                    @Override
                    public PlayerHandle apply(ProxiedPlayer player) {
                        return new BungeePlayerHandle(player, text);
                    }
                });
    }

    @Override
    protected Binding bind() throws Throwable {
        Plugin plugin = environment.findVanishPlugin();
        if (plugin == null) {
            return null;
        }
        final ClassLoader loader = environment.loaderOf(plugin);
        if (loader == null) {
            throw new ClassNotFoundException(PLUGIN_NAME + " has no class loader");
        }
        Class<?> api = Class.forName(API_CLASS, true, loader);
        final Method isInvisible = api.getMethod("isInvisible", ProxiedPlayer.class);
        final Method allInvisible = optionalMethod(api, "getAllInvisiblePlayers");
        // Resolved here only to fail the bind, rather than the hook, when the events have moved.
        Class.forName(HIDE_EVENT, false, loader);
        Class.forName(SHOW_EVENT, false, loader);

        return new Binding() {
            @Override
            public boolean current() {
                return true;
            }

            @Override
            public boolean vanished(PlayerHandle player) throws Throwable {
                ProxiedPlayer online = environment.onlinePlayer(player.uuid());
                if (online != null) {
                    return Boolean.TRUE.equals(isInvisible.invoke(null, online));
                }
                if (allInvisible == null) {
                    return false;
                }
                Object all = allInvisible.invoke(null);
                return all instanceof Collection && ((Collection<?>) all).contains(player.uuid());
            }

            @Override
            public Registration hook() throws Throwable {
                // The first time BungeeVanishListener is touched, and only now that PremiumVanish is
                // known to be here.
                if (!BungeeVanishListener.linksTo(loader)) {
                    throw new IllegalStateException("PremiumVanish's event classes resolve to a "
                            + "copy rather than PremiumVanish's own; another plugin has probably "
                            + "shaded its API");
                }
                final BungeeVanishListener listener = new BungeeVanishListener(BungeeVanish.this);
                environment.register(listener);
                return Registration.once(new Runnable() {
                    @Override
                    public void run() {
                        environment.unregister(listener);
                    }
                });
            }
        };
    }

    /** One hide or show, from {@link BungeeVanishListener}. Never throws into BungeeCord's bus. */
    void onEvent(ProxiedPlayer player, boolean vanished) {
        try {
            if (player != null) {
                toggled(handles.apply(player), vanished, false);
            }
        } catch (Throwable failed) {
            logger.error("could not read a PremiumVanish vanish event; that one change was not seen",
                    failed);
        }
    }

    private static Method optionalMethod(Class<?> owner, String name) {
        try {
            return owner.getMethod(name);
        } catch (NoSuchMethodException absent) {
            return null;
        }
    }

    /** The real proxy. */
    private static final class ProxyEnvironment implements Environment {

        private final Plugin plugin;
        private final ProxyServer proxy;

        ProxyEnvironment(Plugin plugin, ProxyServer proxy) {
            this.plugin = plugin;
            this.proxy = proxy;
        }

        @Override
        public Plugin findVanishPlugin() {
            return proxy.getPluginManager().getPlugin(PLUGIN_NAME);
        }

        @Override
        public ClassLoader loaderOf(Plugin vanish) {
            return vanish.getClass().getClassLoader();
        }

        @Override
        public ProxiedPlayer onlinePlayer(UUID uuid) {
            return proxy.getPlayer(uuid);
        }

        @Override
        public void register(Listener listener) {
            proxy.getPluginManager().registerListener(plugin, listener);
        }

        @Override
        public void unregister(Listener listener) {
            proxy.getPluginManager().unregisterListener(listener);
        }
    }
}
