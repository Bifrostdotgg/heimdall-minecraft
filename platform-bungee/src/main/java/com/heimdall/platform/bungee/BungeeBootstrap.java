package com.heimdall.platform.bungee;

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
import com.heimdall.platform.common.ShellHotSwap;
import com.heimdall.platform.common.TunnelSpiService;
import com.heimdall.shell.contract.ShellContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;

/**
 * Everything proxy initialisation does, in a class that is not the plugin.
 *
 * <p>The mirror of {@code VelocityBootstrap}, deliberately down to the order of the steps and the
 * reverse teardown. v2's two entry points were 1,086 and 1,311 lines of the same wiring written
 * twice, and they had drifted; a third copy would have been the same mistake with more of it.
 *
 * <p>What is genuinely different from the Velocity file is short enough to list, and none of it is
 * about this being a proxy — that part is identical:
 *
 * <ul>
 *   <li>the migration passes {@link MigrationBoot#NO_V2_DIRECTORY}, because v2 never shipped a
 *       BungeeCord build and there is no sibling directory to name (departure D78);
 *   <li>the login listener is handed this plugin instance, because BungeeCord's asynchronous login
 *       gate is keyed on the plugin that registered the intent (departure D75);
 *   <li>the console tap is the JUL one, and it is attached to the proxy's own logger
 *       (departure D77).
 * </ul>
 */
final class BungeeBootstrap {

    private final Plugin plugin;
    private final ProxyServer proxy;
    private final HeimdallLogger logger;
    private final Path dataDirectory;
    private final long startedAtMs = System.currentTimeMillis();

    /** The shell this generation runs under. */
    private final ShellContext shell;

    /**
     * This generation's listeners, timers and bindings, undone newest first on disable. Since the
     * hot-swap split (departure D87) a generation can stop while the proxy and the shell's plugin
     * keep running, so nothing is left for BungeeCord to clean up on a plugin disable.
     */
    private final CoreRegistrations registrations;

    /**
     * Held rather than kept in a local: a throw part-way through {@link #enable()} would otherwise
     * strand three thread pools with nothing holding a reference to them. Ownership passes to the
     * runtime once it exists, so {@link #disable()} closes these directly only in the window where
     * it does not.
     */
    private HeimdallExecutors executors;

    private BungeeText text;
    private BungeePlatform platform;
    private HeimdallRuntime runtime;

    /** The tunnel backend handed to the shell's permanent {@code HeimdallTunnel}. */
    private Registration spi = Registration.NONE;

    /** The {@code /hdp} and {@code /hwl} registrations, unregistered on disable. */
    private Registration adminCommands = Registration.NONE;

    /** The updater's periodic check, its {@code update} subscription and its join notice. */
    private Registration updates = Registration.NONE;

