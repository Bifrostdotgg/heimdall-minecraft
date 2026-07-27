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
        // Checked BEFORE registering, because Velocity does not refuse a collision — it takes the
        // name over silently. So the failure without this is not "Heimdall's command did not work":
        // it is another plugin's command quietly ceasing to exist the moment Heimdall loads, and
        // then reappearing when a module is toggled off. Nothing in any log would connect the two.
        //
        // Registered anyway, deliberately: on a proxy Heimdall's /linkdiscord is far more likely to
        // be the one the operator wants than whatever also claimed it, and refusing would leave a
        // module with no command and no way to get one. What changes is that it is now said out
        // loud, and that the takeover is not silently reversed on the way out.
        boolean takingOver = ownedByAnother(spec.name(), aliases);
        if (takingOver) {
            logger.warn("/" + spec.name() + " is already registered on this proxy — Heimdall is "
                    + "taking the name over, and whatever owned it will stop responding. It will "
                    + "NOT be handed back when this module is disabled; restart the proxy for that.");
        }

        final CommandMeta meta = manager.metaBuilder(spec.name())
                .aliases(aliases.toArray(new String[0]))
                .build();
        try {
            manager.register(meta, new Bridge(spec));
        } catch (RuntimeException refused) {
            logger.warn("the proxy refused to register /" + spec.name()
                    + " (another plugin probably owns it): " + refused);
            return Registration.NONE;
        }

        final boolean unregisterOnClose = !takingOver;
        return Registration.once(new Runnable() {
            @Override
            public void run() {
                if (!unregisterOnClose) {
                    // Unregistering a name we took from somebody else does not give it back — the
                    // previous owner's registration was replaced, not stacked — it just deletes the
                    // command outright. Leaving ours bound at least keeps a working verb that says
                    // the feature is disabled, rather than turning a toggle into a command that
                    // vanishes from the whole network.
                    logger.debug(() -> "leaving /" + spec.name() + " registered: Heimdall took the "
                            + "name from another plugin and unregistering would delete it entirely");
                    return;
                }
                try {
                    manager.unregister(meta);
                } catch (RuntimeException alreadyGone) {
                    logger.debug(() -> "unregistering a command failed: " + alreadyGone);
                }
            }
        });
    }

    /** Whether the proxy already has any of these names bound to something. */
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
