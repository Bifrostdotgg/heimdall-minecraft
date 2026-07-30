package com.heimdall.platform.bungee;

import com.heimdall.core.config.ServerRole;
import com.heimdall.core.json.Payload;
import com.heimdall.core.tunnel.IdentitySource;
import com.heimdall.core.tunnel.ServerIdentity;
import java.util.Locale;
import net.md_5.bungee.api.ProxyServer;

/**
 * What the proxy tells the bot about itself.
 *
 * <p>{@code mcVersion} is deliberately empty, as it is on Velocity and was in v2. A proxy does not
 * have a Minecraft version — it speaks several protocol versions at once, and picking one to report
 * would be a fiction the dashboard would then display as fact. The backend servers behind it each
 * report their own.
 *
 * <h2>{@code platform} is what the proxy calls itself; {@code platformFamily} is always bungeecord</h2>
 *
 * <p>The same split the Bukkit binding makes, and for the same reason (departure D42): a BungeeCord
 * fork reports its own name from {@code getName()} — {@code Waterfall}, and whatever else a network
 * is running — and telling support "BungeeCord" for a proxy whose problem is that it is not
 * BungeeCord is exactly the failure v2 had on the Bukkit side. So {@code platform} is the truth,
 * lower-cased, and {@code platformFamily} carries the stable value anything bot-side can match on.
 *
 * <p>That is also why this binding is described as BungeeCord's rather than Waterfall's throughout:
 * Waterfall is a BungeeCord fork exposing the identical {@code net.md_5.bungee} API, so it needs no
 * separate module — only an honest name on the wire.
 */
final class BungeeIdentitySource implements IdentitySource {

    private final ProxyServer proxy;
    private final ServerRole role;
    private final long startedAtMs;

    BungeeIdentitySource(ProxyServer proxy, ServerRole role, long startedAtMs) {
        this.proxy = proxy;
        this.role = role;
        this.startedAtMs = startedAtMs;
    }

    @Override
    public ServerIdentity identity() {
        return ServerIdentity.builder()
                .platform(platform())
                .serverSoftware(software())
                .mcVersion("")
                .role(role)
                .startedAtMs(startedAtMs)
                .extra(Payload.builder().put("platformFamily", "bungeecord").build())
                .build();
    }

    /** {@code getName()} lower-cased — {@code bungeecord}, {@code waterfall}, a fork's own name. */
    private String platform() {
        try {
            String name = proxy.getName();
            if (name != null && !name.trim().isEmpty()) {
                return name.trim().toLowerCase(Locale.ROOT);
            }
        } catch (RuntimeException unavailable) {
            // Falls through to the family name below.
        }
        return "bungeecord";
    }

    private String software() {
        try {
            return proxy.getName() + " " + proxy.getVersion();
        } catch (RuntimeException unavailable) {
            // Identity is built on the socket's reading thread; an exception there costs the whole
            // connection its metadata for as long as it lives.
            return "BungeeCord";
        }
    }
}
