package com.heimdall.platform.velocity;

import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.platform.PlayerHandle;
import com.heimdall.core.util.Registration;
import com.heimdall.platform.common.CoreRegistrations;
import com.heimdall.platform.common.VanishSupport;
import com.velocitypowered.api.event.EventHandler;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Vanish on a Velocity proxy: PremiumVanish's {@code VelocityVanishAPI}, reached reflectively.
 * Departure D88.
 *
 * <h2>Why a proxy has to ask at all</h2>
 *
 * <p>The proxy announces joins too ({@code relayEvents} defaults on for every role, D83), and it sees
 * the join before any backend does. The bot keeps whichever copy of an event arrives first, so a
 * backend that suppressed a vanished join would still leak it through the proxy. So the proxy asks
 * PremiumVanish's proxy half the same question, and waits a moment for the answer (see
 * {@link VanishSupport#JOIN_SETTLE_MS}): it learns a player's state from a backend or the shared
 * database, which can be a little after {@code PostLoginEvent}.
 *
 * <h2>Online and gone</h2>
 *
 * <p>{@code isInvisible(Player)} while the player is still on the proxy, and
 * {@code getAllInvisiblePlayers()} (online and offline) once they have left, because a quit is
 * reported after the fact and PremiumVanish may have dropped them from its online set by then.
 *
 * <h2>Events</h2>
 *
 * <p>{@code VelocityPlayerHideEvent} and {@code VelocityPlayerShowEvent}, registered by class as
 * functional handlers at {@link PostOrder#LAST} through {@link VelocityEvents}, which is the only way
 * to register on Velocity without its method-handle cache pinning a swapped-out core (D87). Velocity
 * cannot tell whether a toggle was silent, so every one is delivered as announced.
 *
 * <p>Velocity has no plugin unloading, so a binding never goes stale here.
 */
final class VelocityVanish extends VanishSupport {

    /** PremiumVanish's plugin id on Velocity, matched case-insensitively against id and name. */
    static final String PLUGIN_ID = "premiumvanish";

    static final String API_CLASS = "de.myzelyam.api.vanish.VelocityVanishAPI";
    static final String HIDE_EVENT = "de.myzelyam.api.vanish.VelocityPlayerHideEvent";
    static final String SHOW_EVENT = "de.myzelyam.api.vanish.VelocityPlayerShowEvent";

    /** What this class needs from the proxy, as a seam for the tests. */
    interface Environment {

        /** PremiumVanish's plugin instance, or {@code null} when it is not loaded. */
        Object findVanishPlugin();

        /** The player with this UUID if they are still on the proxy. */
        Optional<Player> onlinePlayer(UUID uuid);

        /** Registers {@code handler} for {@code type} at {@link PostOrder#LAST}. */
        <E> Registration listen(Class<E> type, EventHandler<E> handler);
    }

    private final Environment environment;
    private final Function<Player, PlayerHandle> handles;

    VelocityVanish(
            HeimdallLogger logger,
            CoreRegistrations registrations,
            Environment environment,
            Function<Player, PlayerHandle> handles) {
        super(logger, registrations);
        this.environment = environment;
        this.handles = handles;
    }

    /** The production wiring: the proxy's plugin and event managers, owned by {@code plugin}. */
    static VelocityVanish create(
            HeimdallLogger logger,
            CoreRegistrations registrations,
            Object plugin,
            ProxyServer proxy,
            final VelocityText text) {
        return new VelocityVanish(logger, registrations, new ProxyEnvironment(plugin, proxy),
                new Function<Player, PlayerHandle>() {
                    @Override
                    public PlayerHandle apply(Player player) {
                        return new VelocityPlayerHandle(player, text);
                    }
                });
    }

    @Override
    protected Binding bind() throws Throwable {
        Object instance = environment.findVanishPlugin();
        if (instance == null) {
            return null;
        }
        ClassLoader loader = instance.getClass().getClassLoader();
        if (loader == null) {
            throw new ClassNotFoundException("PremiumVanish has no class loader");
        }
        Class<?> api = Class.forName(API_CLASS, true, loader);
        final Method isInvisible = api.getMethod("isInvisible", Player.class);
        final Method allInvisible = optionalMethod(api, "getAllInvisiblePlayers");
        final Class<?> hide = Class.forName(HIDE_EVENT, true, loader);
        final Class<?> show = Class.forName(SHOW_EVENT, true, loader);
        final Method hidePlayer = hide.getMethod("getPlayer");
        final Method showPlayer = show.getMethod("getPlayer");

        return new Binding() {
            @Override
            public boolean current() {
                return true;
            }

            @Override
            public boolean vanished(PlayerHandle player) throws Throwable {
                Optional<Player> online = environment.onlinePlayer(player.uuid());
                if (online.isPresent()) {
                    return Boolean.TRUE.equals(isInvisible.invoke(null, online.get()));
                }
                if (allInvisible == null) {
                    return false;
                }
                Object all = allInvisible.invoke(null);
                return all instanceof Collection && ((Collection<?>) all).contains(player.uuid());
            }

            @Override
            public Registration hook() {
                final Registration hides = listen(hide, hidePlayer, true);
                final Registration shows;
                try {
                    shows = listen(show, showPlayer, false);
                } catch (RuntimeException | Error failed) {
                    hides.close();
                    throw failed;
                }
                return Registration.once(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            shows.close();
                        } finally {
                            hides.close();
                        }
                    }
                });
            }
        };
    }

    @SuppressWarnings("unchecked")
    private Registration listen(Class<?> type, final Method getPlayer, final boolean vanished) {
        return environment.listen((Class<Object>) type, new EventHandler<Object>() {
            @Override
            public void execute(Object event) {
                onEvent(getPlayer, event, vanished);
            }
        });
    }

    /** One hide or show. Contained as {@link Throwable}: an escaped error lands in Velocity's bus. */
    private void onEvent(Method getPlayer, Object event, boolean vanished) {
        try {
            Object player = getPlayer.invoke(event);
            if (player instanceof Player) {
                toggled(handles.apply((Player) player), vanished, false);
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

        private final Object plugin;
        private final ProxyServer proxy;

        ProxyEnvironment(Object plugin, ProxyServer proxy) {
            this.plugin = plugin;
            this.proxy = proxy;
        }

        @Override
        public Object findVanishPlugin() {
            Optional<PluginContainer> container = proxy.getPluginManager().getPlugin(PLUGIN_ID);
            if (!container.isPresent()) {
                // The id is PremiumVanish's own choice and not something its API documents, so a
                // differently spelled id or a matching display name is accepted too.
                for (PluginContainer candidate : proxy.getPluginManager().getPlugins()) {
                    String id = candidate.getDescription().getId();
                    String name = candidate.getDescription().getName().orElse("");
                    if (PLUGIN_ID.equalsIgnoreCase(id) || PLUGIN_ID.equalsIgnoreCase(name)) {
                        container = Optional.of(candidate);
                        break;
                    }
                }
            }
            if (!container.isPresent()) {
                return null;
            }
            // Empty until Velocity has constructed it; looked up again on the next call.
            return container.get().getInstance().orElse(null);
        }

        @Override
        public Optional<Player> onlinePlayer(UUID uuid) {
            return proxy.getPlayer(uuid);
        }

        @Override
        public <E> Registration listen(Class<E> type, EventHandler<E> handler) {
            return VelocityEvents.listen(
                    proxy.getEventManager(), plugin, type, PostOrder.LAST, handler);
        }
    }
}
