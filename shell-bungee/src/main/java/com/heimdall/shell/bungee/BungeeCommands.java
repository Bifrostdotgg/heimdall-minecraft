package com.heimdall.shell.bungee;

import com.heimdall.shell.hotswap.CommandPlatform;
import com.heimdall.shell.hotswap.Relay;
import com.heimdall.shell.hotswap.ShellLog;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.api.plugin.TabExecutor;

/**
 * Puts shell relays on BungeeCord's plugin manager.
 *
 * <p>The same takeover rule as on Velocity: a name another plugin owns is taken with a warning and
 * never unregistered, because there is no way to hand it back.
 */
final class BungeeCommands implements CommandPlatform {

    private final Plugin plugin;
    private final ProxyServer proxy;
    private final ShellLog log;

    BungeeCommands(Plugin plugin, ProxyServer proxy, ShellLog log) {
        this.plugin = plugin;
        this.proxy = proxy;
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
        boolean takingOver = ownedByAnother(name, aliases);
        if (takingOver) {
            log.warn("/" + name + " is already registered on this proxy; Heimdall is taking the "
                    + "name over, and whatever owned it will stop responding. It will NOT be "
                    + "handed back when this module is disabled; restart the proxy for that.");
        }
        final Command command = new RelayCommand(name, aliases, relay);
        try {
            proxy.getPluginManager().registerCommand(plugin, command);
        } catch (RuntimeException refused) {
            log.warn("the proxy refused to register /" + name + ": " + refused);
            return null;
        }
        final List<String> registered = Collections.unmodifiableList(new ArrayList<String>(aliases));
        final boolean permanent = takingOver;
        return new Handle() {
            @Override
            public void unregister() {
                if (permanent) {
                    return;
                }
                try {
                    proxy.getPluginManager().unregisterCommand(command);
                } catch (RuntimeException alreadyGone) {
                    log.debug("unregistering a command failed: " + alreadyGone);
                }
            }

            @Override
            public boolean permanent() {
                return permanent;
            }

            @Override
            public List<String> aliases() {
                return registered;
            }
        };
    }

    private boolean ownedByAnother(String name, List<String> aliases) {
        try {
            for (Map.Entry<String, Command> registered : proxy.getPluginManager().getCommands()) {
                String key = registered.getKey();
                if (key == null) {
                    continue;
                }
                String normalised = key.toLowerCase(Locale.ROOT);
                if (normalised.equals(name) || aliases.contains(normalised)) {
                    return true;
                }
            }
        } catch (RuntimeException unreadable) {
            log.debug("could not read the proxy's command map: " + unreadable);
        }
        return false;
    }

    /**
     * A relay as a BungeeCord command.
     *
     * <p>No permission is passed to the {@code Command} constructor: BungeeCord fixes that field at
     * construction, and the permission that applies changes with whichever core is bound. The
     * {@link #hasPermission} override asks the relay instead.
     */
    private static final class RelayCommand extends Command implements TabExecutor {

        private final Relay relay;

        RelayCommand(String name, List<String> aliases, Relay relay) {
            super(name, null, aliases.toArray(new String[0]));
            this.relay = relay;
        }

        @Override
        public boolean hasPermission(CommandSender sender) {
            return relay.visibleTo(sender);
        }

        @Override
        public void execute(CommandSender sender, String[] args) {
            relay.execute(sender, getName(), args);
        }

        @Override
        public Iterable<String> onTabComplete(CommandSender sender, String[] args) {
            if (!hasPermission(sender)) {
                return Collections.emptyList();
            }
            List<String> suggestions = relay.complete(sender, getName(), args);
            return suggestions == null
                    ? Collections.<String>emptyList()
                    : new ArrayList<String>(suggestions);
        }
    }
}
