package com.heimdall.platform.velocity;

import com.heimdall.core.BuildConstants;
import com.heimdall.core.admin.AdminCommand;
import com.heimdall.core.admin.AdminContext;
import com.heimdall.core.concurrent.HeimdallExecutors;
import com.heimdall.core.config.BootstrapConfig;
import com.heimdall.core.config.BootstrapStore;
import com.heimdall.core.config.ServerRole;
import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.migrate.MigrationResult;
import com.heimdall.core.util.Registration;
import com.heimdall.core.wiring.HeimdallRuntime;
import com.heimdall.core.wiring.MigrationBoot;
import com.heimdall.core.wiring.UpdateWiring;
import com.heimdall.platform.common.CoreRegistrations;
import com.heimdall.platform.common.FloodgateIdentityProvider;
import com.heimdall.platform.common.HeimdallModules;
import com.heimdall.platform.common.TunnelSpiService;
import com.heimdall.shell.contract.ShellContext;
import com.velocitypowered.api.event.EventManager;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

/**
 * Everything proxy initialisation does, in a class that is not the plugin.
 *
 * <p>The mirror of {@code BukkitBootstrap}, and the two are deliberately the same shape: the same
 * order, the same reverse teardown, the same {@link HeimdallRuntime} doing everything that is not
 * platform-specific. v2's two entry points were 1,086 and 1,311 lines of the same wiring written
 * twice, and they had drifted — the Velocity one had fixes the Paper one never got.
 *
 * <p>What is genuinely different here is short enough to list: the role is always
 * {@link ServerRole#GATEKEEPER} unless configured otherwise, the chat listener observes and never
 * cancels (a proxy cannot cancel signed chat), and text has to cross a shading boundary — see
 * {@link VelocityText}.
 *
 * <p>Since the hot-swap split (departure D87) this runs in a core generation that can be stopped
 * while the proxy and the shell's plugin keep running, so every listener, task and command it
 * registers is tracked through {@link CoreRegistrations} and undone in {@link #disable()}. Listeners
 * are functional handlers registered through {@link VelocityEvents}, never annotated listener
 * objects; see that class for why.
 */
final class VelocityBootstrap {

    private final Object plugin;
    private final ProxyServer proxy;
    private final HeimdallLogger logger;
    private final Path dataDirectory;
    private final long startedAtMs = System.currentTimeMillis();

    /** The shell this generation runs under. */
    private final ShellContext shell;

    /** This generation's listeners, timers and bindings, undone newest first on disable. */
    private final CoreRegistrations registrations;

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

    /** The tunnel backend handed to the shell's permanent {@code HeimdallTunnel}. */
    private Registration spi = Registration.NONE;

    /** The {@code /hdp} and {@code /hwl} registrations, unregistered on disable. */
    private Registration adminCommands = Registration.NONE;

    /** The updater's periodic check, its {@code update} subscription and its join notice. */
    private Registration updates = Registration.NONE;

    VelocityBootstrap(
            Object plugin,
            ProxyServer proxy,
            HeimdallLogger logger,
            Path dataDirectory,
            ShellContext shell) {
        this.plugin = plugin;
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.shell = shell;
        this.registrations = new CoreRegistrations(shell);
    }

    /**
     * Builds and starts everything.
     *
     * <p>Expected failures leave a reduced state and say which one. A throw is a genuine bug, and
     * reaches the shell through {@link VelocityCore#start}, which unwinds the half-built generation
     * (departure D87).
     */
    void enable() {
        try {
            Files.createDirectories(dataDirectory);
        } catch (IOException notWritable) {
            logger.warn("could not create " + dataDirectory + " — configuration will not persist: "
                    + notWritable);
        }

        BootstrapStore store = new BootstrapStore(logger, dataDirectory.resolve("bootstrap.yml"));
        // Before the bootstrap is read, because on the first boot after a v2 upgrade this is what
        // writes it. v2's proxy config is a config.json in the sibling plugins/heimdall-whitelist/ —
        // v2's @Plugin id verbatim, because Velocity derives a plugin's directory from its id.
        MigrationResult migration = MigrationBoot.migrate(
                logger, store, dataDirectory, MigrationBoot.V2_VELOCITY_DIRECTORY);
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
                plugin, proxy, logger, role, dataDirectory, executors, text, registrations);

        runtime = HeimdallRuntime.builder(logger, platform)
                .executors(executors)
                .bootstrapStore(store)
                .identitySource(new VelocityIdentitySource(proxy, role, startedAtMs))
                .commandLabel("hdp")
                .healthSource(new VelocityHealthSource(proxy))
                .bedrockIdentityProvider(FloodgateIdentityProvider.create())
                .handoff(shell.handoff())
                .build();

        // Between build() and start(), like the Bukkit side and for the same reason: the first
        // reconcile happens inside start(), and a module registered after it sits STOPPED until the
        // next config push.
        AdminContext.Builder admin = AdminContext.builder(runtime)
                .role(role)
                .pluginVersion(BuildConstants.VERSION);
        HeimdallModules.registerAll(runtime, admin);

