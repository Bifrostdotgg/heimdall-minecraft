package com.heimdall.platform.velocity;

import com.heimdall.core.BuildConstants;
import com.heimdall.core.concurrent.HeimdallExecutors;
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
        ServerRole configured = store.load().role();
        ServerRole role = configured == ServerRole.AUTO ? ServerRole.GATEKEEPER : configured;
        logger.info("server role: " + role.wireName()
                + (configured == ServerRole.AUTO ? " (a proxy owns the login decision)"
                        : " (set in bootstrap.yml)"));

        text = new VelocityText(logger);

        HeimdallExecutors executors = new HeimdallExecutors(logger);
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
        TunnelSpiService.uninstall(spi);
        spi = null;
        if (runtime != null) {
            // Closes the executors too — ownership transferred when they were handed to the builder.
            runtime.close();
            runtime = null;
        }
        if (platform != null) {
            platform.close();
            platform = null;
        }
        logger.info("Heimdall v" + BuildConstants.VERSION + " shutting down");
    }

    private void registerListeners() {
        proxy.getEventManager().register(
                plugin,
                new VelocityLoginListener(
                        logger, runtime.loginPipeline(), platform.integrations().floodgate(), text));
        // No chat listener, deliberately: a proxy cannot cancel signed chat, so interception belongs
        // to the backend servers. See VelocityLoginListener for the whole reasoning.
    }

    private void registerCommand(ServerRole role) {
        CommandMeta meta = proxy.getCommandManager().metaBuilder("hdp").build();
        proxy.getCommandManager()
                .register(meta, new HeimdallVelocityCommand(runtime, text, role));
    }
}
