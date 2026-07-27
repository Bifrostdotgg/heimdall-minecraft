package com.heimdall.platform.bukkit;

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
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.Plugin;

/**
 * Binds a {@link CommandSpec} to a {@code plugin.yml} command, and unbinds it again.
 *
 * <h2>Why the name has to be in plugin.yml</h2>
 *
 * <p>Bukkit's command map can be reached reflectively and a command really can be registered at
 * runtime — v2 did not do it, and neither does this. The map's shape has changed across the decade
 * of servers this plugin supports, the reflective path is invisible to the conformance rules, and
 * the failure mode is a command that silently does not exist on one server generation. Declaring the
 * names in the descriptor costs one edit per new verb and works identically from 1.8.8 to current.
 *
 * <p>So a spec whose name the descriptor does not carry gets a warning and {@link Registration#NONE}
 * — never an exception. A module that fails to enable because a {@code plugin.yml} line was missed
 * would take its whole feature down over a typo in a resource file.
 *
 * <h2>Unbinding</h2>
 *
 * <p>Closing the handle puts the command's executor back to the plugin itself, which is what Bukkit
 * uses when {@code setExecutor(null)} is called and produces the descriptor's own usage message.
 * The verb still exists — nothing can remove it — but it stops reaching a module that has been
 * switched off, which is the property departure D30 is about.
 *
 * <p>Thread-safe: Bukkit's {@code PluginCommand} setters are, and the rest is stateless.
 */
final class BukkitCommandRegistrar implements CommandRegistrar {

    private final Plugin plugin;
    private final HeimdallLogger logger;
    private final BukkitMessenger messenger;

    BukkitCommandRegistrar(Plugin plugin, HeimdallLogger logger, BukkitMessenger messenger) {
        this.plugin = plugin;
        this.logger = logger;
        this.messenger = messenger;
    }

    @Override
    public Registration register(final CommandSpec spec) {
        if (spec == null) {
            throw new IllegalArgumentException("spec is required");
        }
        final PluginCommand command = plugin.getServer().getPluginCommand(spec.name());
        if (command == null || command.getPlugin() != plugin) {
            logger.warn("plugin.yml declares no '" + spec.name() + "' command, so it will answer "
                    + "\"Unknown command\" however the module is configured. Add it to the "
                    + "descriptor.");
            return Registration.NONE;
        }
        warnAboutUndeclaredAliases(spec, command);

        Bridge bridge = new Bridge(spec);
        command.setExecutor(bridge);
        command.setTabCompleter(bridge);
        return Registration.once(new Runnable() {
            @Override
            public void run() {
                // Only if it is still ours. A second module registering the same verb would
                // otherwise have its binding torn out by the first one's disable.
                if (command.getExecutor() == bridge) {
                    command.setExecutor(null);
                    command.setTabCompleter(null);
                }
            }
        });
    }

    /**
     * Says so when the spec and the descriptor disagree about aliases.
     *
     * <p>Not an error — the command still works under its primary name — but a silent difference
     * between the two platforms is exactly the kind of thing that turns into "it works on my proxy".
     */
    private void warnAboutUndeclaredAliases(CommandSpec spec, PluginCommand command) {
        if (spec.aliases().isEmpty()) {
            return;
        }
        List<String> declared = new ArrayList<String>();
        for (String alias : command.getAliases()) {
            declared.add(alias.toLowerCase(Locale.ROOT));
        }
        List<String> missing = new ArrayList<String>();
        for (String alias : spec.aliases()) {
            String normalised = alias == null ? "" : alias.trim().toLowerCase(Locale.ROOT);
            if (!normalised.isEmpty() && !declared.contains(normalised)) {
                missing.add(normalised);
            }
        }
        if (!missing.isEmpty()) {
            logger.warn("plugin.yml does not declare alias(es) " + missing + " for /" + spec.name()
                    + " — Bukkit fixes a command's aliases at load time, so they will not work "
                    + "here even though they do on the proxy");
        }
    }

    /** One command's executor and completer. Both halves gate on the same permission. */
    private final class Bridge implements CommandExecutor, TabCompleter {

        private final CommandSpec spec;

        Bridge(CommandSpec spec) {
            this.spec = spec;
        }

        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            BukkitCommandSource source = new BukkitCommandSource(sender, messenger);
            if (!source.hasPermission(spec.permission())) {
                messenger.send(sender, Msg.legacy("§cYou do not have permission to use that."));
                return true;
            }
            try {
                spec.handler().execute(source, Collections.unmodifiableList(Arrays.asList(args)));
            } catch (Throwable broken) {
                // Throwable, for the same reason BukkitLoginListener catches one: the failures this
                // binding exists to be careful about are NoSuchMethodError and friends from an API
                // that moved between server versions, and those are Errors. Left to Bukkit, any of
                // them prints a stack trace at the player.
                logger.error("/" + label + " failed for " + sender.getName(), broken);
                messenger.send(sender, Msg.legacy("§cThat command failed. Check the server log."));
            }
            // Always true: returning false makes Bukkit print the descriptor's usage line, which is
            // never the right answer once the handler has already replied.
            return true;
        }

        @Override
        public List<String> onTabComplete(
                CommandSender sender, Command command, String alias, String[] args) {
            if (spec.completer() == null) {
                return null;
            }
            BukkitCommandSource source = new BukkitCommandSource(sender, messenger);
            if (!source.hasPermission(spec.permission())) {
                // Empty rather than null: null falls through to Bukkit's own online-player
                // completion, so a player with no permission would still be told who is on.
                return Collections.emptyList();
            }
            try {
                List<String> suggestions = spec.completer()
                        .complete(source, Collections.unmodifiableList(Arrays.asList(args)));
                return suggestions == null ? null : new ArrayList<String>(suggestions);
            } catch (Throwable broken) {
                logger.debug(() -> "tab completion for /" + alias + " failed: " + broken);
                return Collections.emptyList();
            }
        }
    }
}
