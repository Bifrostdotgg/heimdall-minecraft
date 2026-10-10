package com.heimdall.shell.velocity;

import com.heimdall.shell.hotswap.CommandPlatform;
import com.heimdall.shell.hotswap.Relay;
import com.heimdall.shell.hotswap.ShellLog;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.SimpleCommand;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Puts shell relays on Velocity's command manager.
 *
 * <p>A name another plugin already owns is taken over with a warning, as before the split, and is
 * then never unregistered: Velocity has no way to hand it back, and unregistering would delete the
 * name outright. That handle reports itself permanent, so neither a module switching off nor a swap
 * ever removes it.
 */
final class VelocityCommands implements CommandPlatform {

    private final CommandManager manager;
    private final ShellLog log;

    VelocityCommands(CommandManager manager, ShellLog log) {
        this.manager = manager;
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
        final CommandMeta meta = manager.metaBuilder(name)
                .aliases(aliases.toArray(new String[0]))
                .build();
        try {
            manager.register(meta, new RelayCommand(relay));
        } catch (RuntimeException refused) {
            log.warn("the proxy refused to register /" + name + " (another plugin probably owns "
                    + "it): " + refused);
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
                    manager.unregister(meta);
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
        if (manager.hasCommand(name)) {
            return true;
        }
        for (String alias : aliases) {
            if (manager.hasCommand(alias)) {
                return true;
            }
        }
        return false;
    }

    /** A relay as a Velocity command. */
    private static final class RelayCommand implements SimpleCommand {

        private final Relay relay;

        RelayCommand(Relay relay) {
            this.relay = relay;
        }

        @Override
        public boolean hasPermission(Invocation invocation) {
            return relay.visibleTo(invocation.source());
        }

        @Override
        public void execute(Invocation invocation) {
            relay.execute(invocation.source(), invocation.alias(), invocation.arguments());
        }

        @Override
        public List<String> suggest(Invocation invocation) {
            List<String> suggestions =
                    relay.complete(invocation.source(), invocation.alias(), invocation.arguments());
            return suggestions == null
                    ? Collections.<String>emptyList()
                    : new ArrayList<String>(suggestions);
        }
    }
}
