package com.heimdall.platform.bukkit;

import com.heimdall.api.HeimdallTunnel;
import com.heimdall.core.BuildConstants;
import com.heimdall.core.concurrent.HeimdallExecutors;
import com.heimdall.core.config.BootstrapConfig;
import com.heimdall.core.config.BootstrapStore;
import com.heimdall.core.config.ServerRole;
import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.log.JulLogger;
import com.heimdall.core.platform.InstanceRoleDetector;
import com.heimdall.core.wiring.HeimdallRuntime;
import com.heimdall.platform.bukkit.adapter.BukkitAdapters;
import com.heimdall.platform.bukkit.adapter.TickSource;
import com.heimdall.platform.common.FloodgateIdentityProvider;
import com.heimdall.platform.common.TunnelSpiService;
import java.io.File;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Everything {@code onEnable} does, in a class that is not a {@code JavaPlugin}.
 *
 * <p>The split is the point of phase 1c. v2's two entry points were 1,086 and 1,311 lines and both
 * were {@code JavaPlugin}/{@code @Plugin} subclasses, which meant none of the wiring could be
 * looked at without a running server — so it was never looked at, and the two copies drifted. Here
 * the {@code JavaPlugin} is a shell that owns the Bukkit lifecycle and nothing else, this class
 * owns the order things are built in, and {@link HeimdallRuntime} owns everything that is not
 * Bukkit-specific at all.
 *
 * <h2>Order, and why it is this one</h2>
 *
 * <ol>
 *   <li><strong>Bootstrap first, for the role only.</strong> An explicitly configured role beats
 *       detection, and the resolved role has to be settled before the platform facade exists —
 *       module eligibility is decided against it.
 *   <li><strong>Executors, then the platform, then the runtime.</strong> The two genuinely need
 *       each other: the console tap and the LuckPerms bridge run work on {@code heimdall-io}, and
 *       the runtime cannot be built without a platform. Creating the pools here and handing
 *       ownership to the runtime is what breaks the cycle without a setter that would let either
 *       object be observed half-built.
 *   <li><strong>Listeners, command, SPI.</strong>
 *   <li><strong>{@code runtime.start()} last</strong>, so nothing can receive an event before the
 *       thing that handles it exists.
 * </ol>
 *
 * <p>Teardown is the reverse of whatever actually got built, and every step is contained. Not "the
 * exact reverse": {@code onEnable} catches everything, so a throw half-way through this leaves a
 * server running with some of it constructed, and {@link #disable()} is the only thing that will
 * ever be called again. It null-guards each step independently rather than assuming the one before
 * it ran.
 */
final class BukkitBootstrap {

    private final JavaPlugin plugin;
    private final HeimdallLogger logger;
    private final long startedAtMs = System.currentTimeMillis();

    /**
     * Held rather than kept in a local, because a throw part-way through {@link #enable()} would
     * otherwise strand three thread pools with nothing holding a reference to them.
     *
     * <p>Ownership passes to the runtime once it is built, so {@link #disable()} closes these
     * directly only in the window where the runtime does not exist yet.
     */
    private HeimdallExecutors executors;

    private BukkitPlatform platform;
    private HeimdallRuntime runtime;
    private TunnelSpiService spi;

    BukkitBootstrap(JavaPlugin plugin) {
        this.plugin = plugin;
        this.logger = new JulLogger(plugin.getLogger());
    }

    /**
     * Builds and starts everything.
     *
     * <p>Never throws. A {@code JavaPlugin} whose {@code onEnable} throws is disabled by the server
     * with a stack trace, and every reason this could fail — no config, no LuckPerms, an
     * unattachable logging backend — is a reason to run in a reduced state and say so, not a reason
     * to leave the operator with no Heimdall and no instruction either.
     */
    void enable() {
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.isDirectory() && !dataFolder.mkdirs()) {
            // Bukkit only creates a plugin's directory if it ships a config.yml, and this one
            // deliberately does not — every setting is either bootstrap.yml or the dashboard's.
            logger.warn("could not create " + dataFolder + " — configuration will not persist");
        }

        BootstrapStore store =
                new BootstrapStore(logger, dataFolder.toPath().resolve("bootstrap.yml"));
        // Read once and passed to both consumers. Two reads would be two chances to disagree about
        // what is on disk if a setup command wrote between them, and it is a file parse on the
        // boot path either way.
        BootstrapConfig bootstrap = store.load();
        ServerRole role = InstanceRoleDetector.resolve(
                bootstrap.role(),
                new BukkitRoleDetector(Bukkit.getWorldContainer(), logger),
                logger);

        executors = new HeimdallExecutors(logger);
        platform = new BukkitPlatform(plugin, logger, role, executors);

        TickSource ticks = BukkitAdapters.tickSource(logger);
        runtime = HeimdallRuntime.builder(logger, platform)
                .executors(executors)
                .bootstrapStore(store)
                .identitySource(new BukkitIdentitySource(role, startedAtMs))
                .healthSource(new BukkitHealthSource(ticks))
                .bedrockIdentityProvider(FloodgateIdentityProvider.create())
                .build();

        registerListeners();
        registerCommand(role);
        registerSpi();

        boolean tapped = platform.attachConsoleTap();
        runtime.start();

        logger.info("Heimdall v" + BuildConstants.VERSION + " enabled — role " + role.wireName()
                + ", ticks via " + ticks.describe() + ", console tap " + (tapped ? "on" : "off"));
    }

    /**
     * Shuts everything down in reverse. Contained step by step; the pools stop last.
     *
     * <p><strong>It also has to unwind a half-built enable.</strong> {@code onEnable} catches
     * everything, so a throw part-way through {@link #enable()} leaves the server running with
     * whatever had been constructed by then — pools, an attached appender — and this is the only
     * thing that will ever be called again. So every step is null-guarded independently rather than
     * assuming the previous one ran, and the executors are closed directly when the runtime that
     * would otherwise own them was never built.
     */
    void disable() {
        guarded("uninstalling the tunnel SPI", new Runnable() {
            @Override
            public void run() {
                TunnelSpiService.uninstall(spi);
            }
        });
        spi = null;

        guarded("unregistering Bukkit services", new Runnable() {
            @Override
            public void run() {
                // Bukkit unregisters a disabling plugin's services itself; this is belt and braces
                // for the /reload path, where it does not always get that far.
                Bukkit.getServicesManager().unregisterAll(plugin);
            }
        });

        guarded("stopping the runtime", new Runnable() {
            @Override
            public void run() {
                if (runtime != null) {
                    // Closes the executors too — ownership transferred when they were handed to the
                    // builder.
                    runtime.close();
                } else if (executors != null) {
                    // enable() threw between constructing the pools and constructing the runtime.
                    // Nothing else holds them, and three pools of daemon threads outliving a failed
                    // enable is a leak per /reload.
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
     * <p>{@code Throwable}, not {@code RuntimeException}. What actually escapes on the way out is a
     * {@code NoSuchMethodError} or {@code NoClassDefFoundError} from an API that moved between
     * server versions — the failure class departures D43, D44 and D45 are about, and the one this
     * platform's listeners already catch. Left uncontained it skips the platform close, which is
     * what detaches the root log4j appender: a module throwing an {@code Error} from
     * {@code disable()} would leak one appender per {@code /reload} and swallow the shutdown banner
     * the boot-smoke matrix asserts on.
     *
     * <p>The field is nulled by the caller after each step rather than inside it, so a step that
     * fails part-way still leaves nothing for a second {@code disable()} to trip over.
     */
    private void guarded(String what, Runnable step) {
        try {
            step.run();
        } catch (Throwable failed) {
            logger.error(what + " failed; continuing with the rest of shutdown", failed);
        }
    }

    // ── Wiring ───────────────────────────────────────────────────────────────

    private void registerListeners() {
        Bukkit.getPluginManager().registerEvents(
                new BukkitLoginListener(
                        logger, runtime.loginPipeline(), platform.integrations().floodgate()),
                plugin);
        Bukkit.getPluginManager().registerEvents(
                new BukkitChatListener(logger, runtime.chatPipeline(), platform.messenger()),
                plugin);
        // Phase 1c deliberately shipped no join/quit listeners rather than dead ones; the whitelist
        // mirror's extension windows are the first real consumer and arrive in 1d. See seam S1.
        Bukkit.getPluginManager().registerEvents(
                new BukkitSessionListener(
                        logger, runtime.playerSessions(), platform.playerDirectory()),
                plugin);
    }

    private void registerCommand(ServerRole role) {
        PluginCommand command = plugin.getCommand("hd");
        if (command == null) {
            // Not fatal, but silent otherwise: a plugin.yml the entry point never claims produces a
            // command that answers "Unknown command" with nothing anywhere to explain it.
            logger.warn("plugin.yml does not declare the 'hd' command — /hd will not work");
            return;
        }
        command.setExecutor(new HeimdallBukkitCommand(runtime, platform.messenger(), role));
    }

    private void registerSpi() {
        spi = TunnelSpiService.install(logger, runtime);
        // The ServicesManager is Bukkit's own idiom and where a Bukkit plugin author looks first;
        // HeimdallTunnelProvider, installed above, is the portable route that also works on Velocity.
        Bukkit.getServicesManager()
                .register(HeimdallTunnel.class, spi, plugin, ServicePriority.Normal);
    }
}
