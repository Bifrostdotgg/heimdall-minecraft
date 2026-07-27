package com.heimdall.platform.velocity;

import com.heimdall.core.command.CommandRegistrar;
import com.heimdall.core.command.CommandSpec;
import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.text.Msg;
import com.heimdall.core.util.Registration;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.SimpleCommand;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Registers a {@link CommandSpec} with the proxy, and unregisters it again.
 *
 * <p>The straightforward half of the pair. Velocity's {@code CommandManager} registers and
 * unregisters at runtime by design, so aliases really do work here and a disabled module's verb
 * genuinely stops existing rather than merely stopping answering — which is the one place the two
 * platforms differ, and why {@link com.heimdall.core.command.CommandSpec} says aliases are
 * advertised rather than guaranteed.
 *
 * <p>Permission is checked in {@link SimpleCommand#hasPermission}, not in {@code execute}. Velocity
 * uses that hook for the command's visibility as well as its gate, so a player without the node
 * neither runs it nor sees it in their client's completion list.
 */
final class VelocityCommandRegistrar implements CommandRegistrar {

    private final CommandManager manager;
    private final HeimdallLogger logger;
    private final VelocityText text;

    VelocityCommandRegistrar(CommandManager manager, HeimdallLogger logger, VelocityText text) {
        this.manager = manager;
        this.logger = logger;
        this.text = text;
    }

    @Override
    public Registration register(CommandSpec spec) {
        if (spec == null) {
            throw new IllegalArgumentException("spec is required");
        }
        List<String> aliases = new ArrayList<String>();
        for (String alias : spec.aliases()) {
            if (alias != null && !alias.trim().isEmpty()) {
                aliases.add(alias.trim().toLowerCase(Locale.ROOT));
            }
        }
        final CommandMeta meta = manager.metaBuilder(spec.name())
                .aliases(aliases.toArray(new String[0]))
                .build();
        try {
            manager.register(meta, new Bridge(spec));
        } catch (RuntimeException refused) {
            // Another plugin already owns the name. Not fatal — the module still works through
            // whatever else it registered — but silent otherwise, and "why does /link do something
            // else on the proxy" has no other answer in any log.
            logger.warn("the proxy refused to register /" + spec.name()
                    + " (another plugin probably owns it): " + refused);
            return Registration.NONE;
        }
        return Registration.once(new Runnable() {
            @Override
            public void run() {
                try {
                    manager.unregister(meta);
                } catch (RuntimeException alreadyGone) {
                    logger.debug(() -> "unregistering a command failed: " + alreadyGone);
                }
            }
        });
    }

    /** One command, bridged onto the proxy's simplest command shape. */
    private final class Bridge implements SimpleCommand {

        private final CommandSpec spec;

        Bridge(CommandSpec spec) {
            this.spec = spec;
        }

        @Override
        public boolean hasPermission(Invocation invocation) {
            return spec.permission().isEmpty() || invocation.source().hasPermission(spec.permission());
        }

        @Override
        public void execute(Invocation invocation) {
            VelocityCommandSource source = new VelocityCommandSource(invocation.source(), text);
            try {
                spec.handler().execute(source, arguments(invocation));
            } catch (Throwable broken) {
                // Throwable, for the reason departure D44 exists: the failures worth being careful
                // about on this platform are NoSuchMethodError from the reflective text bridge, and
                // an Error escaping here is swallowed by Velocity with nothing shown to anybody.
                logger.error("/" + spec.name() + " failed for " + source.name(), broken);
                source.sendMessage(Msg.legacy("§cThat command failed. Check the proxy log."));
            }
        }

        @Override
        public List<String> suggest(Invocation invocation) {
            if (spec.completer() == null) {
                return Collections.emptyList();
            }
            try {
                List<String> suggestions = spec.completer().complete(
                        new VelocityCommandSource(invocation.source(), text), arguments(invocation));
                return suggestions == null
                        ? Collections.<String>emptyList()
                        : new ArrayList<String>(suggestions);
            } catch (Throwable broken) {
                logger.debug(() -> "tab completion for /" + spec.name() + " failed: " + broken);
                return Collections.emptyList();
            }
        }

        private List<String> arguments(Invocation invocation) {
            String[] args = invocation.arguments();
            return args == null
                    ? Collections.<String>emptyList()
                    : Collections.unmodifiableList(Arrays.asList(args));
        }
    }
}
