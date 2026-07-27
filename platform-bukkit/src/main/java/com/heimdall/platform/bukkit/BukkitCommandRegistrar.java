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

        final Bridge bridge = new Bridge(spec);
        command.setExecutor(bridge);
        command.setTabCompleter(bridge);

        // Read back, rather than assumed. setExecutor has no return value and no failure mode of its
        // own, but the binding is a mutable field on an object the whole server can reach: another
        // plugin doing the same thing to the same PluginCommand — a command-manager plugin, or one
        // that "fixes" conflicts by re-pointing them — silently wins, and every symptom afterwards
        // is Heimdall's command doing somebody else's thing with no line anywhere to explain it.
        if (command.getExecutor() != bridge) {
            logger.warn("something else claimed /" + spec.name() + " immediately after Heimdall "
                    + "registered it (now " + describe(command.getExecutor()) + ") — that command "
                    + "will not reach Heimdall. A command-manager plugin is the usual cause.");
        }

        return Registration.once(new Runnable() {
            @Override
            public void run() {
                // Only if it is still ours. Unbinding a command another plugin has since taken over
                // would break THEIR command as a side effect of disabling one of our modules, which
                // is a far worse failure than leaving a verb bound to a module that is off — and
                // that case is handled: the bridge answers "this feature is disabled".
                if (command.getExecutor() == bridge) {
                    // Swapped for a stub rather than cleared. setExecutor(null) makes Bukkit fall
                    // back to the plugin's own onCommand, which returns false, which prints the
                    // descriptor's usage line — so a player who runs /offend while the module is off
                    // is told the argument syntax for a command that will not do anything. Telling
                    // them the feature is disabled is the answer to the question they asked.
                    //
                    // The completer is replaced rather than cleared for the same reason it returns
                    // empty on a permission failure: a null completer falls through to Bukkit's own
                    // online-player completion, so a disabled command would still be quietly
                    // listing who is on the server.
                    Disabled disabled = new Disabled(spec.name());
                    command.setExecutor(disabled);
                    command.setTabCompleter(disabled);
                }
            }
        });
    }

    /**
     * What a command answers while the module that owns it is switched off.
     *
     * <p>Bukkit cannot remove a {@code plugin.yml} command at runtime, so the verb exists whatever
     * the dashboard says. The honest thing for it to do is say which of the three possible reasons
     * applies — and "this feature is disabled" is the only one an operator can act on, because the
     * other two (no permission, wrong arguments) are already answered elsewhere.
     */
    private final class Disabled implements CommandExecutor, TabCompleter {

        private final String name;

        Disabled(String name) {
            this.name = name;
        }

        @Override
        public boolean onCommand(
                CommandSender sender, Command command, String label, String[] args) {
            messenger.send(sender, Msg.legacy(
                    "§cThat feature is switched off. Enable §f" + name
                            + "§c on the Minecraft page of the Heimdall dashboard."));
            return true;
        }

        @Override
        public List<String> onTabComplete(
                CommandSender sender, Command command, String alias, String[] args) {
            // Empty, not null: null falls through to Bukkit's own player-name completion, and a
            // switched-off command has no business advertising who is online.
            return Collections.emptyList();
        }
    }

    /** Names whatever currently owns a command, for the collision warning. */
    private static String describe(Object executor) {
        if (executor == null) {
            return "unbound";
        }
        return executor.getClass().getName();
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
