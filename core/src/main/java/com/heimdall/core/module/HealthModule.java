package com.heimdall.core.module;

import com.heimdall.core.config.ServerRole;
import com.heimdall.core.tunnel.Capabilities;
import com.heimdall.core.tunnel.TunnelClient;
import java.util.Collections;
import java.util.LinkedHashSet;
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
 * sends half the fields. The same frames also carry MOTD and favicon under {@code status@1}: that
 * is a second capability of this module, not a dashboard toggle of its own. Disable health and
 * those fields stop with the rest of the snapshot.
 *
 * <p><strong>{@code vanish@1} is a third, and it is conditional.</strong> A Bukkit-family server can
 * see that a player is hidden by a vanish plugin and reports it twice over: {@code vanishedPlayers}
 * on the snapshot, and {@code vanished} on the roster rows {@code get_players} answers with. A proxy
 * can see neither, so it declares neither - the flag comes from
 * {@link com.heimdall.core.platform.PlayerDirectory#reportsVanish()} and is passed in at
 * construction. The roster half does not stop when health is switched off, because it is answered by
 * core's request wiring rather than by the heartbeat; the capability describes what the jar can see,
 * which is true either way.
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

    private static final Set<String> CAPABILITIES = capabilities(false);

    private static final Set<String> CAPABILITIES_WITH_VANISH = capabilities(true);

    private static Set<String> capabilities(boolean vanish) {
        Set<String> caps = new LinkedHashSet<String>();
        caps.add(Capabilities.HEALTH);
        caps.add(Capabilities.STATUS);
        if (vanish) {
            caps.add(Capabilities.VANISH);
        }
        return Collections.unmodifiableSet(caps);
    }

    private final TunnelClient tunnel;
    private final boolean reportsVanish;

    /**
     * @param reportsVanish whether this platform's
     *     {@link com.heimdall.core.platform.PlayerDirectory#reportsVanish() player directory} can see
     *     vanish state. The Bukkit family can; a proxy cannot, and must not declare
     *     {@link Capabilities#VANISH} it would never send a key for.
     */
    public HealthModule(TunnelClient tunnel, boolean reportsVanish) {
        if (tunnel == null) {
            throw new IllegalArgumentException("a tunnel is required");
        }
        this.tunnel = tunnel;
        this.reportsVanish = reportsVanish;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Set<String> capabilities() {
        return reportsVanish ? CAPABILITIES_WITH_VANISH : CAPABILITIES;
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
