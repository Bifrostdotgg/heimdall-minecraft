package com.heimdall.platform.bungee;

import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.log.JulLogger;
import net.md_5.bungee.api.plugin.Plugin;

/**
 * The BungeeCord proxy entry point.
 *
 * <p>Deliberately a shell, exactly like its Bukkit and Velocity counterparts. Everything it would
 * otherwise do lives in {@link BungeeBootstrap}, which is an ordinary class and can be reasoned about
 * without a running proxy.
 *
 * <p><strong>The plugin's identity comes from {@code bungee.yml}</strong>, not from an annotation.
 * BungeeCord reads {@code bungee.yml} from the jar and falls back to {@code plugin.yml} only if the
 * first is absent — which is exactly why one jar can carry both: with {@code bungee.yml} present the
 * proxy never sees the Bukkit descriptor, and a Bukkit server never looks for the proxy's.
 *
 * <p>The logger is {@link JulLogger} over {@link #getLogger()} rather than a wrapper of its own.
 * BungeeCord's {@code PluginLogger} already prefixes every line with {@code [Heimdall] }, which is
 * the thing the Velocity binding needs its own {@code Slf4jLogger} to achieve — so on this platform
 * core's ordinary JUL logger is already the right answer.
 *
 * <p>Both handlers contain everything. A plugin whose {@code onEnable} throws is left half-started by
 * BungeeCord with the proxy carrying on regardless, which is strictly worse than a plugin that
 * started in a reduced state and said which one.
 */
public final class HeimdallBungeePlugin extends Plugin {

    private HeimdallLogger logger;
    private BungeeBootstrap bootstrap;

    @Override
    public void onEnable() {
        this.logger = new JulLogger(getLogger());
        bootstrap = new BungeeBootstrap(this, getProxy(), logger, getDataFolder().toPath());
        try {
            bootstrap.enable();
        } catch (Throwable failed) {
            logger.error("Heimdall could not start; the proxy is unaffected", failed);
        }
    }

    /**
     * Tears everything down while the proxy is still up enough to log about it.
     *
     * <p><strong>Not optional.</strong> BungeeCord calls this before it closes its IO threads, and it
     * is the only chance to stop the executors, close the tunnel and detach the console handler. It
     * is also where the shutdown banner the boot-smoke matrix asserts on comes from — without it
     * those rows could only prove the <em>proxy</em> stopped gracefully, which says nothing at all
     * about Heimdall unloading.
     */
    @Override
    public void onDisable() {
        if (bootstrap == null) {
            return;
        }
        try {
            bootstrap.disable();
        } catch (Throwable failed) {
            logger.error("Heimdall did not shut down cleanly", failed);
        } finally {
            bootstrap = null;
        }
    }
}
