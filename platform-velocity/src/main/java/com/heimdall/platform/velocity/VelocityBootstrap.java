package com.heimdall.platform.velocity;

import com.heimdall.core.BuildConstants;
import com.heimdall.core.concurrent.HeimdallExecutors;
import com.heimdall.core.config.BootstrapConfig;
import com.heimdall.core.config.BootstrapStore;
import com.heimdall.core.config.ServerRole;
import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.wiring.HeimdallRuntime;
import com.heimdall.platform.common.FloodgateIdentityProvider;
import com.heimdall.platform.common.TunnelSpiService;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.proxy.ProxyServer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Everything proxy initialisation does, in a class that is not the plugin.
 *
 * <p>The mirror of {@code BukkitBootstrap}, and the two are deliberately the same shape: the same
 * order, the same reverse teardown, the same {@link HeimdallRuntime} doing everything that is not
 * platform-specific. v2's two entry points were 1,086 and 1,311 lines of the same wiring written
 * twice, and they had drifted — the Velocity one had fixes the Paper one never got.
 *
 * <p>What is genuinely different here is short enough to list: the role is always
 * {@link ServerRole#GATEKEEPER} unless configured otherwise, there is no chat listener (a proxy
 * cannot cancel signed chat), and text has to cross a shading boundary — see {@link VelocityText}.
 */
final class VelocityBootstrap {

    private final Object plugin;
    private final ProxyServer proxy;
    private final HeimdallLogger logger;
    private final Path dataDirectory;
    private final long startedAtMs = System.currentTimeMillis();

    /**
     * Held rather than kept in a local: a throw part-way through {@link #enable()} would otherwise
     * strand three thread pools with nothing holding a reference to them. Ownership passes to the
     * runtime once it exists, so {@link #disable()} closes these directly only in the window where
     * it does not.
     */
    private HeimdallExecutors executors;

    private VelocityText text;
    private VelocityPlatform platform;
    private HeimdallRuntime runtime;
    private TunnelSpiService spi;

