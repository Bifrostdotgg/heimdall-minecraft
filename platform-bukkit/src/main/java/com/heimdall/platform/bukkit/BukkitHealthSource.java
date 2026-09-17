package com.heimdall.platform.bukkit;

import com.heimdall.core.json.Payload;
import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.tunnel.HealthSnapshotSource;
import com.heimdall.platform.bukkit.adapter.TickSource;
import com.heimdall.platform.common.JvmHealth;
import com.heimdall.platform.common.StatusHealth;
import java.io.File;
import java.lang.reflect.Method;
import java.util.List;
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
 * <p><strong>The two player counts travel together or not at all.</strong> They come from one
 * race-tolerant copy of the online list, because a bot subtracting one from the other to get a
 * publishable figure would, given {@code onlinePlayers} alone, quietly publish a number that still
 * includes hidden staff. Degrading to "the total, minus nothing" is the one degradation here that is
 * worse than sending nothing, so when the copy cannot be taken both counts are dropped and only
 * {@code maxPlayers} goes.
 *
 * <p>Called on {@code heimdall-ws} every heartbeat, and reads only counters, a string getter, a
 * cached file and, for the vanish count, one pass over a copy of the online list - no Bukkit call
 * here blocks or needs the main thread. {@link TickSource} is what makes the tick figures optional;
 * see it for why Spigot cannot be asked directly.
 */
final class BukkitHealthSource implements HealthSnapshotSource {

    private final HeimdallLogger logger;
    private final TickSource ticks;
    private final MotdFaviconSource status;
    private final BukkitPlayerDirectory.RosterSource roster;
    private StatusHealth.IconFile iconCache;

    BukkitHealthSource(HeimdallLogger logger, TickSource ticks) {
        this(logger, ticks, LiveBukkitStatus.INSTANCE);
    }

    /** Package-visible so tests can stub MOTD and the icon file without a running server. */
    BukkitHealthSource(HeimdallLogger logger, TickSource ticks, MotdFaviconSource status) {
        this(logger, ticks, status, BukkitPlayerDirectory.LIVE);
    }

    /** And this one so they can stub the online list, which is the only way to count vanish. */
    BukkitHealthSource(
            HeimdallLogger logger,
            TickSource ticks,
            MotdFaviconSource status,
            BukkitPlayerDirectory.RosterSource roster) {
        this.logger = logger;
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

        // One copy, both counts. They have to describe the same instant and they have to travel
        // together: a bot subtracting vanishedPlayers to get a publishable figure would, given
        // onlinePlayers on its own, silently publish a number that still includes hidden staff. So
        // the copy failing costs both of them rather than just the one that needed the walk.
        List<Player> online = null;
        try {
            online = BukkitPlayerDirectory.raceTolerantCopy(roster);
        } catch (RuntimeException unavailable) {
            // Either the server is not in a state to be asked at all, or five consecutive reads all
            // raced. A one-tick gap in the dashboard's chart beats a one-tick leak, and the next
            // heartbeat is seconds away.
        }

        try {
            if (online != null) {
                builder.put("onlinePlayers", online.size());
                builder.put("vanishedPlayers", BukkitVanish.count(logger, online));
            }
            builder.put("maxPlayers", Bukkit.getMaxPlayers());
        } catch (RuntimeException notReady) {
            // Asked before the server has finished starting or while it is stopping. Whatever was
            // already written stays; the heartbeat still goes, which is what keeps the connection
            // alive.
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
