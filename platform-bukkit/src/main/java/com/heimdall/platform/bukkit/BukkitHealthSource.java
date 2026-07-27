package com.heimdall.platform.bukkit;

import com.heimdall.core.json.Payload;
import com.heimdall.core.tunnel.HealthSnapshotSource;
import com.heimdall.platform.bukkit.adapter.TickSource;
import com.heimdall.platform.common.JvmHealth;
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
 * <p>Called on {@code heimdall-ws} every heartbeat, and reads only counters and volatile arrays —
 * no Bukkit call here blocks or needs the main thread. {@link TickSource} is what makes the tick
 * figures optional; see it for why Spigot cannot be asked directly.
 */
final class BukkitHealthSource implements HealthSnapshotSource {

    private final TickSource ticks;

    BukkitHealthSource(TickSource ticks) {
        this.ticks = ticks;
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

        return JvmHealth.memory(builder).build();
    }
}
