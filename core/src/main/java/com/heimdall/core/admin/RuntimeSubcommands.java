package com.heimdall.core.admin;

import com.heimdall.core.command.CommandSource;
import com.heimdall.core.config.BootstrapConfig;
import com.heimdall.core.module.ModuleManager;
import com.heimdall.core.module.ModuleState;
import com.heimdall.core.text.Msg;
import com.heimdall.core.wiring.HeimdallRuntime;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The three verbs about the plugin itself: {@code reload}, {@code modules}, {@code debug}.
 *
 * <p>Grouped in one file because they are one concern — "what is this plugin doing and make it do it
 * again" — and each is fifty lines. Splitting them into three files would be three class javadocs
 * saying the same thing.
 */
final class RuntimeSubcommands {

    private RuntimeSubcommands() {
    }

    /**
     * {@code /hd reload} — re-read configuration and rebuild the tunnel <em>in place</em>.
     *
     * <h2>In place is the whole contract, and it is v2's bug written as a requirement</h2>
     *
     * <p>v2's reload constructed a new WebSocket client. The old one's private scheduler and its
     * selector thread were never stopped, and the message-handler wiring was silently not carried
     * over — so a server reloaded three times had three half-live sockets, no role sync, and a
     * plugin that looked fine. v2 then papered over the resulting reconnect storm by destroying the
     * scheduler on disconnect, which is where its "no live scheduler" rebuild branches came from.
     *
     * <p>{@link HeimdallRuntime#reload()} reuses one {@code TunnelClient}, one set of executors and
     * one subscription registry. Nothing is constructed, so nothing can be orphaned.
     *
     * <h2>Threading</h2>
     *
     * <p>Hands off to {@code heimdall-io}: the reload re-reads a file and may start a guild
     * discovery, neither of which belongs on the tick loop.
     */
    static final class Reload implements AdminSubcommand {

        @Override
        public String name() {
            return "reload";
        }

        @Override
        public String usage() {
            return "";
        }

        @Override
        public String description() {
            return "re-read bootstrap.yml and reconnect the tunnel, without restarting";
        }

        @Override
        public void run(final CommandSource source, List<String> args, final AdminContext context) {
            source.sendMessage(Msg.legacy("§7Reloading…"));
            context.async(new Runnable() {
                @Override
                public void run() {
                    try {
                        source.sendMessage(Msg.legacy("§a" + context.runtime().reload()));
                    } catch (RuntimeException failed) {
                        source.sendMessage(Msg.legacy("§cReload failed: " + failed));
                    }
                }
            });
        }
    }

    /**
     * {@code /hd modules} — every module this jar has, and which of five states it is in.
     *
     * <p>The five are the point. v2 had one boolean per feature and no way to express the difference
     * between "you switched it off", "it is not compiled into this build at all", "it cannot run on
     * a server with this role whatever the dashboard says", and "it threw on startup and has been
     * contained". Each of those needs a different thing done about it, and three of them look
     * identical from the outside.
     */
    static final class Modules implements AdminSubcommand {

        @Override
        public String name() {
            return "modules";
        }

        @Override
        public String usage() {
            return "";
        }

        @Override
        public String description() {
            return "list this build's modules and what each is doing";
        }

