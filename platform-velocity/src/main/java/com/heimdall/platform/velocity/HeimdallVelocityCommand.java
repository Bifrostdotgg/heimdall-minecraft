package com.heimdall.platform.velocity;

import com.heimdall.core.BuildConstants;
import com.heimdall.core.config.ServerRole;
import com.heimdall.core.text.Msg;
import com.heimdall.core.wiring.HeimdallRuntime;
import com.velocitypowered.api.command.SimpleCommand;

/**
 * {@code /hdp} — the proxy's command shell, with only its status line behind it for now.
 *
 * <p>A different name from the Bukkit side's {@code /hd} on purpose. In a proxied network both are
 * installed and a player typing {@code /hd} would reach whichever of the two claimed the name
 * first — which is the proxy, because it intercepts before forwarding. Giving the proxy its own
 * verb means "am I asking the gatekeeper or this backend?" is answered by what you typed rather
 * than by where you were standing.
 *
 * <p>The {@code heimdallproxy} alias is the spelled-out form, for the operator who does not
 * remember which abbreviation belongs to which component.
 *
 * <p>Registered in phase 1c so the registration path runs on every boot; the subcommand tree lands
 * in 1e. Unlike the Bukkit side, the smoke matrix cannot invoke it — the proxy image has no console
 * pipe and no RCON — so the coverage there is that the enable banner is logged immediately after
 * registration, and a throw during it would have surfaced as an enable failure instead.
 * TODO(1d): assert it for real from the headless-client row that D43 also needs.
 */
final class HeimdallVelocityCommand implements SimpleCommand {

    private static final String PERMISSION = "heimdall.admin";

    private final HeimdallRuntime runtime;
    private final VelocityText text;
    private final ServerRole role;

    HeimdallVelocityCommand(HeimdallRuntime runtime, VelocityText text, ServerRole role) {
        this.runtime = runtime;
        this.text = text;
        this.role = role;
    }

    @Override
    public void execute(Invocation invocation) {
        Object source = invocation.source();
        text.send(source, Msg.legacy("§6Heimdall §fv" + BuildConstants.VERSION));
        text.send(source, Msg.legacy("§7role: §f" + role.wireName()));
        text.send(source, Msg.legacy("§7status: " + status()));
        if (invocation.arguments().length > 0) {
            text.send(source, Msg.legacy("§7Subcommands arrive in the next phase."));
        }
    }

    /**
     * Velocity asks before running, which is the idiomatic place for the check here — unlike Bukkit,
     * where the permission node is declared in {@code plugin.yml} and denial is our own branch.
     */
    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission(PERMISSION);
    }

    private String status() {
        if (!runtime.isConfigured()) {
            return "§enot set up — no bootstrap.yml yet";
        }
        return runtime.tunnel() != null && runtime.tunnel().isConnected()
                ? "§aconnected"
                : "§econfigured, not connected";
    }
}
