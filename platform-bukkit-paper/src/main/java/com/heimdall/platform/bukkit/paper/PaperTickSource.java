package com.heimdall.platform.bukkit.paper;

import com.heimdall.platform.bukkit.adapter.TickSource;
import org.bukkit.Bukkit;

/**
 * TPS and MSPT straight from Paper's API — the only source that can report both.
 *
 * <p>The reflective fallback in {@code :platform-bukkit} can find a tick rate inside almost any
 * server, but it cannot find a mean tick time: deriving one from TPS would be a fabrication, since
 * the two only agree while the server is keeping up and disagree exactly when somebody is looking.
 * Paper measures it, so where Paper is present this is what answers.
 *
 * <p><strong>Never loaded without {@link PaperSupport#hasTickApi()} first.</strong> The methods
 * below are direct calls to API Spigot does not have, and the JVM verifies a method body when the
 * class is linked rather than when the method is first called — so merely touching this class on a
 * Spigot server would throw. {@code BukkitAdapters} owns that guard and reaches this class by name.
 *
 * <p>Public and no-argument-constructible for the same reason: it is instantiated reflectively.
 */
public final class PaperTickSource implements TickSource {

    private final boolean mspt = PaperSupport.hasMsptApi();

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public Double tps() {
        try {
            double[] recent = Bukkit.getTPS();
            if (recent == null || recent.length == 0) {
                return null;
            }
            // Clamped: Paper's own figure drifts slightly above 20 on a quiet tick, and a dashboard
            // showing 20.04 TPS invites a support ticket about a server that is fine.
            return Double.valueOf(Math.min(20.0, round(recent[0])));
        } catch (Throwable notThere) {
            return null;
        }
    }

    @Override
    public Double mspt() {
        if (!mspt) {
            return null;
        }
        try {
            return Double.valueOf(round(Bukkit.getAverageTickTime()));
        } catch (Throwable notThere) {
            return null;
        }
    }

    @Override
    public String describe() {
        return mspt ? "paper (tps+mspt)" : "paper (tps)";
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
