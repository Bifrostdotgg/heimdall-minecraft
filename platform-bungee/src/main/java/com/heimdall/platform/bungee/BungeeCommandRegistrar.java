package com.heimdall.platform.bungee;

import com.heimdall.core.command.CommandRegistrar;
import com.heimdall.core.command.CommandSpec;
import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.text.Msg;
import com.heimdall.core.util.Registration;
import java.util.ArrayList;
import java.util.Arrays;
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
 * Registers a {@link CommandSpec} with the proxy, and unregisters it again.
 *
 * <p>The straightforward half of the pair, and almost exactly the Velocity registrar: BungeeCord's
 * {@code PluginManager} registers and unregisters at runtime by design, so aliases really do work
 * here and a disabled module's verb genuinely stops existing rather than merely stopping answering.
 *
 * <p>Permission is checked in {@link Command#hasPermission}, not in {@code execute}. BungeeCord uses
 * that hook for tab completion and for its own "you do not have permission" reply as well as for the
 * gate, so a player without the node neither runs the command nor sees its suggestions.
 *
 * <h2>A name collision is taken over, loudly</h2>
 *
 * <p>{@code PluginManager.registerCommand} puts the name into a map. It does not refuse a name that
 * is already there — it takes it over silently. So the failure without the warning below is not
 * "Heimdall's command did not work": it is another plugin's command quietly ceasing to exist the
 * moment Heimdall loads, and reappearing when a module is toggled off, with nothing in any log to
 * connect the two.
 *
 * <p>And unregistering does not hand it back. {@code unregisterCommand} removes every map entry whose
 * value is that {@code Command} object, so the previous owner's registration — which was replaced,
 * not stacked — stays gone. Which is why a name taken from somebody else is deliberately left bound
 * on the way out, exactly as on Velocity: leaving a working verb that says the feature is disabled
 * beats turning a module toggle into a command that vanishes from the whole network.
 */
final class BungeeCommandRegistrar implements CommandRegistrar {

    private final Plugin plugin;
    private final ProxyServer proxy;
    private final HeimdallLogger logger;
    private final BungeeText text;

    BungeeCommandRegistrar(
            Plugin plugin, ProxyServer proxy, HeimdallLogger logger, BungeeText text) {
        this.plugin = plugin;
        this.proxy = proxy;
        this.logger = logger;
        this.text = text;
    }

    @Override
    public Registration register(final CommandSpec spec) {
        if (spec == null) {
            throw new IllegalArgumentException("spec is required");
        }
        List<String> aliases = new ArrayList<String>();
        for (String alias : spec.aliases()) {
            if (alias != null && !alias.trim().isEmpty()) {
                aliases.add(alias.trim().toLowerCase(Locale.ROOT));
            }
        }

        // Checked BEFORE registering — see the class javadoc for what the silent version costs.
        boolean takingOver = ownedByAnother(spec.name(), aliases);
        if (takingOver) {
            logger.warn("/" + spec.name() + " is already registered on this proxy — Heimdall is "
                    + "taking the name over, and whatever owned it will stop responding. It will "
                    + "NOT be handed back when this module is disabled; restart the proxy for that.");
        }

        final Command command = new Bridge(spec, aliases);
        try {
            proxy.getPluginManager().registerCommand(plugin, command);
        } catch (RuntimeException refused) {
            logger.warn("the proxy refused to register /" + spec.name() + ": " + refused);
            return Registration.NONE;
        }

        final boolean unregisterOnClose = !takingOver;
        return Registration.once(new Runnable() {
            @Override
            public void run() {
                if (!unregisterOnClose) {
                    logger.debug(() -> "leaving /" + spec.name() + " registered: Heimdall took the "
                            + "name from another plugin and unregistering would delete it entirely");
                    return;
                }
                try {
                    proxy.getPluginManager().unregisterCommand(command);
                } catch (RuntimeException alreadyGone) {
                    logger.debug(() -> "unregistering a command failed: " + alreadyGone);
                }
            }
        });
    }

    /**
     * Whether the proxy already has any of these names bound to something.
     *
     * <p>BungeeCord has no {@code hasCommand}: {@code getCommands()} returns the map's entries, and
     * the keys are already lower-cased by {@code registerCommand}. {@code isExecutableCommand} is the
     * near miss to avoid — it also consults the disabled-commands list and the sender's permissions,
     * so it answers "no" for a name that is very much taken.
     */
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
            // Only the warning is lost, and the registration below still happens. A collision check
            // that failed a module's enable would be worse than one that stayed quiet.
            logger.debug(() -> "could not read the proxy's command map: " + unreadable);
        }
        return false;
    }

    /** One command, bridged onto BungeeCord's own shape. */
    private final class Bridge extends Command implements TabExecutor {

        private final CommandSpec spec;

        Bridge(CommandSpec spec, List<String> aliases) {
            // (name, permission, aliases). The permission argument is what Command.hasPermission
            // reads by default; it is overridden below anyway, so this is the descriptive half —
            // BungeeCord also uses it for its own permission-denied message.
            super(spec.name(), spec.permission().isEmpty() ? null : spec.permission(),
                    aliases.toArray(new String[0]));
            this.spec = spec;
        }

        @Override
        public boolean hasPermission(CommandSender sender) {
            return spec.permission().isEmpty() || sender.hasPermission(spec.permission());
        }

        @Override
        public void execute(CommandSender sender, String[] args) {
            BungeeCommandSource source = new BungeeCommandSource(sender, text);
            try {
                spec.handler().execute(source, arguments(args));
            } catch (Throwable broken) {
                // Throwable rather than RuntimeException: the failures worth being careful about on a
                // platform whose API spans a decade are NoSuchMethodError and friends, and an Error
                // escaping here is logged by BungeeCord with nothing shown to whoever typed it.
                logger.error("/" + spec.name() + " failed for " + source.name(), broken);
                source.sendMessage(Msg.legacy("§cThat command failed. Check the proxy log."));
            }
        }

        @Override
        public Iterable<String> onTabComplete(CommandSender sender, String[] args) {
            if (spec.completer() == null) {
                return Collections.emptyList();
            }
            if (!hasPermission(sender)) {
                // BungeeCord routes tab completion through the Command object without consulting
                // hasPermission first, unlike execution — so without this a player who cannot run
                // the command would still be told what its arguments are.
                return Collections.emptyList();
            }
            try {
                List<String> suggestions = spec.completer()
                        .complete(new BungeeCommandSource(sender, text), arguments(args));
                return suggestions == null
                        ? Collections.<String>emptyList()
                        : new ArrayList<String>(suggestions);
            } catch (Throwable broken) {
                logger.debug(() -> "tab completion for /" + spec.name() + " failed: " + broken);
                return Collections.emptyList();
            }
        }

        private List<String> arguments(String[] args) {
            return args == null
                    ? Collections.<String>emptyList()
                    : Collections.unmodifiableList(Arrays.asList(args));
        }
    }
}
