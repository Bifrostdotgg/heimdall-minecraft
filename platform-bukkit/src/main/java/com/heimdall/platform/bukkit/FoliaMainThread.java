package com.heimdall.platform.bukkit;

import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.platform.PlayerHandle;
import com.heimdall.core.util.Registration;
import com.heimdall.platform.bukkit.adapter.FoliaSupport;
import java.lang.reflect.Method;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Getting onto the right region thread on a server that has Folia's scheduler APIs.
 *
 * <p>Folia rejects {@code Bukkit.getScheduler()} with {@code UnsupportedOperationException}. The
 * replacement is two schedulers, reached here by reflection so this class stays Java 8 bytecode
 * and never names a type that Spigot 1.8.8 does not have:
 *
 * <ul>
 *   <li>{@code Server#getGlobalRegionScheduler()} — console dispatch, {@link #execute}, delayed
 *       join-settle work. The global region is Folia's answer to "the main thread".
 *   <li>{@code Entity#getScheduler()} — kicks and chat. An entity scheduler follows the player
 *       across region boundaries; hopping to the global region and then kicking would be the
 *       wrong thread the moment they are anywhere else.
 * </ul>
 *
 * <p>The same APIs exist on modern Paper, where they hop to the (one) main thread. Selecting this
 * class whenever the methods exist — not when the server calls itself Folia — is why Canvas and
 * other forks work without a brand check.
 *
 * <h2>Already on the right thread means run now</h2>
 *
 * <p>Same contract as {@link BukkitMainThread}: an executor that defers to the next tick when the
 * caller is already on the destination thread is not an executor. Global work checks
 * {@code Bukkit.isPrimaryThread()} (true on Folia's global region). Entity work checks
 * {@code Bukkit.isOwnedByCurrentRegion} when that method exists.
 *
 * <h2>A gone player still runs the task</h2>
 *
 * <p>{@link com.heimdall.core.platform.SchedulerBridge} promises a null or disconnected player
 * still runs the task somewhere sensible. Folia's entity scheduler retires the task instead, so
 * the retired callback — and any path that cannot unwrap a live {@code Player} — falls back to
 * the global region rather than dropping the work.
 */
final class FoliaMainThread implements ServerThread {

    /** One tick, in milliseconds. Folia's scheduler counts ticks, callers count time. */
    private static final long MS_PER_TICK = 50L;

    private final HeimdallLogger logger;
    private final RegionisedTasks tasks;

    FoliaMainThread(HeimdallLogger logger, RegionisedTasks tasks) {
        this.logger = logger;
        this.tasks = tasks;
    }

    /**
     * Binds the region schedulers reflectively, or returns {@code null} if this server does not
     * actually have a usable pair of them.
     *
     * <p>The probe in {@link FoliaSupport} is necessary but not sufficient: a server can have the
     * two lookup methods and still lack {@code execute}/{@code runDelayed} on what they return.
     * Binding is what proves the hop will work, and a half-bound adapter would be worse than the
     * Bukkit scheduler — it would load, then throw the first time anything was scheduled.
     */
    static FoliaMainThread tryCreate(Plugin plugin, HeimdallLogger logger) {
        if (plugin == null || !FoliaSupport.hasRegionSchedulers()) {
            return null;
        }
        RegionisedTasks bound = ReflectiveRegionisedTasks.bind(plugin);
        if (bound == null) {
            return null;
        }
        return new FoliaMainThread(logger, bound);
    }

    @Override
    public String describe() {
        return "folia";
    }

    @Override
    public void execute(Runnable command) {
        if (command == null) {
            return;
        }
        if (tasks.isGlobalThread()) {
            runGuarded(command);
            return;
        }
        try {
            tasks.runGlobal(guard(command));
        } catch (Throwable rejected) {
            logger.debug(() -> "the global region scheduler refused a task (the plugin is "
                    + "disabling); running it inline instead: " + rejected);
            runGuarded(command);
        }
    }

    @Override
    public void runOnEntityThread(PlayerHandle player, Runnable task) {
        if (task == null) {
            return;
        }
        Player bukkit = unwrap(player);
        if (bukkit == null || !usable(bukkit)) {
            execute(task);
            return;
        }
        if (tasks.currentlyOwns(bukkit)) {
            runGuarded(task);
            return;
        }
        try {
            tasks.runOnEntity(bukkit, guard(task), new Runnable() {
                @Override
                public void run() {
                    // The entity was removed before the task ran. Folia retires it; we still owe
                    // the caller a somewhere-sensible execution.
                    execute(task);
                }
            });
        } catch (Throwable rejected) {
            logger.debug(() -> "the entity scheduler refused a task; running it on the global "
                    + "region instead: " + rejected);
            execute(task);
        }
    }

    @Override
    public Registration runLater(Runnable task, long delayMs) {
        if (task == null) {
            return Registration.NONE;
        }
        try {
            return tasks.runGlobalLater(guard(task), delayMs);
        } catch (Throwable rejected) {
            logger.debug(() -> "not scheduling delayed work; the plugin is disabling: " + rejected);
            return Registration.NONE;
        }
    }

    private Runnable guard(final Runnable task) {
        return new Runnable() {
            @Override
            public void run() {
                runGuarded(task);
            }
        };
    }

    private void runGuarded(Runnable task) {
        try {
            task.run();
        } catch (RuntimeException e) {
            logger.error("a task on a region thread failed", e);
        }
    }

    private static Player unwrap(PlayerHandle player) {
        if (player instanceof BukkitPlayerHandle) {
            return ((BukkitPlayerHandle) player).player();
        }
        return null;
    }

    private static boolean usable(Player player) {
        try {
            return player.isOnline();
        } catch (RuntimeException gone) {
            return false;
        }
    }

    /**
     * The two hops, so a test can drive them without a Folia server.
     *
     * <p>A seam of the same shape as {@link BukkitConsoleBridge.CommandSink}: the production
     * implementation is reflection against a live server, and the branch under test is otherwise
     * reachable only by starting Minecraft.
     */
    interface RegionisedTasks {

        boolean isGlobalThread();

        boolean currentlyOwns(Player player);

        void runGlobal(Runnable task);

        Registration runGlobalLater(Runnable task, long delayMs);

        void runOnEntity(Player player, Runnable task, Runnable retired);
    }

    /**
     * The live binding. Method objects are resolved once; a server that passed
     * {@link FoliaSupport#hasRegionSchedulers()} but whose returned schedulers do not have the
     * methods we need is treated as unbound, not as a runtime surprise.
     */
    private static final class ReflectiveRegionisedTasks implements RegionisedTasks {

        private final Plugin plugin;
        private final Object globalScheduler;
        private final Method globalExecute;
        private final Method globalRun;
        private final Method globalRunDelayed;
        private final Method getEntityScheduler;
        private final Method entityExecute;
        private final Method entityRun;
        private final Method isOwnedByCurrentRegion;
        private final Object isOwnedTarget;

        private ReflectiveRegionisedTasks(
                Plugin plugin,
                Object globalScheduler,
                Method globalExecute,
                Method globalRun,
                Method globalRunDelayed,
                Method getEntityScheduler,
                Method entityExecute,
                Method entityRun,
                Method isOwnedByCurrentRegion,
                Object isOwnedTarget) {
            this.plugin = plugin;
            this.globalScheduler = globalScheduler;
            this.globalExecute = globalExecute;
            this.globalRun = globalRun;
            this.globalRunDelayed = globalRunDelayed;
            this.getEntityScheduler = getEntityScheduler;
            this.entityExecute = entityExecute;
            this.entityRun = entityRun;
            this.isOwnedByCurrentRegion = isOwnedByCurrentRegion;
            this.isOwnedTarget = isOwnedTarget;
        }

        static RegionisedTasks bind(Plugin plugin) {
            try {
                Object server = Bukkit.getServer();
                if (server == null) {
                    return null;
                }
                Object global = server.getClass().getMethod("getGlobalRegionScheduler").invoke(server);
                if (global == null) {
                    return null;
                }
                Method globalExecute = findMethod(global.getClass(), "execute", Plugin.class, Runnable.class);
                Method globalRun = findMethod(global.getClass(), "run", Plugin.class, Consumer.class);
                Method globalRunDelayed = findMethod(
                        global.getClass(), "runDelayed", Plugin.class, Consumer.class, Long.TYPE);
                if ((globalExecute == null && globalRun == null) || globalRunDelayed == null) {
                    return null;
                }

                Method getEntityScheduler = Entity.class.getMethod("getScheduler");
                Class<?> entitySchedulerType = getEntityScheduler.getReturnType();
                Method entityExecute = findMethod(
                        entitySchedulerType,
                        "execute",
                        Plugin.class,
                        Runnable.class,
                        Runnable.class,
                        Long.TYPE);
                Method entityRun = findMethod(
                        entitySchedulerType, "run", Plugin.class, Consumer.class, Runnable.class);
                if (entityExecute == null && entityRun == null) {
                    return null;
                }

                Method isOwned = findMethod(Bukkit.class, "isOwnedByCurrentRegion", Entity.class);
                Object isOwnedTarget = null;
                if (isOwned == null) {
                    isOwned = findMethod(server.getClass(), "isOwnedByCurrentRegion", Entity.class);
                    isOwnedTarget = server;
                }

                return new ReflectiveRegionisedTasks(
                        plugin,
                        global,
                        globalExecute,
                        globalRun,
                        globalRunDelayed,
                        getEntityScheduler,
                        entityExecute,
                        entityRun,
                        isOwned,
                        isOwnedTarget);
            } catch (Throwable unbound) {
                return null;
            }
        }

        @Override
        public boolean isGlobalThread() {
            return Bukkit.isPrimaryThread();
        }

        @Override
        public boolean currentlyOwns(Player player) {
            if (isOwnedByCurrentRegion == null) {
                return false;
            }
            try {
                Object result = isOwnedByCurrentRegion.invoke(isOwnedTarget, player);
                return Boolean.TRUE.equals(result);
            } catch (Throwable unknown) {
                return false;
            }
        }

        @Override
        public void runGlobal(Runnable task) {
            try {
                if (globalExecute != null) {
                    globalExecute.invoke(globalScheduler, plugin, task);
                    return;
                }
                globalRun.invoke(globalScheduler, plugin, asConsumer(task));
            } catch (Throwable failed) {
                throw unwrap(failed);
            }
        }

        @Override
        public Registration runGlobalLater(Runnable task, long delayMs) {
            long ticks = Math.max(0L, delayMs) / MS_PER_TICK;
            if (ticks < 1L) {
                // Folia's runDelayed rejects a non-positive delay. One tick is what Bukkit's
                // runTaskLater(0) already meant: the next time the destination thread ticks.
                ticks = 1L;
            }
            try {
                Object handle = globalRunDelayed.invoke(
                        globalScheduler, plugin, asConsumer(task), Long.valueOf(ticks));
                return cancellable(handle);
            } catch (Throwable failed) {
                throw unwrap(failed);
            }
        }

        @Override
        public void runOnEntity(Player player, Runnable task, Runnable retired) {
            try {
                Object scheduler = getEntityScheduler.invoke(player);
                if (scheduler == null) {
                    if (retired != null) {
                        retired.run();
                    }
                    return;
                }
                Method execute = entityExecute != null
                        ? entityExecute
                        : findMethod(
                                scheduler.getClass(),
                                "execute",
                                Plugin.class,
                                Runnable.class,
                                Runnable.class,
                                Long.TYPE);
                if (execute != null) {
                    Object scheduled = execute.invoke(
                            scheduler, plugin, task, retired, Long.valueOf(1L));
                    if (scheduled instanceof Boolean && !((Boolean) scheduled).booleanValue()) {
                        if (retired != null) {
                            retired.run();
                        }
                    }
                    return;
                }
                Method run = entityRun != null
                        ? entityRun
                        : findMethod(
                                scheduler.getClass(), "run", Plugin.class, Consumer.class, Runnable.class);
                if (run == null) {
                    if (retired != null) {
                        retired.run();
                    }
                    return;
                }
                run.invoke(scheduler, plugin, asConsumer(task), retired);
            } catch (Throwable failed) {
                throw unwrap(failed);
            }
        }

        private static RuntimeException unwrap(Throwable failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof RuntimeException) {
                return (RuntimeException) cause;
            }
            if (failed instanceof RuntimeException) {
                return (RuntimeException) failed;
            }
            return new IllegalStateException(cause == null ? failed : cause);
        }

        private static Registration cancellable(final Object handle) {
            if (handle == null) {
                return Registration.NONE;
            }
            final Method cancel = findMethod(handle.getClass(), "cancel");
            if (cancel == null) {
                return Registration.NONE;
            }
            return Registration.once(new Runnable() {
                @Override
                public void run() {
                    try {
                        cancel.invoke(handle);
                    } catch (Throwable ignored) {
                        // Cancelling after the task has run, or after the plugin has disabled, is
                        // the normal close path. A failure here must not become a disable-time
                        // stack trace.
                    }
                }
            });
        }

        private static Consumer<Object> asConsumer(final Runnable task) {
            return new Consumer<Object>() {
                @Override
                public void accept(Object ignored) {
                    task.run();
                }
            };
        }

        private static Method findMethod(Class<?> type, String name, Class<?>... parameters) {
            if (type == null) {
                return null;
            }
            try {
                Method method = type.getMethod(name, parameters);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException missing) {
                return null;
            }
        }
    }
}
