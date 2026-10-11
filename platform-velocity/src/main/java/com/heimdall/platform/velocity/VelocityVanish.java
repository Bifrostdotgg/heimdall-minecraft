package com.heimdall.platform.velocity;

import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.platform.PlayerHandle;
import com.heimdall.core.util.Registration;
import com.heimdall.platform.common.CoreRegistrations;
import com.heimdall.platform.common.VanishSupport;
import com.velocitypowered.api.event.EventHandler;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.LongSupplier;

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
 * to register on Velocity without its method-handle cache pinning a swapped-out core (D87). Both are
 * optional: they arrived in PremiumVanish well after the query API did. Velocity cannot tell whether
 * a toggle was announced in game, and the backend that announced it relays its own copy, so every
 * toggle is delivered as silent and a proxy never announces one (D88).
 *
 * <h2>The join wait is anchored to the backend</h2>
 *
 * <p>The proxy's PremiumVanish learns a player's state only after they reach a backend, which can
 * take longer than the whole settle window, or never (an auth limbo). So the wait counts from the
 * first backend connection of the session, and a join with no connection yet is not decided at
 * all; see {@link VanishSupport}.
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

        /** Everyone on the proxy now. */
        Collection<Player> onlinePlayers();

        /** The class loader PremiumVanish's own classes live in. */
        ClassLoader loaderOf(Object instance);

        /** Registers {@code handler} for {@code type} at {@code order}. */
        <E> Registration listen(Class<E> type, PostOrder order, EventHandler<E> handler);
    }

    private final Environment environment;
    private final Function<Player, PlayerHandle> handles;

    VelocityVanish(
            HeimdallLogger logger,
            CoreRegistrations registrations,
            Environment environment,
            Function<Player, PlayerHandle> handles) {
        this(logger, registrations, environment, handles, null);
    }

    /** With a substitute monotonic clock (nanoseconds), so a test's join wait is exact. */
    VelocityVanish(
            HeimdallLogger logger,
            CoreRegistrations registrations,
            Environment environment,
            Function<Player, PlayerHandle> handles,
            LongSupplier clock) {
        super(logger, registrations, true, clock);
        this.environment = environment;
        this.handles = handles;
    }

    @Override
    protected boolean isOnline(UUID player) {
        return environment.onlinePlayer(player).isPresent();
    }

    /**
     * After a hot swap, nobody already online logs in again: a player on a server is recorded as
     * connected, and one with no server (an auth limbo) as still connecting, so their join is held
     * and a later quit cannot post a leave for a join that was never posted.
     */
    @Override
    protected void seedBackends() {
        for (Player player : environment.onlinePlayers()) {
            if (player.getCurrentServer().isPresent()) {
                backendConnected(player.getUniqueId());
            } else {
                stillConnecting(player.getUniqueId());
            }
        }
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
        ClassLoader loader = environment.loaderOf(instance);
        if (loader == null) {
            throw new ClassNotFoundException("PremiumVanish has no class loader");
        }
        Class<?> api = Class.forName(API_CLASS, true, loader);
        final Method isInvisible = api.getMethod("isInvisible", Player.class);
        final Method allInvisible = optionalMethod(api, "getAllInvisiblePlayers");
        // Optional: PremiumVanish shipped VelocityVanishAPI (2.9.0, November 2023) well over a year
        // before these events (March 2025). A release from that window still answers isInvisible,
        // which is what keeps vanished joins out of Discord; only the toggles need the events.
        final Class<?> hide = optionalClass(loader, HIDE_EVENT);
        final Class<?> show = optionalClass(loader, SHOW_EVENT);
        final Method hidePlayer = hide == null ? null : optionalMethod(hide, "getPlayer");
        final Method showPlayer = show == null ? null : optionalMethod(show, "getPlayer");

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
                final List<Registration> made = new ArrayList<Registration>();
                try {
                    // The login and the backend connection anchor the join wait, so they are
                    // registered whether or not this release has the toggle events.
                    made.add(listenForLogins());
                    made.add(listenForBackends());
                    watchingBackends();
                    if (hidePlayer != null && showPlayer != null) {
                        made.add(listen(hide, hidePlayer, true));
                        made.add(listen(show, showPlayer, false));
                    } else {
                        logger.warn("this PremiumVanish has no Velocity vanish and reappear events "
                                + "(they arrived in a later release); vanished players' joins and "
                                + "leaves are still kept out of Discord, but a vanish mid-session "
                                + "is not seen as it happens");
                    }
                } catch (RuntimeException | Error failed) {
                    closeAll(made);
                    throw failed;
                }
                return Registration.once(new Runnable() {
                    @Override
                    public void run() {
                        closeAll(made);
                    }
                });
            }
        };
    }

    /**
     * The player's backend connections, which anchor a join's wait (see {@link VanishSupport}).
     * {@code ServerPostConnectEvent} fires once the player is on the backend, which is the moment
     * PremiumVanish there can tell this proxy anything; a Velocity too old to have it gets
     * {@code ServerConnectedEvent}, a moment earlier. Each lives in its own anonymous class so the
     * missing one is never even loaded.
     */
    private Registration listenForBackends() {
        try {
            return environment.listen(ServerPostConnectEvent.class, PostOrder.LAST,
                    new EventHandler<ServerPostConnectEvent>() {
                        @Override
                        public void execute(ServerPostConnectEvent event) {
                            onBackend(event.getPlayer());
                        }
                    });
        } catch (LinkageError tooOld) {
            return environment.listen(ServerConnectedEvent.class, PostOrder.LAST,
                    new EventHandler<ServerConnectedEvent>() {
                        @Override
                        public void execute(ServerConnectedEvent event) {
                            onBackend(event.getPlayer());
                        }
                    });
        }
    }

    /**
     * Each login starts a new session, so the previous session's backend connection stops counting.
     * {@link PostOrder#FIRST}, so it runs before the session listener (at {@code LAST}) reports the
     * join and the bridge first asks how long to wait.
     */
    private Registration listenForLogins() {
        return environment.listen(PostLoginEvent.class, PostOrder.FIRST,
                new EventHandler<PostLoginEvent>() {
                    @Override
                    public void execute(PostLoginEvent event) {
                        try {
                            sessionStarted(event.getPlayer().getUniqueId());
                        } catch (Throwable failed) {
                            logger.error("could not note a proxy login for the vanish wait", failed);
                        }
                    }
                });
    }

    private void onBackend(Player player) {
        try {
            if (player != null) {
                backendConnected(player.getUniqueId());
            }
        } catch (Throwable failed) {
            logger.error("could not note a backend connection; that join waits for its cap", failed);
        }
    }

    private static void closeAll(List<Registration> registrations) {
        for (int i = registrations.size() - 1; i >= 0; i--) {
            try {
                registrations.get(i).close();
            } catch (Throwable ignored) {
                // Teardown; the rest still come off.
            }
        }
    }

    private static Class<?> optionalClass(ClassLoader loader, String name) {
        try {
            return Class.forName(name, true, loader);
        } catch (ClassNotFoundException | LinkageError absent) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Registration listen(Class<?> type, final Method getPlayer, final boolean vanished) {
        return environment.listen((Class<Object>) type, PostOrder.LAST, new EventHandler<Object>() {
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
                // Always silent: Velocity cannot tell whether PremiumVanish announced the change in
                // game, and the backend that did announce it relays its own copy (D88).
                toggled(handles.apply((Player) player), vanished, true);
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
        public Collection<Player> onlinePlayers() {
            return proxy.getAllPlayers();
        }

        @Override
        public ClassLoader loaderOf(Object instance) {
            return instance.getClass().getClassLoader();
        }

        @Override
        public <E> Registration listen(Class<E> type, PostOrder order, EventHandler<E> handler) {
            return VelocityEvents.listen(proxy.getEventManager(), plugin, type, order, handler);
        }
    }
}
