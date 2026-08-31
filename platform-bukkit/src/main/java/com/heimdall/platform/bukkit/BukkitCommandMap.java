package com.heimdall.platform.bukkit;

import com.heimdall.core.log.HeimdallLogger;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.Plugin;

/**
 * Runtime command-map bind and unbind for names that are not in plugin.yml.
 *
 * <p>Descriptor commands cannot yield the label: Bukkit leaves a PluginCommand in the map for the
 * life of the plugin, and our unbind is a Disabled stub that would swallow {@code /ban} on a
 * LiteBans hook install. Names that must be able to disappear therefore never live in plugin.yml
 * and are put on the map here, then taken off again.
 */
final class BukkitCommandMap {

    private static final String FALLBACK = "heimdall";

    private BukkitCommandMap() {
    }

    static PluginCommand create(Plugin plugin, String name, HeimdallLogger logger) {
        try {
            Constructor<PluginCommand> ctor =
                    PluginCommand.class.getDeclaredConstructor(String.class, Plugin.class);
            ctor.setAccessible(true);
            return ctor.newInstance(name, plugin);
        } catch (ReflectiveOperationException e) {
            if (logger != null) {
                logger.warn("cannot construct PluginCommand for /" + name + ": " + e);
            }
            return null;
        }
    }

    static CommandMap mapOf(Plugin plugin, HeimdallLogger logger) {
        Object server = plugin.getServer();
        try {
            Method method = server.getClass().getMethod("getCommandMap");
            Object map = method.invoke(server);
            if (map instanceof CommandMap) {
                return (CommandMap) map;
            }
        } catch (ReflectiveOperationException ignored) {
            // CraftBukkit has it; the Bukkit interface did not always.
        }
        try {
            Object manager = plugin.getServer().getPluginManager();
            Field field = fieldNamed(manager.getClass(), "commandMap");
            if (field != null) {
                field.setAccessible(true);
                Object map = field.get(manager);
                if (map instanceof CommandMap) {
                    return (CommandMap) map;
                }
            }
        } catch (ReflectiveOperationException e) {
            if (logger != null) {
                logger.warn("cannot reach the command map: " + e);
            }
        }
        return null;
    }

    static RegistrationHandle bind(
            Plugin plugin,
            PluginCommand command,
            List<String> aliases,
            HeimdallLogger logger) {
        CommandMap map = mapOf(plugin, logger);
        if (map == null) {
            return null;
        }
        Map<String, Command> known = knownCommands(map, logger);
        if (known == null) {
            return null;
        }
        String name = command.getName().toLowerCase(Locale.ROOT);
        Command previous = known.get(name);
        known.put(name, command);
        known.put(FALLBACK + ":" + name, command);
        if (aliases != null) {
            for (int i = 0; i < aliases.size(); i++) {
                String alias = aliases.get(i);
                if (alias == null || alias.isEmpty()) continue;
                String key = alias.toLowerCase(Locale.ROOT);
                if (!known.containsKey(key) || known.get(key) == previous) {
                    known.put(key, command);
                }
                known.put(FALLBACK + ":" + key, command);
            }
        }
        try {
            command.register(map);
        } catch (RuntimeException e) {
            if (logger != null) {
                logger.warn("command.register(/" + name + ") failed: " + e);
            }
        }
        return new RegistrationHandle(map, known, name, aliases, command, previous);
    }

    static final class RegistrationHandle {
        private final CommandMap map;
        private final Map<String, Command> known;
        private final String name;
        private final List<String> aliases;
        private final PluginCommand command;
        private final Command previous;

        RegistrationHandle(
                CommandMap map,
                Map<String, Command> known,
                String name,
                List<String> aliases,
                PluginCommand command,
                Command previous) {
            this.map = map;
            this.known = known;
            this.name = name;
            this.aliases = aliases;
            this.command = command;
            this.previous = previous;
        }

        void unbind() {
            removeIfOurs(name);
            if (aliases != null) {
                for (int i = 0; i < aliases.size(); i++) {
                    String alias = aliases.get(i);
                    if (alias != null && !alias.isEmpty()) {
                        removeIfOurs(alias.toLowerCase(Locale.ROOT));
                    }
                }
            }
            try {
                command.unregister(map);
            } catch (RuntimeException ignored) {
                // knownCommands edit is the part that yields the label.
            }
        }

        private void removeIfOurs(String label) {
            if (known.get(label) == command) {
                if (previous != null && label.equals(name)) {
                    known.put(label, previous);
                } else {
                    known.remove(label);
                }
            }
            known.remove(FALLBACK + ":" + label);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Command> knownCommands(CommandMap map, HeimdallLogger logger) {
        try {
            Method method = map.getClass().getMethod("getKnownCommands");
            Object value = method.invoke(map);
            if (value instanceof Map) {
                return (Map<String, Command>) value;
            }
        } catch (ReflectiveOperationException ignored) {
            // Paper exposes this; CraftBukkit 1.8 does not.
        }
        try {
            Field field = fieldNamed(map.getClass(), "knownCommands");
            if (field == null) {
                return null;
            }
            field.setAccessible(true);
            Object value = field.get(map);
            if (value instanceof Map) {
                return (Map<String, Command>) value;
            }
        } catch (ReflectiveOperationException e) {
            if (logger != null) {
                logger.warn("cannot read knownCommands: " + e);
            }
        }
        return null;
    }

    private static Field fieldNamed(Class<?> type, String name) {
        Class<?> cursor = type;
        while (cursor != null && cursor != Object.class) {
            try {
                return cursor.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                cursor = cursor.getSuperclass();
            }
        }
        return null;
    }
}