    VelocityBootstrap(Object plugin, ProxyServer proxy, HeimdallLogger logger, Path dataDirectory) {
        this.plugin = plugin;
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    /**
     * Builds and starts everything.
     *
     * <p>Never throws. Velocity logs a plugin whose initialise handler throws and carries on with
     * the plugin half-started, which is strictly worse than a plugin that started in a reduced state
     * and said which one.
     */
    void enable() {
        try {
            Files.createDirectories(dataDirectory);
        } catch (IOException notWritable) {
            logger.warn("could not create " + dataDirectory + " — configuration will not persist: "
                    + notWritable);
        }

        BootstrapStore store = new BootstrapStore(logger, dataDirectory.resolve("bootstrap.yml"));
        // A proxy IS the gatekeeper — there is nothing to detect, and the question the Bukkit side
        // has to answer ("is something in front of me?") has one answer here. An explicit role in
        // bootstrap.yml still wins, for the operator running a proxy behind another proxy.
        // Read once and passed on, rather than loaded again by whoever needs it next.
        BootstrapConfig bootstrap = store.load();
        ServerRole configured = bootstrap.role();
        ServerRole role = configured == ServerRole.AUTO ? ServerRole.GATEKEEPER : configured;
        logger.info("server role: " + role.wireName()
                + (configured == ServerRole.AUTO ? " (a proxy owns the login decision)"
                        : " (set in bootstrap.yml)"));

        text = new VelocityText(logger);

        executors = new HeimdallExecutors(logger);
        platform = new VelocityPlatform(
                plugin, proxy, logger, role, dataDirectory, executors, text);

        runtime = HeimdallRuntime.builder(logger, platform)
                .executors(executors)
                .bootstrapStore(store)
                .identitySource(new VelocityIdentitySource(proxy, role, startedAtMs))
                .healthSource(new VelocityHealthSource(proxy))
                .bedrockIdentityProvider(FloodgateIdentityProvider.create())
                .build();

        registerListeners();
        registerCommand(role);
        spi = TunnelSpiService.install(logger, runtime);

        boolean tapped = platform.attachConsoleTap();
        runtime.start();

        logger.info("Heimdall v" + BuildConstants.VERSION + " enabled — role " + role.wireName()
                + ", text bridge " + (text.isUsable() ? "ok" : "degraded")
                + ", console tap " + (tapped ? "on" : "off"));
    }

    /**
     * Shuts everything down in reverse, and says so.
     *
     * <p>The line it logs is load-bearing beyond operator comfort: the boot-smoke matrix's Velocity
     * rows assert it. Until phase 1c those rows could only check that the <em>proxy</em> shut down
     * gracefully, which proves the proxy was not killed but proves nothing about Heimdall unloading.
     */
    void disable() {
        guarded("uninstalling the tunnel SPI", new Runnable() {
            @Override
            public void run() {
                TunnelSpiService.uninstall(spi);
            }
        });
        spi = null;

        guarded("stopping the runtime", new Runnable() {
            @Override
            public void run() {
                if (runtime != null) {
                    // Closes the executors too — ownership transferred when they were handed to the
                    // builder.
                    runtime.close();
                } else if (executors != null) {
                    // enable() threw between constructing the pools and constructing the runtime.
                    // Nothing else holds them, and daemon pools outliving a failed enable is a leak
                    // per reload.
                    executors.shutdown();
                }
            }
        });
        runtime = null;
        executors = null;

        guarded("closing the platform", new Runnable() {
            @Override
            public void run() {
                if (platform != null) {
                    platform.close();
                }
            }
        });
        platform = null;

        logger.info("Heimdall v" + BuildConstants.VERSION + " shutting down");
    }

    /**
     * Runs one teardown step so its failure cannot skip the steps after it.
     *
     * <p>{@code Throwable}, not {@code RuntimeException}, and on this platform the consequence is
     * directly observable: the banner below is what the boot-smoke matrix's Velocity rows assert on
     * to distinguish "Heimdall unloaded" from "the proxy shut down". An {@code Error} out of a
     * module's {@code disable()} — a {@code NoSuchMethodError} from the reflective text bridge is
     * exactly the shape (departure D44) — would skip the platform close that detaches the root
     * log4j appender AND swallow the line, turning a contained fault into a red smoke row with no
     * obvious cause.
     */
    private void guarded(String what, Runnable step) {
        try {
            step.run();
        } catch (Throwable failed) {
            logger.error(what + " failed; continuing with the rest of shutdown", failed);
        }
    }

    private void registerListeners() {
        proxy.getEventManager().register(
                plugin,
                new VelocityLoginListener(
                        logger, runtime.loginPipeline(), platform.integrations().floodgate(), text));
        // Join and quit, as core's session notifications. PostLoginEvent rather than LoginEvent and
        // DisconnectEvent rather than ServerDisconnectEvent — see VelocitySessionListener for why
        // each of the obvious alternatives is wrong.
        proxy.getEventManager().register(
                plugin, new VelocitySessionListener(logger, runtime.playerSessions(), text));
        // No chat listener, deliberately: a proxy cannot cancel signed chat, so interception belongs
        // to the backend servers. See VelocityLoginListener for the whole reasoning.
    }

    private void registerCommand(ServerRole role) {
        // "heimdallproxy" spelled out, for the same reason the primary verb is /hdp rather than
        // /hd: in a proxied network both plugins are installed and the proxy claims a name before
        // the backend ever sees it, so the two need names that cannot collide — and an operator who
        // does not remember which abbreviation is which has a word to type instead.
        CommandMeta meta = proxy.getCommandManager()
                .metaBuilder("hdp")
                .aliases("heimdallproxy")
                .build();
        proxy.getCommandManager()
                .register(meta, new HeimdallVelocityCommand(runtime, text, role));
    }
}
