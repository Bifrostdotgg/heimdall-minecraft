package com.heimdall.core.module;

import com.heimdall.core.config.ServerRole;
import com.heimdall.core.tunnel.Capabilities;
import com.heimdall.core.tunnel.TunnelClient;
import java.util.Collections;
import java.util.Set;

/**
 * Health reporting, as a module an operator can switch off. Departure D69.
 *
 * <h2>Why this exists at all, given core does the sending</h2>
 *
 * <p>The heartbeat piggybacks a {@code health} payload from the platform's
 * {@link com.heimdall.core.tunnel.HealthSnapshotSource}, and it did so from core with no module
 * behind it. Nothing therefore contributed {@code health@1} to {@code identify} — so a plugin that
 * was, at that very moment, feeding the dashboard's TPS chart and the bot's {@code low_tps} and
 * {@code player_surge} alerts had its health row rendered as <em>"Not available in this plugin
 * build"</em>. The capability list is supposed to describe what the jar can do, and the jar could
 * demonstrably do this.
 *
 * <p>Declaring the capability without gating anything would have fixed the lie and left the row a
 * dead switch, which is the same lie one click later. So health is a real module: it declares the
 * capability, and its enabled state is what decides whether the heartbeat carries a snapshot. An
 * operator who does not want TPS, memory and player counts leaving their box has a switch that works.
 *
 * <h2>The one thing it does</h2>
 *
 * <p>{@link #enable} and {@link #disable} flip {@link TunnelClient#setHealthReportingEnabled(boolean)}
 * and nothing else. There is no listener to unwind and no state to keep, which is why this takes the
 * client directly rather than reaching for anything on its {@link ModuleContext}: the snapshot source
 * belongs to the platform and is installed on the tunnel at boot, and this module decides only
 * whether it is read.
 *
 * <p>It runs under <strong>every</strong> role. A proxy has no TPS, but it has memory and a player
 * count, and {@code HealthSnapshotSource} is built so a platform that can answer half the questions
 * sends half the fields.
 *
 * <p>Registered by {@code HeimdallRuntime} itself rather than by {@code HeimdallModules} — it is
 * core's own module, and core must not depend on the feature modules.
 *
 * <h2>Default on</h2>
 *
 * <p>Two independent defaults, deliberately: {@code TunnelClient}'s flag starts {@code true}, and
 * {@code HeimdallRuntime}'s built-in config defaults mark {@code health} enabled so the first
 * reconcile starts this module before any {@code config.push} has ever arrived. A server that is
 * offline, unregistered, talking to a v2 bot, or booting for the very first time keeps reporting
 * health exactly as it did before this module existed — only an explicit {@code enabled: false} from
 * the dashboard stops it.
 */
public final class HealthModule implements HeimdallModule {

    /** The module id, which is also its key in the remote-config document. */
    public static final String ID = "health";

    private final TunnelClient tunnel;

    public HealthModule(TunnelClient tunnel) {
        if (tunnel == null) {
            throw new IllegalArgumentException("a tunnel is required");
        }
        this.tunnel = tunnel;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Set<String> capabilities() {
        return Collections.singleton(Capabilities.HEALTH);
    }

    @Override
    public Set<ServerRole> roles() {
        // Empty means "any role". A proxy reports memory and players even though it has no TPS.
        return Collections.emptySet();
    }

    @Override
    public void enable(ModuleContext context) {
        tunnel.setHealthReportingEnabled(true);
    }

    @Override
    public void disable() {
        tunnel.setHealthReportingEnabled(false);
    }
}