        @Override
        public void run(CommandSource source, List<String> args, AdminContext context) {
            ModuleManager modules = context.runtime().modules();
            Set<String> ids = modules.registeredIds();
            if (ids.isEmpty()) {
                source.sendMessage(Msg.legacy("§eThis build ships no feature modules at all."));
                return;
            }
            boolean connected = context.runtime().tunnel().isConnected();
            Set<String> locallyOff = context.runtime().locallyDisabledModules();
            source.sendMessage(Msg.legacy("§6Modules §7(" + ids.size() + ")"));
            for (String id : ids) {
                ModuleState state = modules.state(id);
                Set<String> capabilities = modules.capabilitiesOf(id);
                String tail = locallyOff.contains(id) ? " §c(disabled locally)"
                        : (capabilities.isEmpty() ? "" : " §8" + capabilities);
                source.sendMessage(Msg.legacy("§7 - §f" + id + " §7" + explain(state, locallyOff.contains(id))
                        + tail));
            }
            // Where to change a module depends on whether the bot can be reached. Telling an operator
            // to use the dashboard while /hd status says the tunnel is down is precisely useless — it
            // is the moment the local escape hatch exists for.
            if (connected) {
                source.sendMessage(Msg.legacy("§8A module absent from this list is not in this jar. "
                        + "Toggle the rest on the Minecraft page of the dashboard, or locally with "
                        + "§7/" + context.label() + " disable <module>§8."));
            } else {
                source.sendMessage(Msg.legacy("§eThe bot is not reachable, so the dashboard cannot "
                        + "change these right now. Use §f/" + context.label()
                        + " disable <module>§e / §f" + context.label() + " enable <module>§e — a "
                        + "local override that works offline and survives a restart."));
            }
        }

        private static String explain(ModuleState state, boolean locallyOff) {
            if (locallyOff) {
                // The local override wins over the module's own state, so say so first: a module the
                // dashboard wants ON shows as STOPPED here, and "switched off in the dashboard" would
                // be a lie about why.
                return "§cswitched off locally";
            }
            if (state == null) {
                return "§8in an unknown state";
            }
            switch (state) {
                case ENABLED:
                    return "§arunning";
                case STOPPED:
                    return "§eswitched off in the dashboard";
                case FAILED:
                    return "§cfailed to start — see the server log, then toggle it off and on";
                case INELIGIBLE:
                default:
                    return "§8cannot run on a server with this role";
            }
        }
    }

    /** {@code /hd enable <module>} — clears a local disable. See {@link LocalToggle}. */
    static final class Enable extends LocalToggle {

        Enable() {
            super(false, "enable", "clear a local disable so the dashboard controls this module again");
        }
    }

    /** {@code /hd disable <module>} — the offline escape hatch. See {@link LocalToggle}. */
    static final class Disable extends LocalToggle {

        Disable() {
            super(true, "disable", "switch a module off locally, even with the bot unreachable");
        }
    }

    /**
     * {@code /hd enable|disable <module>} — v2's global on/off, made per-module and made local.
     *
     * <h2>Why this is not the dashboard toggle</h2>
     *
     * <p>v3's module state is dashboard-owned and arrives over the tunnel (departure D66). That is
     * right almost always, and exactly wrong in the one case an operator most needs a lever: the
     * whitelist is refusing everybody and the bot cannot be reached to turn it off. So this writes a
     * <em>local</em> override to {@code bootstrap.yml} that wins over the pushed config and survives a
     * restart — a module disabled here stays off until it is enabled here, whatever the dashboard
     * says. {@code /hd status} shows what is locally off so it is never a mystery.
     *
     * <p>No argument disables (or re-enables) the {@code whitelist} module, because that is the one
     * the escape hatch exists for — "let everyone in" is what v2's bare {@code /hwl disable} did.
     */
    abstract static class LocalToggle implements AdminSubcommand {

        /** The module the bare verb acts on: the whitelist, which is what "let everyone in" means. */
        private static final String DEFAULT_MODULE = "whitelist";

        private final boolean disable;
        private final String name;
        private final String description;

