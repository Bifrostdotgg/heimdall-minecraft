package com.heimdall.shell.bukkit;

import com.heimdall.shell.hotswap.LoadedCore;
import com.heimdall.shell.hotswap.ShellLog;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.function.Predicate;
import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.scheduler.BukkitTask;

/**
 * Removes whatever the server still holds for a core that has stopped (see
 * {@code ShellPlatform.sweep}).
 *
 * <h2>What it looks at</h2>
 *
 * <ul>
 *   <li><strong>Listeners</strong> registered against the shell's plugin whose listener or executor
 *       class came from the retired core. This is the one that matters in practice:
 *       adventure-platform-bukkit's {@code BukkitAudiences} registers its own join and quit listener
 *       and its {@code close()} never unregisters it (confirmed in the 4.3.4 bytecode), so without
 *       this every swap would leave one behind, still firing into a stopped core.
 *   <li><strong>Services</strong> the shell's plugin registered with a provider from the retired
 *       core.
 *   <li><strong>Scheduler tasks</strong> owned by the shell's plugin whose task class came from the
 *       retired core, where the server says which class that is.
 * </ul>
 *
 * <p>Never {@code HandlerList.unregisterAll(plugin)} or {@code cancelTasks(plugin)}: both would take
 * the shell's own registrations (the login gate, the relays' world) with them.
 *
 * <p>Every reflective step is inside a {@code Throwable} catch: a server that has moved a field is a
 * server where the sweep does less, never one where the swap fails.
 */
final class BukkitSweep {

    private BukkitSweep() {
    }

    static int sweep(Plugin plugin, final LoadedCore retired, ShellLog log) {
        Predicate<Class<?>> owned = new Predicate<Class<?>>() {
            @Override
            public boolean test(Class<?> type) {
                return retired.owns(type);
            }
        };
        return listeners(plugin, owned, log) + services(plugin, owned, log)
                + tasks(plugin, owned, log);
    }

    /** The listener half, against any notion of "belongs to the retired core". */
    static int listeners(Plugin plugin, Predicate<Class<?>> retired, ShellLog log) {
        Set<Listener> stale = Collections.newSetFromMap(new IdentityHashMap<Listener, Boolean>());
        try {
            for (RegisteredListener registered : HandlerList.getRegisteredListeners(plugin)) {
                Listener listener = registered.getListener();
                Class<?> executor = executorClass(registered);
                if (retired.test(listener.getClass())
                        || (executor != null && retired.test(executor))) {
                    stale.add(listener);
                }
            }
        } catch (Throwable unreadable) {
            log.debug("could not list the plugin's listeners for the sweep: " + unreadable);
            return 0;
        }
        for (Listener listener : stale) {
            try {
                HandlerList.unregisterAll(listener);
                log.debug("swept a listener left by the old core: " + listener.getClass().getName());
            } catch (Throwable failed) {
                log.debug("could not unregister " + listener.getClass().getName() + ": " + failed);
            }
        }
        return stale.size();
    }

    private static int services(Plugin plugin, Predicate<Class<?>> retired, ShellLog log) {
        int removed = 0;
        try {
            for (RegisteredServiceProvider<?> service
                    : Bukkit.getServicesManager().getRegistrations(plugin)) {
                Object provider = service.getProvider();
                if (provider != null && retired.test(provider.getClass())) {
                    Bukkit.getServicesManager().unregister(provider);
                    removed++;
                }
            }
        } catch (Throwable unreadable) {
            log.debug("could not sweep services: " + unreadable);
        }
        return removed;
    }

    private static int tasks(Plugin plugin, Predicate<Class<?>> retired, ShellLog log) {
        int cancelled = 0;
        try {
            for (BukkitTask task : Bukkit.getScheduler().getPendingTasks()) {
                Class<?> type = taskClass(task);
                if (task.getOwner() == plugin && type != null && retired.test(type)) {
                    task.cancel();
                    cancelled++;
                }
            }
        } catch (Throwable unreadable) {
            log.debug("could not sweep scheduler tasks: " + unreadable);
        }
        return cancelled;
    }

    /** The class of a registered listener's executor, when the server lets that be read. */
    private static Class<?> executorClass(RegisteredListener registered) {
        try {
            Field field = RegisteredListener.class.getDeclaredField("executor");
            field.setAccessible(true);
            Object executor = field.get(registered);
            return executor == null ? null : executor.getClass();
        } catch (Throwable hidden) {
            return null;
        }
    }

    /**
     * The class of the work behind a task. CraftBukkit's task class exposes it for timings as
     * {@code getTaskClass()} on most versions and holds it in a {@code task} or {@code rTask} field
     * on the rest.
     */
    private static Class<?> taskClass(BukkitTask task) {
        try {
            Method method = task.getClass().getMethod("getTaskClass");
            Object type = method.invoke(task);
            if (type instanceof Class) {
                return (Class<?>) type;
            }
        } catch (Throwable absent) {
            // Fall through to the fields.
        }
        for (String name : new String[] {"task", "rTask"}) {
            Class<?> cursor = task.getClass();
            while (cursor != null && cursor != Object.class) {
                try {
                    Field field = cursor.getDeclaredField(name);
                    field.setAccessible(true);
                    Object value = field.get(task);
                    if (value != null) {
                        return value.getClass();
                    }
                } catch (Throwable absent) {
                    // Try the superclass.
                }
                cursor = cursor.getSuperclass();
            }
        }
        return null;
    }
}
