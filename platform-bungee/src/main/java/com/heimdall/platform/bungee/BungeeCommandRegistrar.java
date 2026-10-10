package com.heimdall.platform.bungee;

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
import net.md_5.bungee.api.CommandSender;

/**
 * Binds a {@link CommandSpec} to the shell's relay for its name, and unbinds it again.
 *
 * <p>BungeeCord's {@code PluginManager} registers and unregisters at runtime by design, so aliases
 * really do work here and a disabled module's verb genuinely stops existing rather than merely
 * stopping answering. Since the hot-swap split (departure D87) the {@code Command} BungeeCord holds
 * is the shell's relay, and this class supplies the code behind it, so a swap replaces the code
 * without the command ever disappearing.
 *
 * <p>Permission is checked by the relay's {@code hasPermission}, from the binding's node. BungeeCord
 * uses that hook for tab completion and for its own "you do not have permission" reply as well as
 * for the gate, so a player without the node neither runs the command nor sees its suggestions.
 *
 * <h2>A name collision is taken over, loudly</h2>
 *
 * <p>{@code PluginManager.registerCommand} does not refuse a name that is already there: it takes it
 * over silently, and {@code unregisterCommand} does not hand it back. The shell keeps that rule from
 * before the split: a name taken from another plugin is warned about, and is deliberately left
 * bound on the way out, because a working verb that says the feature is disabled beats a module
 * toggle that makes a command vanish from the whole network. What changed is who holds it: the
 * relay is a shell object, so leaving it bound no longer pins a core.
 */
final class BungeeCommandRegistrar implements CommandRegistrar {

    private final CoreRegistrations registrations;
    private final HeimdallLogger logger;
    private final BungeeText text;

    BungeeCommandRegistrar(
            CoreRegistrations registrations, HeimdallLogger logger, BungeeText text) {
        this.registrations = registrations;
        this.logger = logger;
        this.text = text;
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

    private final class Target implements CommandTarget {

        private final CommandSpec spec;

        Target(CommandSpec spec) {
            this.spec = spec;
        }

        @Override
        public void execute(Object sender, String label, String[] args) {
            BungeeCommandSource source = new BungeeCommandSource((CommandSender) sender, text);
            try {
                spec.handler().execute(source, arguments(args));
            } catch (Throwable broken) {
                logger.error("/" + spec.name() + " failed for " + source.name(), broken);
                source.sendMessage(Msg.legacy("§cThat command failed. Check the proxy log."));
            }
        }

        @Override
        public List<String> complete(Object sender, String alias, String[] args) {
            if (spec.completer() == null) {
                return Collections.emptyList();
            }
            try {
                List<String> suggestions = spec.completer().complete(
                        new BungeeCommandSource((CommandSender) sender, text), arguments(args));
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
