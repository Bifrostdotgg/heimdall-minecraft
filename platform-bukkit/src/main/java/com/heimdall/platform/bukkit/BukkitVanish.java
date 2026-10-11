package com.heimdall.platform.bukkit;

import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.platform.PlayerHandle;
import com.heimdall.core.util.Registration;
import com.heimdall.platform.common.CoreRegistrations;
import com.heimdall.platform.common.VanishSupport;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;

/**
 * Vanish on a Bukkit backend: PremiumVanish or SuperVanish reached reflectively, plus the
 * {@code vanished} metadata convention. Departure D88.
 *
 * <h2>Two sources</h2>
 *
 * <ul>
 *   <li><strong>The {@code vanished} metadata</strong>, set to a true value by SuperVanish,
 *       EssentialsX, CMI and VanishNoPacket among others. Read first and needs no plugin to be
 *       found, so a server running only EssentialsX gets suppression too. It never asks a join to
 *       wait, so it is best-effort: it has no events, and some plugins set it a moment after the
 *       join (EssentialsX applies a silent-join vanish in a join task that runs afterwards), so a
 *       join they hide can be checked before the flag is set.
 *   <li><strong>PremiumVanish's {@code VanishAPI}</strong>, which SuperVanish ships too, under the
 *       same names. {@code isInvisible(Player)} for a player still online, and
 *       {@code isInvisibleOffline(UUID)} once they have gone, because PremiumVanish documents the
 *       first as unreliable for an offline player when it runs on MySQL.
 * </ul>
 *
 * <h2>Events</h2>
 *
 * <p>{@code PostPlayerHideEvent} and {@code PostPlayerShowEvent}, which fire after the change has
 * happened and cannot be cancelled. An older PremiumVanish without them falls back to
 * {@code PlayerHideEvent} / {@code PlayerShowEvent} at {@code MONITOR}, ignoring cancelled ones,
 * which is the same fact one step earlier. {@code PlayerAutoVanishOnJoinEvent} is a hide that
 * happens inside a join, and is delivered as a silent one. All at {@code MONITOR}: nothing here
 * changes the event. Every event is optional: a release without one still answers the queries, so
 * vanished joins stay out of Discord, and only that kind of mid-session change goes unseen (one
 * warning).
 *
 * <p>The listener object is anonymous and so package-private, which keeps Paper 1.16's generated
 * executor cache from pinning a swapped-out core (D87, {@code BukkitListenerShapeTest}).
 */
final class BukkitVanish extends VanishSupport {

    /** The plugins that ship PremiumVanish's API, in the order they are looked for. */
    static final String[] PLUGIN_NAMES = {"PremiumVanish", "SuperVanish"};

    static final String API_CLASS = "de.myzelyam.api.vanish.VanishAPI";
    static final String POST_HIDE_EVENT = "de.myzelyam.api.vanish.PostPlayerHideEvent";
    static final String POST_SHOW_EVENT = "de.myzelyam.api.vanish.PostPlayerShowEvent";
    static final String HIDE_EVENT = "de.myzelyam.api.vanish.PlayerHideEvent";
    static final String SHOW_EVENT = "de.myzelyam.api.vanish.PlayerShowEvent";
    static final String AUTO_VANISH_EVENT = "de.myzelyam.api.vanish.PlayerAutoVanishOnJoinEvent";

    /** The metadata key the vanish plugins agree on. */
    static final String METADATA_KEY = "vanished";

    /**
     * What this class needs from the server, as a seam. Exists for the tests, for the same reason as
     * {@code ChatControlChannels.Environment}: a mocked plugin's class loader cannot stand in for
     * PremiumVanish's, and registering an event needs a live plugin manager.
     */
    interface Environment {

        /** The first of {@link #PLUGIN_NAMES} that is loaded (enabled or not), else {@code null}. */
        Plugin findVanishPlugin();

        /** The class loader the plugin's own classes live in. */
        ClassLoader loaderOf(Plugin plugin);