        LocalToggle(boolean disable, String name, String description) {
            this.disable = disable;
            this.name = name;
            this.description = description;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String usage() {
            return "[module]";
        }

        @Override
        public String description() {
            return description;
        }

        @Override
        public void run(CommandSource source, List<String> args, AdminContext context) {
            String module = args.isEmpty() ? DEFAULT_MODULE : args.get(0).toLowerCase(Locale.ROOT);
            if (context.runtime().modules().state(module) == null) {
                source.sendMessage(Msg.legacy("§cThis build has no module called §f" + module
                        + "§c. §7/" + context.label() + " modules§c lists the ones it has."));
                return;
            }
            try {
                context.runtime().setModuleLocallyDisabled(module, disable);
            } catch (IOException notPersisted) {
                source.sendMessage(Msg.legacy("§cCould not write " + context.runtime().bootstrapStore().file()
                        + " (" + notPersisted.getMessage() + "), so the change would not survive a "
                        + "restart — leaving it unchanged."));
                return;
            }
            if (disable) {
                source.sendMessage(Msg.legacy("§aSwitched §f" + module + "§a off locally. It stays "
                        + "off — even if the dashboard says on — until §f/" + context.label()
                        + " enable " + module + "§a."));
            } else {
                source.sendMessage(Msg.legacy("§aCleared the local override on §f" + module
                        + "§a; the dashboard controls it again."));
            }
        }

        @Override
        public List<String> complete(CommandSource source, List<String> args, AdminContext context) {
            return args.size() <= 1
                    ? new java.util.ArrayList<String>(context.runtime().modules().registeredIds())
                    : java.util.Collections.<String>emptyList();
        }
    }

    /**
     * {@code /hd debug on|off} — the one diagnostic knob that has to be local.
     *
     * <p>Local because the thing an operator most often needs debug logging for is a server that
     * cannot reach its bot, and a setting that arrives over the tunnel is no help there. It is
     * therefore a {@code bootstrap.yml} field, and this writes it back so the setting survives a
     * restart — an operator who turns debug on to catch an intermittent problem should not lose it
     * the first time the server cycles.
     *
     * <p>The toggle takes effect immediately regardless: the logger's flag is volatile, and it is
     * flipped before the file write, so a failed write leaves debug on for this session and says so.
     */
    static final class Debug implements AdminSubcommand {

        @Override
        public String name() {
            return "debug";
        }

        @Override
        public String usage() {
            return "<on|off>";
        }

        @Override
        public String description() {
            return "turn debug logging on or off, and remember it";
        }

        @Override
        public void run(CommandSource source, List<String> args, AdminContext context) {
            HeimdallRuntime runtime = context.runtime();
            if (args.isEmpty()) {
                source.sendMessage(Msg.legacy("§7Debug logging is currently §f"
                        + (runtime.bootstrap().debug() ? "on" : "off")
                        + "§7. Usage: §f/" + context.label() + " debug <on|off>"));
                return;
            }
            Boolean wanted = parse(args.get(0));
            if (wanted == null) {
                source.sendMessage(Msg.legacy("§cSay §fon§c or §foff§c."));
                return;
            }

            BootstrapConfig updated = runtime.bootstrap().toBuilder()
                    .debug(wanted.booleanValue())
                    .build();
            // Flipped first, persisted second. The flag is what an operator asked for; the file is
            // how it survives a restart, and a read-only data directory should not stop the first.
            runtime.setDebugLogging(wanted.booleanValue());
            try {
                runtime.persist(updated);
                source.sendMessage(Msg.legacy("§aDebug logging is now §f"
                        + (wanted.booleanValue() ? "on" : "off") + "§a, and will stay that way."));
            } catch (IOException notPersisted) {
                source.sendMessage(Msg.legacy("§eDebug logging is now §f"
                        + (wanted.booleanValue() ? "on" : "off")
                        + "§e for this session only — " + runtime.bootstrapStore().file()
                        + " could not be written (" + notPersisted.getMessage() + ")."));
            }
        }

        @Override
        public List<String> complete(CommandSource source, List<String> args, AdminContext context) {
            return args.size() <= 1 ? Arrays.asList("on", "off") : java.util.Collections.<String>emptyList();
        }

        /** Accepts what an operator would plausibly type, and nothing else. */
        private static Boolean parse(String raw) {
            String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
            if ("on".equals(value) || "true".equals(value) || "yes".equals(value)) {
                return Boolean.TRUE;
            }
            if ("off".equals(value) || "false".equals(value) || "no".equals(value)) {
                return Boolean.FALSE;
            }
            return null;
        }
    }
}
