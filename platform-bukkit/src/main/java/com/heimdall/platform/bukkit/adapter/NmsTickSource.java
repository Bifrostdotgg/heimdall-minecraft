package com.heimdall.platform.bukkit.adapter;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * TPS read out of the server's own internals, for servers whose API will not say.
 *
 * <p>Spigot has never exposed tick rate. The number exists — {@code MinecraftServer.recentTps} is a
 * {@code double[3]} of the 1-, 5- and 15-minute averages, and has been since 1.7 — but only Paper
 * added an API for it. Reaching for the field directly is the difference between a legacy Spigot
 * showing a real TPS on the dashboard and showing nothing at all.
 *
 * <p>Two hops, both reflective and both guarded: {@code Bukkit.getServer().getServer()} is
 * CraftBukkit's own accessor for the underlying {@code MinecraftServer}, and {@code recentTps} is a
 * public field on it. The field name is stable across the entire 1.7–1.21 range and survived the
 * 1.17 package flattening, which is what makes this worth doing rather than a version-mapping
 * exercise.
 *
 * <p>Resolution happens once at construction and the outcome is remembered. If either hop fails
 * this source reports unavailable forever, which is correct: neither the server implementation nor
 * its class shape changes while the JVM is running.
 *
 * <p>No MSPT. Deriving it from TPS ({@code 1000/tps}) would be a fabrication — it is the mean tick
 * time only while the server is keeping up, and the moment it is not, the two numbers say different
 * things and only one of them is measured.
 */
final class NmsTickSource implements TickSource {

    private final double[] recentTps;

    private NmsTickSource(double[] recentTps) {
        this.recentTps = recentTps;
    }

    /** Resolves the field, or returns {@code null} if this server does not have it. */
    static TickSource tryCreate() {
        try {
            Object craftServer = org.bukkit.Bukkit.getServer();
            if (craftServer == null) {
                return null;
            }
            Method getServer = craftServer.getClass().getMethod("getServer");
            Object minecraftServer = getServer.invoke(craftServer);
            if (minecraftServer == null) {
                return null;
            }
            Field field = minecraftServer.getClass().getField("recentTps");
            Object value = field.get(minecraftServer);
            if (!(value instanceof double[]) || ((double[]) value).length == 0) {
                return null;
            }
            // The array is held, not copied: the server writes into this same instance every tick,
            // so keeping the reference is what makes later reads current rather than frozen.
            return new NmsTickSource((double[]) value);
        } catch (Throwable notThisServer) {
            return null;
        }
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public Double tps() {
        try {
            // Clamped: the server's own figure drifts slightly above 20 on a quiet tick, and a
            // dashboard showing 20.04 TPS invites a support ticket about a server that is fine.
            return Double.valueOf(Math.min(20.0, round(recentTps[0])));
        } catch (RuntimeException gone) {
            return null;
        }
    }

    @Override
    public Double mspt() {
        return null;
    }

    @Override
    public String describe() {
        return "nms-reflection";
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
