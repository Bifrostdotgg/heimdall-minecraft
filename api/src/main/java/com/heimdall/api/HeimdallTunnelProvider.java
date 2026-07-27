package com.heimdall.api;

/**
 * The portable way to get hold of the {@link HeimdallTunnel}, on any platform.
 *
 * <p>The Bukkit family has a {@code ServicesManager} and Heimdall registers there as well, because
 * that is where a Bukkit plugin author will look first. Velocity has no equivalent registry at all —
 * v2 simply had no SPI on the proxy — so a static holder is what makes one interface reachable from
 * both.
 *
 * <p>A static singleton is a poor default and a reasonable exception here: there is exactly one
 * Heimdall per server process by construction (it is one plugin, with one socket), the alternative
 * on Velocity is a plugin-specific lookup every consumer would have to special-case, and the
 * lifetime is the plugin's own.
 *
 * <h2>Using it</h2>
 *
 * <pre>{@code
 * HeimdallTunnel tunnel = HeimdallTunnelProvider.get();
 * if (tunnel != null && tunnel.isConnected()) {
 *     tunnel.publish("my.event", payload);
 * }
 * }</pre>
 *
 * <p><strong>Never cache the result across a reload.</strong> Heimdall being disabled and
 * re-enabled installs a new instance and the old one is inert; a consumer holding the old reference
 * would publish into nothing, silently. Ask each time — it is a volatile field read.
 *
 * <p>A plugin that only soft-depends on Heimdall should reach this class reflectively, so it keeps
 * loading on a server where Heimdall is not installed.
 */
public final class HeimdallTunnelProvider {

    private static volatile HeimdallTunnel instance;

    private HeimdallTunnelProvider() {
    }

    /** The running tunnel, or {@code null} when Heimdall is absent or not yet enabled. */
    public static HeimdallTunnel get() {
        return instance;
    }

    /**
     * Installs the instance. Called by Heimdall's platform modules on enable.
     *
     * <p>Public because the platform modules live in other packages, not because it is part of the
     * consumer-facing surface. Calling this from outside Heimdall replaces the real tunnel with
     * something else for every other plugin on the server.
     */
    public static void install(HeimdallTunnel tunnel) {
        instance = tunnel;
    }

    /**
     * Removes {@code tunnel} if it is the one currently installed.
     *
     * <p>Compare-and-clear rather than an unconditional null: a reload that enables the new
     * instance before disabling the old one would otherwise have the old one's teardown wipe the
     * new one's registration, and the symptom is an SPI that works until the first {@code /reload}.
     */
    public static void uninstall(HeimdallTunnel tunnel) {
        if (instance == tunnel) {
            instance = null;
        }
    }
}
