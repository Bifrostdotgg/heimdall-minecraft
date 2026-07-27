package com.heimdall.platform.velocity;

import com.google.inject.Inject;
import com.heimdall.core.BuildConstants;
import com.heimdall.core.log.HeimdallLogger;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import java.nio.file.Path;
import org.slf4j.Logger;

/**
 * The Velocity proxy entry point.
 *
 * <p>Deliberately a shell, exactly like its Bukkit counterpart. Everything it would otherwise do
 * lives in {@link VelocityBootstrap}, which is an ordinary class and can be reasoned about without a
 * running proxy. v2's equivalent was 1,311 lines.
 *
 * <p>{@code version} has to be a compile-time constant for the annotation, which is why the build
 * generates {@link BuildConstants} into {@code :core}. Core is Java 8 bytecode and this module is
 * Java 17, but a {@code static final String} lives in the constant pool and is inlined at compile
 * time, so the version crosses the bytecode boundary without a runtime dependency.
 *
 * <p><strong>The shutdown handler is not optional.</strong> Without it Velocity unloads the plugin
 * without telling it, so nothing stops the executors, the socket or the log4j appender — and the
 * boot-smoke matrix could only assert that the <em>proxy</em> stopped gracefully, which proves the
 * proxy was not killed and proves nothing at all about Heimdall.
 */
@Plugin(
        id = "heimdall",
        name = "Heimdall",
        version = BuildConstants.VERSION,
        description = "Discord-to-Minecraft whitelist, role sync, punishments and console relay",
        url = "https://bifrost.gg",
        authors = {"Bifrost"})
public final class HeimdallVelocityPlugin {

    private final ProxyServer proxy;
    private final Logger slf4j;
    private final HeimdallLogger logger;
    private final Path dataDirectory;

    private VelocityBootstrap bootstrap;

    /**
     * Constructed by Velocity's injector.
     *
     * @param proxy the running proxy
     * @param logger the plugin's own logger, which is what gets {@code [heimdall]} onto every line
     * @param dataDirectory the plugin's directory, which Velocity supplies rather than deriving
     */
    @Inject
    public HeimdallVelocityPlugin(
            ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.slf4j = logger;
        this.logger = new Slf4jLogger(logger);
        this.dataDirectory = dataDirectory;
    }

    /** Builds and starts everything, once the proxy has finished initialising. */
    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        bootstrap = new VelocityBootstrap(this, proxy, logger, dataDirectory);
        try {
            bootstrap.enable();
        } catch (Throwable failed) {
            slf4j.error("Heimdall could not start; the proxy is unaffected", failed);
        }
    }

    /** Tears everything down while the proxy is still up enough to log about it. */
    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (bootstrap == null) {
            return;
        }
        try {
            bootstrap.disable();
        } catch (Throwable failed) {
            slf4j.error("Heimdall did not shut down cleanly", failed);
        } finally {
            bootstrap = null;
        }
    }
}
