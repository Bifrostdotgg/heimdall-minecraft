package com.heimdall.api;

/**
 * The portable way to get hold of {@link HeimdallPunishments}, on any platform.
 *
 * <p>The same arrangement as {@link HeimdallTunnelProvider}, for the same reasons: the Bukkit family
 * also finds it in the {@code ServicesManager}, and Velocity and BungeeCord have no registry a
 * plugin could look in.
 *
 * <h2>Using it</h2>
 *
 * <pre>{@code
 * HeimdallPunishments punishments = HeimdallPunishmentsProvider.get();
 * if (punishments != null && punishments.isMuted(player.getUniqueId())) {
 *     // refuse
 * }
 * }</pre>
 *
 * <p>The instance is the shell's and survives a hot swap of Heimdall's core, but a plugin disable
 * and re-enable installs a new one. Asking each time is a volatile field read.
 */
public final class HeimdallPunishmentsProvider {

    private static volatile HeimdallPunishments instance;

    private HeimdallPunishmentsProvider() {
    }

    /** The running instance, or {@code null} when Heimdall is absent or not yet enabled. */
    public static HeimdallPunishments get() {
        return instance;
    }

    /**
     * Installs the instance. Called by Heimdall's platform shells on enable.
     *
     * <p>Public because the shells live in other packages, not because it is part of the
     * consumer-facing surface.
     */
    public static void install(HeimdallPunishments punishments) {
        instance = punishments;
    }

    /** Removes {@code punishments} if it is the one installed; see {@link HeimdallTunnelProvider#uninstall}. */
    public static void uninstall(HeimdallPunishments punishments) {
        if (instance == punishments) {
            instance = null;
        }
    }
}
