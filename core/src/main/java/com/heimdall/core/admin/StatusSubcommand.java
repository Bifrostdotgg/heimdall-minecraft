package com.heimdall.core.admin;

import com.heimdall.core.command.CommandSource;
import com.heimdall.core.config.BootstrapConfig;
import com.heimdall.core.module.ModuleManager;
import com.heimdall.core.module.ModuleState;
import com.heimdall.core.text.Msg;
import com.heimdall.core.tunnel.IdentitySource;
import com.heimdall.core.tunnel.ServerIdentity;
import com.heimdall.core.tunnel.TunnelClient;
import com.heimdall.core.util.Strings;
import com.heimdall.core.wiring.HeimdallRuntime;
import java.util.List;
import java.util.Set;

/**
 * {@code /hd status} — everything a support conversation would otherwise start by asking for.
 *
 * <p>The design rule here is that <strong>every state an operator confuses has to be visible as a
 * different line</strong>, not inferable from the absence of one. Four pairs in particular:
 *
 * <ul>
 *   <li><em>Not set up</em> versus <em>set up but still discovering its guild</em> versus <em>set up
 *       and the bot refused the token</em>. The middle one looks like a network problem and is not;
 *       the third one looks like a network problem and is a revoked credential. {@link
 *       HeimdallRuntime#connectionStatus()} answers all three in one line so both platforms say the
 *       same thing.
 *   <li><em>v3</em> versus <em>v2-compat</em>. A tunnel that negotiated down runs on built-in
 *       defaults with no dashboard control at all, and looks perfectly connected while doing it.
 *   <li>Per module: <em>absent from this jar</em>, <em>switched off</em>, <em>running</em>,
 *       <em>failed to start</em>, <em>ineligible for this role</em>. v2 had one boolean.
 *   <li>The console feed being quiet because nothing is happening, versus being quiet because a
 *       consumer threw and was silently unsubscribed. That is the {@code droppedTapConsumers}
 *       counter, and this is the only place it is ever reported — the drop cannot log for itself,
 *       because it happens inside the logging pipeline.
 * </ul>
 *
 * <p>Read-only and non-blocking throughout: every value is a field read or a cheap snapshot, so this
 * is safe to run on the server thread and safe to run while the bot is unreachable.
 */
final class StatusSubcommand implements AdminSubcommand {

    @Override
    public String name() {
        return "status";
    }

    @Override
    public String usage() {
        return "";
    }

    @Override
    public String description() {
        return "what this server is, and how it stands with its bot";
    }

    @Override
    public void run(CommandSource source, List<String> args, AdminContext context) {
        HeimdallRuntime runtime = context.runtime();
        BootstrapConfig bootstrap = runtime.bootstrap();
        TunnelClient tunnel = runtime.tunnel();

        source.sendMessage(Msg.legacy("§6Heimdall §fv" + context.pluginVersion()
                + " §8— §7" + describeServer(runtime)));
        source.sendMessage(Msg.legacy("§7role: §f" + context.role().wireName()
                + "   §7serverId: §f" + orNone(bootstrap.serverId())));
        source.sendMessage(Msg.legacy("§7bot: §f" + runtime.connectionStatus()));
        source.sendMessage(Msg.legacy("§7api: §f" + runtime.api().describe()
                + "   §7guild: §f" + orNone(runtime.guildId())));
        source.sendMessage(Msg.legacy("§7tunnel: §f" + describeTunnel(tunnel)));
        source.sendMessage(Msg.legacy("§7modules: §f" + describeModules(runtime.modules())));
        source.sendMessage(Msg.legacy("§7whitelist mirror: §f" + context.whitelist().stats()));
        source.sendMessage(Msg.legacy("§7console tap: §f" + describeConsole(runtime)));
        source.sendMessage(Msg.legacy("§7updates: §f" + describeUpdates(context)));
    }

