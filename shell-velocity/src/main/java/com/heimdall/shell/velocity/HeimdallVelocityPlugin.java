package com.heimdall.shell.velocity;

import com.google.inject.Inject;
import com.heimdall.shell.ShellBuildConstants;
import com.heimdall.shell.contract.ShellContract;
import com.heimdall.shell.hotswap.ShellHost;
import com.heimdall.shell.hotswap.ShellLog;
import com.heimdall.shell.hotswap.ShellPlatform;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import org.slf4j.Logger;

/**
 * The Velocity entry point, and the hot-swap shell on this platform (departure D87).
 *
 * <p>The id stays {@code heimdall}, so the data directory and every operator's configuration stay
 * where they were. The version is the shell's: it is a compile-time constant generated into
 * {@code :shell-common}, because the annotation needs one and the core's own constant now lives in a
 * different artifact.
 *
 * <p>Velocity registers this instance's {@code @Subscribe} methods for the life of the proxy, which
 * is exactly right for a shell: initialise and shutdown are permanent, and nothing a core does can
 * take them away. Core listeners never use this route; they register functional handlers they can
 * remove again (see the core's {@code VelocityEvents}).
 *
 * <p><strong>The shutdown handler is not optional.</strong> Without it Velocity unloads the plugin
 * without telling it, so nothing would stop the core's executors, socket or log4j appender.
 */
@Plugin(
        id = "heimdall",
        name = "Heimdall",
        version = ShellBuildConstants.VERSION,
        description = "Discord-to-Minecraft whitelist, role sync, punishments and console relay",
        url = "https://bifrost.gg",
        authors = {"Bifrost"})
public final class HeimdallVelocityPlugin {

    private final ProxyServer proxy;
    private final Logger slf4j;
    private final Path dataDirectory;
    private final ShellLog log;

    private ShellHost host;

    /**
     * Constructed by Velocity's injector.
     *
     * @param proxy the running proxy
     * @param logger the plugin's own logger
     * @param dataDirectory the plugin's directory, which Velocity derives from the id
     */
    @Inject
    public HeimdallVelocityPlugin(
            ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.slf4j = logger;
        this.dataDirectory = dataDirectory;
        this.log = new Slf4jShellLog(logger);
    }

    /** Loads and starts the core once the proxy has finished initialising. */
    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        try {
            host = new ShellHost(new Platform(), ShellBuildConstants.VERSION);
            host.boot();
        } catch (Throwable failed) {
            slf4j.error("Heimdall's shell could not start; the proxy is unaffected", failed);
        }
    }

    /** Stops the core while the proxy is still up enough to log about it. */
    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        ShellHost stopping = host;
        host = null;
        if (stopping == null) {
            return;
        }
        try {
            stopping.shutdown();
        } catch (Throwable failed) {
            slf4j.error("Heimdall did not shut down cleanly", failed);
        }
    }

    /**
     * The jar this class was loaded from.
     *
     * <p>Through the code source rather than {@code PluginContainer.getDescription().getSource()},
     * which would need the plugin manager to have finished registering this instance; the code
     * source is fixed the moment the class exists. {@code null} if the platform hides it.
     */
    private static File ownJar() {
        try {
            CodeSource source = HeimdallVelocityPlugin.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                return null;
            }
            File file = new File(source.getLocation().toURI());
            return file.isFile() ? file : null;
        } catch (Throwable unavailable) {
            return null;
        }
    }

    /** What {@link ShellHost} needs to know about this proxy. */
    private final class Platform implements ShellPlatform {

        @Override
        public String name() {
            return ShellContract.PLATFORM_VELOCITY;
        }

        @Override
        public Object plugin() {
            return HeimdallVelocityPlugin.this;
        }

        @Override
        public Object server() {
            return proxy;
        }

        @Override
        public Object logger() {
            return slf4j;
        }

        @Override
        public Path dataDirectory() {
            try {
                Files.createDirectories(dataDirectory);
            } catch (IOException notWritable) {
                log.warn("could not create " + dataDirectory + ": " + notWritable.getMessage());
            }
            return dataDirectory;
        }

        @Override
        public File shellJar() {
            return ownJar();
        }

        @Override
        public ShellLog log() {
            return log;
        }

        @Override
        public ClassLoader shellLoader() {
            return HeimdallVelocityPlugin.class.getClassLoader();
        }
    }
}