    BungeeBootstrap(
            Plugin plugin,
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
     * <p>Throws only when the core cannot run at all; {@link BungeeCore#start} reports that to the
     * shell, which unwinds the half-built generation (departure D87).
     */
    void enable() {
        try {
            Files.createDirectories(dataDirectory);
        } catch (IOException notWritable) {
            logger.warn("could not create " + dataDirectory + " — configuration will not persist: "
                    + notWritable);
        }

        BootstrapStore store = new BootstrapStore(logger, dataDirectory.resolve("bootstrap.yml"));
        // Still called, even though there is no v2 Bungee install anywhere in the world: this is what
        // migrates a config an operator dropped into plugins/Heimdall/ by hand, and — more usefully —
        // what produces the near-miss line for an operator who copied a whole plugins/HeimdallWhitelist/
        // across from a backend expecting it to be picked up (departure D70).
        MigrationResult migration = MigrationBoot.migrate(
                logger, store, dataDirectory, MigrationBoot.NO_V2_DIRECTORY);
        // A proxy IS the gatekeeper — there is nothing to detect, and the question the Bukkit side
        // has to answer ("is something in front of me?") has one answer here. An explicit role in
        // bootstrap.yml still wins, for the operator running a proxy behind another proxy.
        BootstrapConfig bootstrap = store.load();
        ServerRole configured = bootstrap.role();
        ServerRole role = configured == ServerRole.AUTO ? ServerRole.GATEKEEPER : configured;
        logger.info("server role: " + role.wireName()
                + (configured == ServerRole.AUTO ? " (a proxy owns the login decision)"
                        : " (set in bootstrap.yml)"));

        text = new BungeeText();

        executors = new HeimdallExecutors(logger);
        platform = new BungeePlatform(
                plugin, proxy, logger, role, dataDirectory, executors, text, registrations);

        runtime = HeimdallRuntime.builder(logger, platform)
                .executors(executors)
                .bootstrapStore(store)
                .identitySource(new BungeeIdentitySource(proxy, role, startedAtMs))
                .commandLabel("hdp")
                .healthSource(new BungeeHealthSource(proxy))
                .bedrockIdentityProvider(FloodgateIdentityProvider.create())
                .handoff(shell.handoff())
                .build();

        // Between build() and start(), like the other two bootstraps and for the same reason: the
        // first reconcile happens inside start(), and a module registered after it sits STOPPED until
        // the next config push.
        AdminContext.Builder admin = AdminContext.builder(runtime)
                .role(role)
                .pluginVersion(BuildConstants.VERSION)
                .core(shell.core().toString(), shell.shellVersion());
        HeimdallModules.registerAll(runtime, admin);

        UpdateWiring.Installed update = UpdateWiring.install(
                logger,
                BuildConstants.VERSION,
                runtime,
                new BungeeUpdateInstaller(logger, plugin, dataDirectory),
                new ShellHotSwap(shell));
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
                + ", text via legacy components, console tap " + (tapped ? "on" : "off"));
    }

    /**
     * Shuts everything down in reverse, and says so.
     *
     * <p>The line it logs is load-bearing beyond operator comfort: the boot-smoke matrix's BungeeCord
     * rows assert it, and without it those rows could only check that the <em>proxy</em> shut down
     * gracefully — which proves the proxy was not killed and proves nothing about Heimdall unloading.
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

        guarded("unregistering listeners and timers", new Runnable() {
            @Override
            public void run() {
                // Before the runtime stops, and explicitly rather than left to the proxy.
                //
                // BungeeCord unregisters a disabling plugin's listeners only on its OWN shutdown
                // path; a plugin manager that disables one plugin in isolation does not. A login
                // listener left registered after the pools have gone would register an intent and
                // then fail to submit the work that completes it — and an uncompleted intent hangs
                // that player's connection with no timeout anywhere to rescue it. The listener
                // handles that case itself (departure D75), but not being registered at all is
                // better than relying on it.
                //
                // One listener at a time, never unregisterListeners(plugin): since departure D87 the
                // plugin is the shell's, and that call would take the shell's own login gate with it.
                registrations.closeAll(logger);
            }
        });

        guarded("unbinding the tunnel SPI", new Runnable() {
            @Override
            public void run() {
                spi.close();
            }
        });
        spi = Registration.NONE;

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
     * <p>{@code Throwable}, not {@code RuntimeException}: the failure class worth being careful about
     * on a decade-spanning platform API is {@code NoSuchMethodError}, which is an {@code Error} and
     * sails past a {@code RuntimeException} catch. Uncontained, it would skip the platform close that
     * detaches the console handler AND swallow the banner below, turning a contained fault into a red
     * smoke row with no obvious cause.
     */
    private void guarded(String what, Runnable step) {
        try {
            step.run();
        } catch (Throwable failed) {
            logger.error(what + " failed; continuing with the rest of shutdown", failed);
        }
    }

    /** Registers {@code listener} and tracks a handle that unregisters exactly it. */
    private void listen(final Listener listener) {
        proxy.getPluginManager().registerListener(plugin, listener);
        registrations.track(Registration.once(new Runnable() {
            @Override
            public void run() {
                proxy.getPluginManager().unregisterListener(listener);
            }
        }));
    }

    private void registerListeners() {
        // The login decision, bound to the shell's permanent LoginEvent listener, which owns the
        // event's intent and the thread the decision runs on (departure D87).
        registrations.keep(shell.bindLoginGate(new BungeeLoginListener(
                logger, runtime.loginPipeline(), platform.integrations().floodgate(), text)));
        listen(new BungeeSessionListener(logger, runtime.playerSessions(), text));
        // Chat, OBSERVED. A proxy still cannot cancel signed chat, so interception remains the
        // backends' — this listener reads and touches nothing, which is what makes a proxy-origin
        // Discord relay possible. It is inert unless the bridge module's relayChat setting is on,
        // and that defaults to false on a gatekeeper. See BungeeChatListener, departure D81.
        listen(new BungeeChatListener(logger, runtime.chatPipeline()));
    }

    /**
     * Binds {@code /hdp} (and {@code /heimdallproxy}), plus the deprecated {@code /hwl} alias.
     *
     * <p>Exactly what the Velocity binding registers, through the same platform-free
     * {@link com.heimdall.core.command.CommandRegistrar} and the same {@link AdminCommand} tree — so
     * an operator's runbook does not have to know which proxy they are on, and the two proxies cannot
     * drift the way v2's two command trees did (departure D47, D61).
     */
    private void registerCommands(AdminContext admin) {
        adminCommands = AdminCommand.install(
                platform.commands(), admin, "hdp", Collections.singletonList("heimdallproxy"));
    }
}
