package com.heimdall.shell.bukkit;

import com.heimdall.shell.hotswap.CommandPlatform;
import com.heimdall.shell.hotswap.Relay;
import com.heimdall.shell.hotswap.ShellLog;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.Plugin;

/**
 * Puts shell relays on the Bukkit command map.
 *
 * <p>A name declared in {@code plugin.yml} gets the relay as its executor and tab completer, for
 * good: Bukkit fixes a descriptor command's existence at load time and never takes it away. Any
 * other name (the punishments root aliases) is put on the map at runtime by
 * {@link BukkitCommandMap} and taken off again, so another plugin can reclaim it.
 */
final class BukkitCommands implements CommandPlatform {

    private final Plugin plugin;
    private final ShellLog log;

    BukkitCommands(Plugin plugin, ShellLog log) {
        this.plugin = plugin;
        this.log = log;
    }

    @Override
    public Handle register(
            String name,
            List<String> aliases,
            String permission,
            String description,
            String usage,
            Relay relay) {
        PluginCommand declared = plugin.getServer().getPluginCommand(name);
        if (declared != null && declared.getPlugin() == plugin) {
            return bindDescriptor(declared, relay);
        }
        return bindDynamic(name, aliases, permission, description, usage, relay);
    }

    private Handle bindDescriptor(PluginCommand command, Relay relay) {
        Executor executor = new Executor(relay);
        command.setExecutor(executor);
        command.setTabCompleter(executor);
        if (command.getExecutor() != executor) {
            log.warn("something else claimed /" + command.getName() + " immediately after Heimdall "
                    + "registered it; that command will not reach Heimdall. A command-manager "
                    + "plugin is the usual cause.");
        }
        List<String> declaredAliases = new ArrayList<String>();
        for (String alias : command.getAliases()) {
            declaredAliases.add(alias.toLowerCase(Locale.ROOT));
        }
        return new FixedHandle(declaredAliases, true, null);
    }

    private Handle bindDynamic(
            String name,
            List<String> aliases,
            String permission,
            String description,
            String usage,
            Relay relay) {
        PluginCommand command = BukkitCommandMap.create(plugin, name, log);
        if (command == null) {
            log.warn("cannot register /" + name + " at runtime on this server");
            return null;
        }
        command.setDescription(description == null ? "" : description);
        command.setUsage(usage == null ? "/" + name : usage);
        command.setPermission(permission == null || permission.isEmpty() ? null : permission);
        command.setAliases(new ArrayList<String>(aliases));
        Executor executor = new Executor(relay);
        command.setExecutor(executor);
        command.setTabCompleter(executor);
        BukkitCommandMap.RegistrationHandle handle =
                BukkitCommandMap.bind(plugin, command, aliases, log);
        if (handle == null) {
            log.warn("the command map refused /" + name
                    + "; the verb will not exist until the server exposes SimpleCommandMap");
            return null;
        }
        return new FixedHandle(new ArrayList<String>(aliases), false, handle);
    }

    /** A relay as Bukkit's executor and completer. Always answers {@code true}. */
    private static final class Executor implements CommandExecutor, TabCompleter {

        private final Relay relay;

        Executor(Relay relay) {
            this.relay = relay;
        }

        @Override
        public boolean onCommand(
                CommandSender sender, Command command, String label, String[] args) {
            relay.execute(sender, label, args);
            // Always true: false makes Bukkit print the descriptor's usage line, which is never the
            // right answer once something has already replied.
            return true;
        }

        @Override
        public List<String> onTabComplete(
                CommandSender sender, Command command, String alias, String[] args) {
            List<String> suggestions = relay.complete(sender, alias, args);
            return suggestions == null ? null : new ArrayList<String>(suggestions);
        }
    }

    private static final class FixedHandle implements Handle {

        private final List<String> aliases;
        private final boolean permanent;
        private final BukkitCommandMap.RegistrationHandle map;

        FixedHandle(List<String> aliases, boolean permanent, BukkitCommandMap.RegistrationHandle map) {
            this.aliases = Collections.unmodifiableList(aliases);
            this.permanent = permanent;
            this.map = map;
        }

        @Override
        public void unregister() {
            if (map != null) {
                map.unbind();
            }
        }

        @Override
        public boolean permanent() {
            return permanent;
        }

        @Override
        public List<String> aliases() {
            return aliases;
        }
    }
}
