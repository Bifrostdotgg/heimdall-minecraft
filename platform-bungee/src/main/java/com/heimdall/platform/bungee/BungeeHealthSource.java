package com.heimdall.platform.bungee;

import com.heimdall.core.json.Payload;
import com.heimdall.core.tunnel.HealthSnapshotSource;
import com.heimdall.platform.common.JvmHealth;
import com.heimdall.platform.common.StatusHealth;
import java.util.Collection;
import net.md_5.bungee.api.Favicon;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.config.ListenerInfo;

/**
 * The proxy's periodic health snapshot: player count, memory, MOTD and favicon.
 *
 * <p><strong>No TPS and no MSPT, ever.</strong> A proxy has no tick loop - there is nothing to
 * measure - so those fields are omitted rather than sent as zero or as some invented figure.
 * {@link HealthSnapshotSource} returns a {@link Payload} rather than a typed record precisely so this
 * is expressible: a dashboard that charted a healthy proxy at 0 TPS would be indistinguishable from
 * one showing a real outage. The bot's own {@code ServerHealth} declares every field optional and its
 * panel hides a metric no sample carries, so the omission is what produces the right rendering.
 *
 * <p>Exactly the count and memory keys the Velocity source sends, which is what makes the two
 * proxies indistinguishable on the dashboard's Health panel: {@code onlinePlayers},
 * {@code maxPlayers} when a limit is set, and {@code usedMemMb}/{@code maxMemMb} from
 * {@link JvmHealth}. MOTD comes from the first listener (legacy colour codes are kept as
 * {@code motdRaw}); the favicon is the proxy's configured server-icon.
 *
 * <p>{@code maxPlayers} comes from the proxy's configured slot count, which is what a player sees in
 * the server list - the backends' own limits are separate numbers and each backend reports its.
 * BungeeCord uses {@code -1} for "unlimited", which is why the guard is {@code > 0} rather than a
 * null check: sending {@code maxPlayers: -1} would draw a bar with a negative denominator.
 */
final class BungeeHealthSource implements HealthSnapshotSource {

    private final ProxyServer proxy;

    BungeeHealthSource(ProxyServer proxy) {
        this.proxy = proxy;
    }

    @Override
    public Payload snapshot() {
        Payload.Builder builder = Payload.builder();
        try {
            builder.put("onlinePlayers", proxy.getOnlineCount());
            int slots = proxy.getConfig().getPlayerLimit();
            if (slots > 0) {
                builder.put("maxPlayers", slots);
            }
        } catch (RuntimeException notReady) {
            // Called on heimdall-ws every heartbeat. Throwing costs the tick both its health and its
            // liveness refresh, so the counts are dropped and the beat still goes.
        }

        String motd = "";
        try {
            motd = firstListenerMotd();
        } catch (RuntimeException unread) {
            // Same window as the counts: an unread MOTD is empty, not a skipped heartbeat.
        }

        return JvmHealth.memory(StatusHealth.apply(builder, motd, faviconPng())).build();
    }

    private String firstListenerMotd() {
        Collection<ListenerInfo> listeners = proxy.getConfig().getListeners();
        if (listeners == null || listeners.isEmpty()) {
            return "";
        }
        ListenerInfo first = listeners.iterator().next();
        if (first == null || first.getMotd() == null) {
            return "";
        }
        return first.getMotd();
    }

    private byte[] faviconPng() {
        try {
            Favicon favicon = proxy.getConfig().getFaviconObject();
            if (favicon != null && favicon.getEncoded() != null) {
                byte[] png = StatusHealth.decodeIcon(favicon.getEncoded());
                if (png != null) {
                    return png;
                }
            }
            return StatusHealth.decodeIcon(proxy.getConfig().getFavicon());
        } catch (RuntimeException unread) {
            return null;
        }
    }
}
