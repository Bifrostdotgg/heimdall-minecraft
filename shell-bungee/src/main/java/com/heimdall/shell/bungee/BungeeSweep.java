package com.heimdall.shell.bungee;

import com.heimdall.shell.hotswap.LoadedCore;
import com.heimdall.shell.hotswap.ShellLog;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.api.plugin.PluginManager;

/**
 * Removes listeners and commands a stopped core left registered against the shell's plugin.
 *
 * <p>BungeeCord has no public way to list a plugin's listeners or commands, only
 * {@code unregisterListeners(plugin)} and {@code unregisterCommands(plugin)}, both of which would
 * take the shell's own login gate and relays with them. So the two per-plugin multimaps the plugin
 * manager keeps ({@code listenersByPlugin}, {@code commandsByPlugin}) are read reflectively, and each
 * entry that belongs to the retired core is unregistered one by one. A proxy whose fields moved gets
 * a sweep that finds nothing, never a failed swap: the core's own tracked teardown has already run
 * by the time this does, and this is only the backstop behind it.
 */
final class BungeeSweep {

    private BungeeSweep() {
    }

    static int sweep(ProxyServer proxy, Plugin plugin, LoadedCore retired, ShellLog log) {
        PluginManager manager = proxy.getPluginManager();
        int removed = 0;
        for (Object listener : entries(manager, "listenersByPlugin", plugin, log)) {
            if (listener instanceof Listener && retired.owns(listener.getClass())) {
                try {
                    manager.unregisterListener((Listener) listener);
                    removed++;
                } catch (Throwable failed) {
                    log.debug("could not unregister a listener: " + failed);
                }
            }
        }
        for (Object command : entries(manager, "commandsByPlugin", plugin, log)) {
            if (command instanceof Command && retired.owns(command.getClass())) {
                try {
                    manager.unregisterCommand((Command) command);
                    removed++;
                } catch (Throwable failed) {
                    log.debug("could not unregister a command: " + failed);
                }
            }
        }
        return removed;
    }

    /** A copy of {@code multimap.get(plugin)} for one of the manager's private multimaps. */
    private static List<Object> entries(
            PluginManager manager, String field, Plugin plugin, ShellLog log) {
        List<Object> out = new ArrayList<Object>();
        try {
            Field declared = PluginManager.class.getDeclaredField(field);
            declared.setAccessible(true);
            Object multimap = declared.get(manager);
            if (multimap == null) {
                return out;
            }
            Method get = multimap.getClass().getMethod("get", Object.class);
            get.setAccessible(true);
            Object values = get.invoke(multimap, plugin);
            if (values instanceof Collection) {
                out.addAll((Collection<?>) values);
            }
        } catch (Throwable hidden) {
            log.debug("could not read the plugin manager's " + field + " for the sweep: " + hidden);
        }
        return out;
    }
}