    /**
     * What the server is running, from the same source the bot is told about.
     *
     * <p>Deliberately the {@link IdentitySource} rather than anything read fresh: this is the string
     * the dashboard is showing for this server, so a status line that disagreed with it would send
     * somebody looking for a bug that is really a stale identity.
     */
    private static String describeServer(HeimdallRuntime runtime) {
        IdentitySource source = runtime.identitySource();
        if (source == null) {
            return "unknown server software";
        }
        try {
            ServerIdentity identity = source.identity();
            String software = Strings.isBlank(identity.serverSoftware())
                    ? identity.platform() : identity.serverSoftware();
            String version = Strings.isBlank(identity.mcVersion()) ? "" : " " + identity.mcVersion();
            return orNone(software) + version;
        } catch (RuntimeException unavailable) {
            return "unknown server software (" + unavailable + ")";
        }
    }

    /**
     * The protocol the tunnel is actually speaking, and the config version behind it.
     *
     * <p>{@code v2-compat} on a connected socket is the failure worth surfacing: the bot believes it
     * is talking to a v2 plugin, pushes no configuration, and everything runs on built-in defaults
     * while the dashboard shows settings that are not reaching anybody.
     */
    private static String describeTunnel(TunnelClient tunnel) {
        if (!tunnel.isConnected()) {
            return "not connected (" + tunnel.mode().name().toLowerCase(java.util.Locale.ROOT) + ")";
        }
        int version = tunnel.configVersion();
        return "connected, protocol " + tunnel.mode().name().toLowerCase(java.util.Locale.ROOT)
                + ", bot config version " + (version < 0 ? "none yet" : Integer.toString(version));
    }

    /**
     * Every registered module, its state, and what it declares.
     *
     * <p>Capabilities are shown because they are what the bot narrows its configuration push to: a
     * module that is enabled but declares nothing receives no settings, and the two situations are
     * otherwise indistinguishable from a dashboard that shows a saved value.
     */
    private static String describeModules(ModuleManager modules) {
        Set<String> ids = modules.registeredIds();
        if (ids.isEmpty()) {
            return "none registered";
        }
        StringBuilder out = new StringBuilder();
        for (String id : ids) {
            if (out.length() > 0) {
                out.append(", ");
            }
            ModuleState state = modules.state(id);
            out.append(id).append('=').append(state == null ? "unknown" : label(state));
            Set<String> capabilities = modules.capabilitiesOf(id);
            if (!capabilities.isEmpty()) {
                out.append(' ').append(capabilities);
            }
        }
        return out.toString();
    }

    /** Words rather than enum spelling, because {@code INELIGIBLE} is not what an operator asked. */
    private static String label(ModuleState state) {
        switch (state) {
            case ENABLED:
                return "on";
            case STOPPED:
                return "off";
            case FAILED:
                return "FAILED";
            case INELIGIBLE:
            default:
                return "not for this role";
        }
    }

    /**
     * Whether anything reading the console feed has been dropped for throwing.
     *
     * <p>This is the only place that number is ever reported, and it has to be: the drop happens
     * inside the log-capture path, where writing a line would be captured and fed back into the loop
     * the re-entrancy guard exists to break. So the tap counts silently and this asks.
     */
    private static String describeConsole(HeimdallRuntime runtime) {
        int dropped;
        try {
            dropped = runtime.platform().console().droppedTapConsumers();
        } catch (RuntimeException unavailable) {
            return "unknown (" + unavailable + ")";
        }
        return dropped == 0
                ? "ok"
                : dropped + " consumer(s) threw and were unsubscribed — whatever was reading the "
                        + "console feed has stopped receiving it";
    }

    private static String describeUpdates(AdminContext context) {
        UpdateAdmin updates = context.updates();
        if (!updates.isSupported()) {
            return "no self-updater in this build";
        }
        if (!updates.isUpdateAvailable()) {
            return "up to date as far as the last check knows";
        }
        return "version " + updates.latestVersion() + " is available — run /hd update";
    }

    private static String orNone(String value) {
        return Strings.isBlank(value) ? "none" : value;
    }
}