        UpdateWiring.Installed update = UpdateWiring.install(
                logger,
                BuildConstants.VERSION,
                runtime,
                new VelocityUpdateInstaller(logger, proxy, plugin, dataDirectory));
        updates = update.periodicChecks();
        admin.updates(update.admin());

        registerListeners();
        registerCommands(admin.build());
        // The HeimdallTunnel other plugins hold is the shell's, published once and never replaced;
        // this hands it this generation's tunnel to forward to (departure D87).
        spi = TunnelSpiService.install(logger, runtime, shell);

        boolean tapped = platform.attachConsoleTap();
        runtime.start();

        // After start(), because it schedules against the runtime's pools and waits for the guild
        // that start() begins resolving. A no-op unless this boot migrated something.
        MigrationBoot.scheduleImport(logger, runtime, migration);

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
        guarded("stopping the update checker", new Runnable() {
            @Override
            public void run() {
                updates.close();
            }
        });
        updates = Registration.NONE;

        guarded("unregistering the admin command", new Runnable() {
            @Override
            public void run() {
                adminCommands.close();
            }
        });
        adminCommands = Registration.NONE;

        guarded("unbinding the tunnel SPI", new Runnable() {
            @Override
            public void run() {
                spi.close();
            }
        });
        spi = Registration.NONE;

        guarded("unregistering listeners and timers", new Runnable() {
            @Override
            public void run() {
                // Before the runtime stops, so no event reaches a runtime that is going away. Never
                // unregisterListeners(plugin): the plugin is the shell's, and that would take the
                // shell's own login gate with it.
                registrations.closeAll(logger);
            }
        });

        guarded("handing state to the next core", new Runnable() {
            @Override
            public void run() {
                if (runtime != null && shell.isSwapping()) {
                    shell.handOff(runtime.exportHandoff());
                }
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

        if (shell.isSwapping()) {
            logger.info("Heimdall core v" + BuildConstants.VERSION + " stopped for a swap");
        } else {
            logger.info("Heimdall v" + BuildConstants.VERSION + " shutting down");
        }
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
        EventManager events = proxy.getEventManager();
        final VelocityLoginListener login = new VelocityLoginListener(
                logger, runtime.loginPipeline(), platform.integrations().floodgate(), text);
        registrations.track(VelocityEvents.listen(
                events, plugin, LoginEvent.class, PostOrder.FIRST, login::onLogin));
        // Join and quit, as core's session notifications. PostLoginEvent rather than LoginEvent and
        // DisconnectEvent rather than ServerDisconnectEvent: see VelocitySessionListener for why
        // each of the obvious alternatives is wrong.
        final VelocitySessionListener sessions =
                new VelocitySessionListener(logger, runtime.playerSessions(), text);
        registrations.track(VelocityEvents.listen(
                events, plugin, PostLoginEvent.class, PostOrder.NORMAL, sessions::onPostLogin));
        registrations.track(VelocityEvents.listen(
                events, plugin, DisconnectEvent.class, PostOrder.NORMAL, sessions::onDisconnect));
        // Chat, OBSERVED. A proxy still cannot cancel signed chat, so interception remains the
        // backends' — this listener reads and touches nothing, which is what makes a proxy-origin
        // Discord relay possible. It is inert unless the bridge module's relayChat setting is on,
        // and that defaults to false on a gatekeeper. See VelocityChatListener, departure D81.
        final VelocityChatListener chat = new VelocityChatListener(logger, runtime.chatPipeline());
        registrations.track(VelocityEvents.listen(
                events, plugin, PlayerChatEvent.class, PostOrder.LAST, chat::onChat));
    }

    /**
     * Binds {@code /hdp} (and {@code /heimdallproxy}), plus the deprecated {@code /hwl} alias.
     *
     * <p>"heimdallproxy" spelled out, for the same reason the primary verb is {@code /hdp} rather
     * than {@code /hd}: in a proxied network both plugins are installed and the proxy claims a name
     * before the backend ever sees it, so the two need names that cannot collide — and an operator
     * who does not remember which abbreviation is which has a word to type instead (departure D47).
     *
     * <p>{@code /hwl} is registered here too. v2 used it on <em>both</em> platforms, so a proxy
     * operator's muscle memory and runbooks say {@code /hwl} just as a backend operator's do; it
     * forwards to this same tree and says once per start that the name changed.
     *
     * <p>Through the shared {@link com.heimdall.core.command.CommandRegistrar}, so the proxy and the
     * backend run the identical command code — which is the whole reason v2's two trees drifted.
     */
    private void registerCommands(AdminContext admin) {
        adminCommands = AdminCommand.install(
                platform.commands(), admin, "hdp", Collections.singletonList("heimdallproxy"));
    }
}
