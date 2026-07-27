package com.heimdall.platform.bukkit.adapter;

/**
 * How fast the server is running, if it will say.
 *
 * <p>An adapter rather than a direct call because {@code Server#getTPS()} is <strong>not Bukkit
 * API</strong> — it is a Paper addition. Spigot 1.8.8 has no such method and never will, so a
 * module compiled against the 1.8.8 API cannot name it, and a plugin that calls it unconditionally
 * dies with {@code NoSuchMethodError} on exactly the servers this project promises to support.
 *
 * <p>Three implementations, tried in order by {@link BukkitAdapters}: Paper's own API, a reflective
 * read of the server's internal tick history, and one that admits it does not know. The last is a
 * legitimate outcome — {@link com.heimdall.core.tunnel.HealthSnapshotSource} documents every field
 * as optional precisely so a platform that cannot answer sends nothing rather than sending zeroes
 * the dashboard would chart as a server running at 0 TPS.
 */
public interface TickSource {

    /** Whether this source can answer at all on the running server. */
    boolean isAvailable();

    /**
     * The one-minute average ticks per second.
     *
     * @return the average, or {@code null} if unknown
     */
    Double tps();

    /**
     * The average milliseconds spent per tick.
     *
     * @return the average, or {@code null} if unknown — even Paper only added this in 1.16
     */
    Double mspt();

    /** A short description for the boot log, e.g. {@code paper} or {@code nms-reflection}. */
    String describe();

    /** The source for a server that will not say. */
    TickSource UNAVAILABLE = new TickSource() {
        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public Double tps() {
            return null;
        }

        @Override
        public Double mspt() {
            return null;
        }

        @Override
        public String describe() {
            return "unavailable";
        }
    };
}
