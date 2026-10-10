package com.heimdall.platform.velocity;

import com.heimdall.core.command.CommandRegistrar;
import com.heimdall.core.command.CommandSpec;
import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.text.Msg;
import com.heimdall.core.util.Registration;
import com.heimdall.platform.common.CoreRegistrations;
import com.heimdall.shell.contract.CommandBinding;
import com.heimdall.shell.contract.CommandTarget;
import com.heimdall.shell.contract.ShellContext;
import com.velocitypowered.api.command.CommandSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Binds a {@link CommandSpec} to the shell's relay for its name, and unbinds it again.
 *
 * <p>Velocity's {@code CommandManager} registers and unregisters at runtime by design, so aliases
 * really do work here and a disabled module's verb genuinely stops existing rather than merely
 * stopping answering. Since the hot-swap split (departure D87) the {@code SimpleCommand} that
 * Velocity holds is the shell's relay, and this class supplies the code behind it. That is what
 * lets a swap replace the code without the command ever disappearing, and keeps the old
 * registrar's one leak from recurring: a command taken over from another plugin was left
 * registered on unbind, holding a core object, and would have pinned that core forever.
 *
 * <p>Permission is checked by the relay's {@code hasPermission}, from the binding's node. Velocity
 * uses that hook for the command's visibility as well as its gate, so a player without the node
 * neither runs it nor sees it in their client's completion list.
 */
final class VelocityCommandRegistrar implements CommandRegistrar {

    private final CoreRegistrations registrations;
    private final HeimdallLogger logger;
    private final VelocityText text;

    VelocityCommandRegistrar(
            CoreRegistrations registrations, HeimdallLogger logger, VelocityText text) {
        this.registrations = registrations;
        this.logger = logger;
        this.text = text;
    }

    @Override
    public Registration register(CommandSpec spec) {
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
            VelocityCommandSource source = new VelocityCommandSource((CommandSource) sender, text);
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
                        new VelocityCommandSource((CommandSource) sender, text), arguments(args));
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
