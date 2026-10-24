package com.heimdall.core.admin;

/**
 * The whitelist module's operator surface, as core sees it.
 *
 * <p>Core cannot depend on {@code :module-whitelist} — a feature module is optional and
 * independently toggleable, which is the whole reason the module system exists — but three admin
 * verbs are about the whitelist and nothing else. So the module implements this and the wiring in
 * {@code :platform-common} introduces the two, which is the one place that already depends on both.
 *
 * <p>{@link #NONE} exists so a build compiled without the module still has a coherent command tree:
 * {@code /hd cache stats} says the feature is not installed rather than being a verb that silently
 * does nothing.
 *
 * <h2>Threading</h2>
 *
 * <p>{@link #syncNow()} and {@link #probe(String)} <strong>block</strong> — both make a signed HTTP
 * round trip with a retry budget behind it, which at the defaults is tens of seconds. Callers run
 * them on {@code heimdall-io}, never on a server thread. Everything else here is a cheap read and is
 * safe from anywhere.
 */
public interface WhitelistAdmin {

    /** What an installation without the whitelist module answers. */
    WhitelistAdmin NONE = new WhitelistAdmin() {

        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public String stats() {
            return "the whitelist module is not running";
        }

        @Override
        public void clear() {
        }

        @Override
        public int cleanup() {
            return 0;
        }

        @Override
        public void syncNow() {
        }

        @Override
        public LoginProbe probe(String playerName) {
            return LoginProbe.forPlayer(playerName, "")
                    .allowed(true)
                    .stage("the whitelist module is not running, so nothing gates this login")
                    .build();
        }
    };

    /**
     * Whether the module is enabled right now.
     *
     * <p>{@code false} is an ordinary state — an operator switched it off in the dashboard — so
     * every other method here still answers rather than throwing.
     */
    boolean isAvailable();

    /** A one-line summary of the mirror: how many entries, how many expired, the stored ETag. */
    String stats();

    /**
     * How many players the mirror holds, or {@code -1} when the module is not running.
     *
     * <p>This and the two below are what {@code /hd status} builds its one-row mirror summary from.
     * {@link #stats()} is a display string, and a summary that parsed it would break the first time
     * somebody reworded it.
     */
    default int mirrorEntries() {
        return -1;
    }

    /** When the last successful sync with the bot finished, in epoch millis, or {@code 0} if never. */
    default long lastSyncAtMs() {
        return 0L;
    }

    /**
     * How often the pre-warm poll is meant to sync, in millis, or {@code 0} when it is switched off.
     *
     * <p>{@code 0} means the mirror only fills from individual logins, so its age says nothing and
     * {@code /hd status} does not call it stale.
     */
    default long syncIntervalMs() {
        return 0L;
    }

    /**
     * Empties the mirror, ETag included.
     *
     * <p>Destructive in a way worth naming: with {@code apiFallbackMode: whitelist-only}, an empty
     * mirror means a bot outage in the next few minutes refuses everybody. The pre-warm poll refills
     * it, but not instantly.
     */
    void clear();

    /**
     * Drops entries past their effective expiry.
     *
     * @return how many were removed
     */
    int cleanup();

    /** Pulls the full whitelist from the bot and reconciles it. Blocking; never throws. */
    void syncNow();

    /**
     * Runs the whole login interceptor for a player, without letting it write anything.
     *
     * <p>Blocking, and the reason it is here rather than being assembled by the command: the
     * decision an operator wants to see is the one the interceptor makes, including its fallback
     * mode and its mirror, and reconstructing that in a command handler would be a second
     * implementation of the thing under test.
     *
     * @param playerName the name to probe; resolved to an online player's real UUID when possible
     */
    LoginProbe probe(String playerName);
}