        /** Registers {@code executor} for {@code type} at MONITOR, ignoring cancelled events. */
        void register(Class<? extends Event> type, Listener listener, EventExecutor executor);

        /** Removes everything registered for {@code listener}. */
        void unregister(Listener listener);
    }

    private final Environment environment;
    private final Function<Player, PlayerHandle> handles;

    BukkitVanish(
            HeimdallLogger logger,
            CoreRegistrations registrations,
            Environment environment,
            Function<Player, PlayerHandle> handles) {
        super(logger, registrations, false);
        this.environment = environment;
        this.handles = handles;
    }

    /** The production wiring: Bukkit's plugin manager, with registrations owned by {@code heimdall}. */
    static BukkitVanish create(
            HeimdallLogger logger,
            CoreRegistrations registrations,
            Plugin heimdall,
            Function<Player, PlayerHandle> handles) {
        return new BukkitVanish(logger, registrations, new BukkitEnvironment(heimdall), handles);
    }

    @Override
    protected boolean vanishedByConvention(PlayerHandle player) {
        Player bukkit = unwrap(player);
        if (bukkit == null) {
            return false;
        }
        // Read from the Player object the handle was made from, which is what makes this work at a
        // quit too: the metadata store keeps the value past the moment Bukkit.getPlayer stops
        // finding them.
        List<MetadataValue> values = bukkit.getMetadata(METADATA_KEY);
        if (values == null) {
            return false;
        }
        for (MetadataValue value : values) {
            if (value != null && value.asBoolean()) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected Binding bind() throws Throwable {
        final Plugin plugin = environment.findVanishPlugin();
        if (plugin == null || !plugin.isEnabled()) {
            return null;
        }
        ClassLoader loader = environment.loaderOf(plugin);
        if (loader == null) {
            throw new ClassNotFoundException(plugin.getName() + " has no class loader");
        }
        Class<?> api = Class.forName(API_CLASS, true, loader);
        final Method isInvisible = api.getMethod("isInvisible", Player.class);
        final Method isInvisibleOffline = optionalMethod(api, "isInvisibleOffline", UUID.class);

        final List<Hook> hooks = new ArrayList<Hook>();
        Class<? extends Event> hide = optionalEvent(loader, POST_HIDE_EVENT);
        Class<? extends Event> show = optionalEvent(loader, POST_SHOW_EVENT);
        if (hide == null) {
            hide = optionalEvent(loader, HIDE_EVENT);
        }
        if (show == null) {
            show = optionalEvent(loader, SHOW_EVENT);
        }
        // Each event is optional: a release without them still answers isInvisible, which is what
        // keeps vanished joins out of Discord. Only the mid-session toggles depend on these.
        final boolean toggles = hide != null && show != null;
        if (hide != null) {
            hooks.add(new Hook(hide, true, false));
        }
        if (show != null) {
            hooks.add(new Hook(show, false, false));
        }
        Class<? extends Event> autoVanish = optionalEvent(loader, AUTO_VANISH_EVENT);
        if (autoVanish != null) {
            hooks.add(new Hook(autoVanish, true, true));
        }

        return new Binding() {
            @Override
            public boolean current() {
                return plugin.isEnabled();
            }

            @Override
            public boolean vanished(PlayerHandle player) throws Throwable {
                Player bukkit = unwrap(player);
                if (bukkit != null && Boolean.TRUE.equals(isInvisible.invoke(null, bukkit))) {
                    return true;
                }
                if (isInvisibleOffline == null || (bukkit != null && bukkit.isOnline())) {
                    return false;
                }
                // Gone (a quit is reported after the fact), or a handle this platform did not make.
                // The offline lookup is the one PremiumVanish documents as correct on MySQL.
                return Boolean.TRUE.equals(isInvisibleOffline.invoke(null, player.uuid()));
            }

            @Override
            public Registration hook() {
                // A missing auto-vanish event costs nothing (the join's own check sees it), so only
                // the toggle events are worth a warning.
                if (!toggles) {
                    logger.warn("this PremiumVanish has no vanish or reappear event; vanished "
                            + "players' joins, leaves and deaths are still kept out of Discord, but "
                            + "a vanish mid-session is not seen as it happens");
                }
                if (hooks.isEmpty()) {
                    return Registration.NONE;
                }
                return register(hooks);
            }
        };
    }

    /** Registers every hook on one listener object, so one unregister takes them all off. */
    private Registration register(List<Hook> hooks) {
        final Listener listener = new Listener() {
        };
        try {
            for (final Hook hook : hooks) {
                final Method silentFlag = optionalMethod(hook.type, "isSilent");
                environment.register(hook.type, listener, new EventExecutor() {
                    @Override
                    public void execute(Listener ignored, Event event) {
                        onEvent(hook, silentFlag, event);
                    }
                });
            }
        } catch (RuntimeException | Error failed) {
            environment.unregister(listener);
            throw failed;
        }
        return Registration.once(new Runnable() {
            @Override
            public void run() {
                environment.unregister(listener);
            }
        });
    }

    /**
     * One hide or show. Contained as {@link Throwable}: this runs inside PremiumVanish's own call
     * into the event bus, and an escaped error would land in its stack, not ours.
     */
    private void onEvent(Hook hook, Method silentFlag, Event event) {
        try {
            if (!hook.type.isInstance(event) || !(event instanceof PlayerEvent)) {
                return;
            }
            Player player = ((PlayerEvent) event).getPlayer();
            if (player == null) {
                return;
            }
            boolean silent = hook.silent;
            if (!silent && silentFlag != null) {
                silent = Boolean.TRUE.equals(silentFlag.invoke(event));
            }
            toggled(handles.apply(player), hook.vanished, silent);
        } catch (Throwable failed) {
            logger.error("could not read a PremiumVanish vanish event; that one change was not seen",
                    failed);
        }
    }

    private static Player unwrap(PlayerHandle player) {
        return player instanceof BukkitPlayerHandle ? ((BukkitPlayerHandle) player).player() : null;
    }

    private static Method optionalMethod(Class<?> owner, String name, Class<?>... parameters) {
        try {
            return owner.getMethod(name, parameters);
        } catch (NoSuchMethodException absent) {
            return null;
        }
    }

    private static Class<? extends Event> optionalEvent(ClassLoader loader, String name) {
        Class<?> type;
        try {
            type = Class.forName(name, true, loader);
        } catch (ClassNotFoundException | LinkageError absent) {
            return null;
        }
        if (!Event.class.isAssignableFrom(type)) {
            return null;
        }
        return type.asSubclass(Event.class);
    }

    /** One event this integration listens to, and what it means. */
    private static final class Hook {

        final Class<? extends Event> type;
        final boolean vanished;
        final boolean silent;

        Hook(Class<? extends Event> type, boolean vanished, boolean silent) {
            this.type = type;
            this.vanished = vanished;
            this.silent = silent;
        }
    }

    /** The real server. */
    private static final class BukkitEnvironment implements Environment {

        private final Plugin heimdall;

        BukkitEnvironment(Plugin heimdall) {
            this.heimdall = heimdall;
        }

        @Override
        public Plugin findVanishPlugin() {
            for (String name : PLUGIN_NAMES) {
                Plugin found = Bukkit.getPluginManager().getPlugin(name);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }

        @Override
        public ClassLoader loaderOf(Plugin plugin) {
            return plugin.getClass().getClassLoader();
        }

        @Override
        public void register(Class<? extends Event> type, Listener listener, EventExecutor executor) {
            Bukkit.getPluginManager()
                    .registerEvent(type, listener, EventPriority.MONITOR, executor, heimdall, true);
        }

        @Override
        public void unregister(Listener listener) {
            HandlerList.unregisterAll(listener);
        }
    }
}
