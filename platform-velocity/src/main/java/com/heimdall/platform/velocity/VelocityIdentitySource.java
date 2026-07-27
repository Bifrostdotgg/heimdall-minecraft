package com.heimdall.platform.velocity;

import com.heimdall.core.config.ServerRole;
import com.heimdall.core.json.Payload;
import com.heimdall.core.tunnel.IdentitySource;
import com.heimdall.core.tunnel.ServerIdentity;
import com.velocitypowered.api.proxy.ProxyServer;

/**
 * What the proxy tells the bot about itself.
 *
 * <p>{@code mcVersion} is deliberately empty, as it was in v2. A proxy does not have a Minecraft
 * version — it speaks several protocol versions at once, and picking one to report would be a
 * fiction the dashboard would then display as fact. The backend servers behind it each report their
 * own.
 *
 * <p>{@code platform} is {@code velocity} on both the honest and the compatible reading, so the
 * split the Bukkit side needs (see {@code BukkitIdentitySource}) collapses here — {@code
 * platformFamily} is still sent, with the same value, so anything bot-side can read one field for
 * both families.
 */
final class VelocityIdentitySource implements IdentitySource {

    private final ProxyServer proxy;
    private final ServerRole role;
    private final long startedAtMs;

    VelocityIdentitySource(ProxyServer proxy, ServerRole role, long startedAtMs) {
        this.proxy = proxy;
        this.role = role;
        this.startedAtMs = startedAtMs;
    }

    @Override
    public ServerIdentity identity() {
        return ServerIdentity.builder()
                .platform("velocity")
                .serverSoftware(software())
                .mcVersion("")
                .role(role)
                .startedAtMs(startedAtMs)
                .extra(Payload.builder().put("platformFamily", "velocity").build())
                .build();
    }

    private String software() {
        try {
            return proxy.getVersion().getName() + " " + proxy.getVersion().getVersion();
        } catch (RuntimeException unavailable) {
            // Identity is built on the socket's reading thread; an exception there costs the whole
            // connection its metadata for as long as it lives.
            return "Velocity";
        }
    }
}
