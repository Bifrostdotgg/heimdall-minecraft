package com.heimdall.platform.bukkit;

import com.heimdall.core.command.CommandRegistrar;
import com.heimdall.core.command.CommandSpec;
import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.text.Msg;
import com.heimdall.core.util.Registration;
import com.heimdall.platform.common.CoreRegistrations;
import com.heimdall.shell.contract.CommandBinding;
import com.heimdall.shell.contract.CommandTarget;
import com.heimdall.shell.contract.ShellContext;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.bukkit.command.CommandSender;

/**
 * Binds a {@link CommandSpec} to the shell's relay for its name, and unbinds it again.
 *
 * <h2>The shell owns the Bukkit command now</h2>
 *
 * <p>Before the hot-swap split this class set its own executor on the {@code plugin.yml} command
 * and, for names that are not in the descriptor (the punishments root aliases), put a
 * {@code PluginCommand} on the command map itself. Both are now the shell's job (departure D87):
 * Bukkit never lets a descriptor command go, so whatever it holds as the executor must outlive every
 * core, and a runtime {@code PluginCommand} left in the map across a swap must not be a core object
 * either. The shell installs a relay on every descriptor command at enable and creates the runtime
 * ones on first bind; this class supplies the code behind them, as a {@link CommandTarget}.
 *
 * <p>Everything a player experiences is unchanged: the same permission gate, the same containment
 * of a handler that throws, the same "switched off" answer for a descriptor command whose module
 * is off (now the shell's, because it has to be said with no core code behind the command), and a
 * runtime name still leaves the map when its module does, so LiteBans can keep {@code /ban}.
 *
 * <p>Thread-safe: binding is the shell's, and the rest is stateless.
 */
final class BukkitCommandRegistrar implements CommandRegistrar {

    private final CoreRegistrations registrations;
    private final HeimdallLogger logger;
    private final BukkitMessenger messenger;

    BukkitCommandRegistrar(
            CoreRegistrations registrations, HeimdallLogger logger, BukkitMessenger messenger) {
        this.registrations = registrations;
        this.logger = logger;
        this.messenger = messenger;
    }

    @Override
    public Registration register(final CommandSpec spec) {
        if (spec == null) {
            throw new IllegalArgumentException("spec is required");
        }
        ShellContext shell = registrations.context();
        if (shell == null) {
            logger.warn("no shell to bind /" + spec.name() + " through; it will not exist");
            return Registration.NONE;
        }
        CommandBinding binding = CommandBinding.named(spec.name())
                .aliases(spec.aliases())
                .permission(spec.permission())
                .description(spec.description())
                .usage(spec.usage())
                .target(new Target(spec))
                .build();
        return registrations.keep(shell.bindCommand(binding));
    }

    /** One command's executor and completer. Both halves gate on the same permission. */
    private final class Target implements CommandTarget {

        private final CommandSpec spec;

        Target(CommandSpec spec) {
            this.spec = spec;
        }

        @Override
        public void execute(Object sender, String label, String[] args) {
            CommandSender bukkit = (CommandSender) sender;
            BukkitCommandSource source = new BukkitCommandSource(bukkit, messenger);
            if (!source.hasPermission(spec.permission())) {
                messenger.send(bukkit, Msg.legacy("§cYou do not have permission to use that."));
                return;
            }
            try {
                spec.handler().execute(source, Collections.unmodifiableList(Arrays.asList(args)));
            } catch (Throwable broken) {
                // Throwable, for the same reason BukkitLoginListener catches one: the failures this
                // binding exists to be careful about are NoSuchMethodError and friends from an API
                // that moved between server versions, and those are Errors. Left to Bukkit, any of
                // them prints a stack trace at the player.
                logger.error("/" + label + " failed for " + bukkit.getName(), broken);
                messenger.send(bukkit, Msg.legacy("§cThat command failed. Check the server log."));
            }
        }

        @Override
        public List<String> complete(Object sender, String alias, String[] args) {
            if (spec.completer() == null) {
                return null;
            }
            BukkitCommandSource source = new BukkitCommandSource((CommandSender) sender, messenger);
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
