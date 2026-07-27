package com.heimdall.platform.velocity;

import com.heimdall.core.json.Payload;
import com.heimdall.core.tunnel.HealthSnapshotSource;
import com.heimdall.platform.common.JvmHealth;
import com.velocitypowered.api.proxy.ProxyServer;

/**
 * The proxy's periodic health snapshot: player count and memory, and nothing else.
 *
 * <p><strong>No TPS and no MSPT, ever.</strong> A proxy has no tick loop — there is nothing to
 * measure — so those fields are omitted rather than sent as zero or as some invented figure.
 * {@link HealthSnapshotSource} returns a {@link Payload} rather than a typed record precisely so
 * this is expressible: a dashboard that charted a healthy proxy at 0 TPS would be indistinguishable
 * from one showing a real outage.
 *
 * <p>{@code maxPlayers} comes from the proxy's configured slot count, which is what a player sees
 * in the server list — the backends' own limits are separate numbers and each backend reports its.
 */
final class VelocityHealthSource implements HealthSnapshotSource {

    private final ProxyServer proxy;

    VelocityHealthSource(ProxyServer proxy) {
        this.proxy = proxy;
    }

    @Override
    public Payload snapshot() {
        Payload.Builder builder = Payload.builder();
        try {
            builder.put("onlinePlayers", proxy.getPlayerCount());
            int slots = proxy.getConfiguration().getShowMaxPlayers();
            if (slots > 0) {
                builder.put("maxPlayers", slots);
            }
        } catch (RuntimeException notReady) {
            // Called on heimdall-ws every heartbeat. Throwing costs the tick both its health and
            // its liveness refresh, so the counts are dropped and the beat still goes.
        }
        return JvmHealth.memory(builder).build();
    }
}
