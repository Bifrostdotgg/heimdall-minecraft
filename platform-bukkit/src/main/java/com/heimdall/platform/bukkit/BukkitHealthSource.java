package com.heimdall.platform.bukkit;

import com.heimdall.core.json.Payload;
import com.heimdall.core.tunnel.HealthSnapshotSource;
import com.heimdall.platform.bukkit.adapter.TickSource;
import com.heimdall.platform.common.JvmHealth;
import com.heimdall.platform.common.StatusHealth;
import java.io.File;
import java.lang.reflect.Method;
import org.bukkit.Bukkit;

/**
 * The periodic TPS/memory/player-count snapshot, for a Bukkit-family server.
 *
 * <p><strong>Every field is omitted rather than faked when it cannot be measured.</strong> That is
 * the whole reason {@link HealthSnapshotSource} returns a {@link Payload} instead of a typed record:
 * a Spigot with no tick API that sent {@code tps: 0} would have the dashboard charting a healthy
 * server as one that had stopped ticking, and the graph would be indistinguishable from a real
 * outage. A missing field renders as "unknown", which is true.
 *
 * <p>MOTD and favicon are the exception to omission: {@code motdClean} is always present (empty
 * when unread) so a {@code status@1} bot has a stable key. The favicon is omitted when the PNG
 * cannot be read or is over 64 KiB decoded.
 *
 * <p>Called on {@code heimdall-ws} every heartbeat, and reads only counters, a string getter and a
 * cached file - no Bukkit call here blocks or needs the main thread. {@link TickSource} is what
 * makes the tick figures optional; see it for why Spigot cannot be asked directly.
 */
final class BukkitHealthSource implements HealthSnapshotSource {

    private final TickSource ticks;
    private final MotdFaviconSource status;
    private StatusHealth.IconFile iconCache;

    BukkitHealthSource(TickSource ticks) {
        this(ticks, LiveBukkitStatus.INSTANCE);
    }

    /** Package-visible so tests can stub MOTD and the icon file without a running server. */
    BukkitHealthSource(TickSource ticks, MotdFaviconSource status) {
        this.ticks = ticks;
        this.status = status;
    }

    @Override
    public Payload snapshot() {
        Payload.Builder builder = Payload.builder();

        Double tps = ticks.tps();
        if (tps != null) {
            builder.put("tps", tps.doubleValue());
        }
        Double mspt = ticks.mspt();
        if (mspt != null) {
            builder.put("mspt", mspt.doubleValue());
        }

        try {
            builder.put("onlinePlayers", Bukkit.getOnlinePlayers().size());
            builder.put("maxPlayers", Bukkit.getMaxPlayers());
        } catch (RuntimeException notReady) {
            // Asked before the server has finished starting or while it is stopping. The counts are
            // left out; the heartbeat still goes, which is what keeps the connection alive.
        }

        String motd = "";
        try {
            String read = status.motd();
            if (read != null) {
                motd = read;
            }
        } catch (RuntimeException unread) {
            // Same window as the player counts: a throw here must not cost the tick its health.
        }

        return JvmHealth.memory(StatusHealth.apply(builder, motd, iconPng())).build();
    }

    private byte[] iconPng() {
        try {
            File file = status.iconFile();
            if (file != null) {
                if (iconCache == null) {
                    iconCache = new StatusHealth.IconFile(file);
                }
                byte[] fromFile = iconCache.read();
                if (fromFile != null) {
                    return fromFile;
                }
            }
        } catch (RuntimeException unread) {
            // Fall through to CachedServerIcon, which later Bukkit exposes as getData().
        }
        return cachedServerIconPng();
    }

    /**
     * 1.8.8's {@code CachedServerIcon} is an empty interface, so {@code getData()} is reached
     * only when the implementation actually has it. Missing method, missing icon, or a throw
     * is treated as no favicon.
     */
    private static byte[] cachedServerIconPng() {
        try {
            Object icon = Bukkit.getServerIcon();
            if (icon == null) {
                return null;
            }
            Method getData = icon.getClass().getMethod("getData");
            Object data = getData.invoke(icon);
            if (data instanceof String) {
                return StatusHealth.decodeIcon((String) data);
            }
        } catch (RuntimeException ignored) {
        } catch (ReflectiveOperationException ignored) {
        }
        return null;
    }

    /** MOTD string and server-icon.png location. The live impl talks to Bukkit. */
    interface MotdFaviconSource {
        String motd();

        File iconFile();
    }

    private static final class LiveBukkitStatus implements MotdFaviconSource {

        static final LiveBukkitStatus INSTANCE = new LiveBukkitStatus();

        @Override
        public String motd() {
            return Bukkit.getMotd();
        }

        @Override
        public File iconFile() {
            return new File(Bukkit.getWorldContainer(), "server-icon.png");
        }
    }
}
