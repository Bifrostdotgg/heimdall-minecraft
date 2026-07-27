package com.heimdall.platform.velocity;

import com.google.inject.Inject;
import com.heimdall.core.BuildConstants;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

/**
 * The Velocity proxy entry point.
 *
 * <p>Phase 0 scaffold: it loads, logs and does nothing else.
 *
 * <p>{@code version} has to be a compile-time constant for the annotation, which is why the build
 * generates {@link BuildConstants} into {@code :core}. Core is Java 8 bytecode and this module is
 * Java 17, but a {@code static final String} lives in the constant pool and is inlined at compile
 * time, so the version crosses the bytecode boundary without a runtime dependency.
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
    private final Logger logger;

    /**
     * Constructed by Velocity's injector.
     *
     * @param proxy the running proxy
     * @param logger the plugin's logger
     */
    @Inject
    public HeimdallVelocityPlugin(ProxyServer proxy, Logger logger) {
        this.proxy = proxy;
        this.logger = logger;
    }

    /** Logs the scaffold banner once the proxy has finished initialising. */
    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        logger.info(
                "Heimdall v{} (phase 0 scaffold) on {}",
                BuildConstants.VERSION,
                proxy.getVersion().getName() + " " + proxy.getVersion().getVersion());
    }
}
