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
            source.sendMessage(Msg.legacy("§6Modules §7(" + ids.size() + ")"));
            for (String id : ids) {
                ModuleState state = modules.state(id);
                Set<String> capabilities = modules.capabilitiesOf(id);
                source.sendMessage(Msg.legacy("§7 - §f" + id + " §7" + explain(state)
                        + (capabilities.isEmpty() ? "" : " §8" + capabilities)));
            }
            source.sendMessage(Msg.legacy("§8A module absent from this list is not in this jar. "
                    + "Toggle the rest on the Minecraft page of the dashboard."));
        }

        private static String explain(ModuleState state) {
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
                        + "§7. Usage: §f/hd debug <on|off>"));
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
