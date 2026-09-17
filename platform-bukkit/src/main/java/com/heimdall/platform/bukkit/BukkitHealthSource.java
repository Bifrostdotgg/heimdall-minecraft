package com.heimdall.platform.bukkit;

import com.heimdall.core.json.Payload;
import com.heimdall.core.tunnel.HealthSnapshotSource;
import com.heimdall.platform.bukkit.adapter.TickSource;
import com.heimdall.platform.common.JvmHealth;
import com.heimdall.platform.common.StatusHealth;
import java.io.File;
import java.lang.reflect.Method;
import java.util.Collection;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

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
 * <p>{@code vanishedPlayers} is the one field sent as zero rather than omitted when it is zero, and
 * that is not a contradiction: "nobody is hidden" is something this server measured, unlike a tps it
 * cannot read. A bot reading a snapshot with no {@code vanishedPlayers} at all is talking to a proxy
 * or to an older jar, which is a different fact and is why {@code vanish@1} exists to say so.
 *
 * <p>Called on {@code heimdall-ws} every heartbeat, and reads only counters, a string getter, a
 * cached file and, for the vanish count, one pass over the online list - no Bukkit call here blocks
 * or needs the main thread. {@link TickSource} is what makes the tick figures optional; see it for
 * why Spigot cannot be asked directly.
 */
final class BukkitHealthSource implements HealthSnapshotSource {

    private final TickSource ticks;
    private final MotdFaviconSource status;
    private final BukkitPlayerDirectory.RosterSource roster;
    private StatusHealth.IconFile iconCache;

    BukkitHealthSource(TickSource ticks) {
        this(ticks, LiveBukkitStatus.INSTANCE);
    }

    /** Package-visible so tests can stub MOTD and the icon file without a running server. */
    BukkitHealthSource(TickSource ticks, MotdFaviconSource status) {
        this(ticks, status, LIVE_ROSTER);
    }

    /** And this one so they can stub the online list, which is the only way to count vanish. */
    BukkitHealthSource(
            TickSource ticks, MotdFaviconSource status, BukkitPlayerDirectory.RosterSource roster) {
        this.ticks = ticks;
        this.status = status;
        this.roster = roster;
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

        Collection<? extends Player> online = null;
        try {
            online = roster.onlinePlayers();
            if (online != null) {
                builder.put("onlinePlayers", online.size());
            }
            builder.put("maxPlayers", Bukkit.getMaxPlayers());
        } catch (RuntimeException notReady) {
            // Asked before the server has finished starting or while it is stopping. The counts are
            // left out; the heartbeat still goes, which is what keeps the connection alive.
        }

        if (online != null) {
            try {
                builder.put("vanishedPlayers", BukkitVanish.count(online));
            } catch (RuntimeException raced) {
                // Its own block because it is the only read here that walks the live view rather
                // than asking it for a number, so it is the only one a join or a quit can raise a
                // ConcurrentModificationException out of. Sharing the block above would let that
                // race cost the player counts as well, and one missing field on one heartbeat is a
                // field the next tick supplies seconds later.
            }
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

    /** The server's own online list. Static on Bukkit, which is the whole reason for the seam. */
    private static final BukkitPlayerDirectory.RosterSource LIVE_ROSTER =
            new BukkitPlayerDirectory.RosterSource() {
                @Override
                public Collection<? extends Player> onlinePlayers() {
                    return Bukkit.getOnlinePlayers();
                }
            };

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
