package com.heimdall.platform.velocity;

import com.heimdall.core.command.CommandRegistrar;
import com.heimdall.core.concurrent.HeimdallExecutors;
import com.heimdall.core.config.ServerRole;
import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.platform.ConsoleBridge;
import com.heimdall.core.platform.Integrations;
import com.heimdall.core.platform.PlatformFacade;
import com.heimdall.core.platform.PlayerDirectory;
import com.heimdall.core.platform.SchedulerBridge;
import com.heimdall.platform.common.Log4jConsoleTap;
import com.velocitypowered.api.proxy.ProxyServer;
import java.nio.file.Path;
import java.util.concurrent.Executor;

/**
 * The proxy's answer to every question core asks about its server.
 *
 * <p>Assembly only, and shorter than its Bukkit counterpart for one structural reason: a proxy has
 * no main thread, so the executor and the scheduler bridge are the same object doing almost
 * nothing, and no accessor here needs a thread hop behind it.
 *
 * <p>The role is fixed at {@link ServerRole#GATEKEEPER} unless {@code bootstrap.yml} says otherwise
 * — a proxy is by definition the thing in front, and it owns the login decision for everything
 * behind it. Detection is settled before this is built; see {@code VelocityBootstrap}.
 */
final class VelocityPlatform implements PlatformFacade, AutoCloseable {

    private final ServerRole role;
    private final Path dataDirectory;
    private final VelocityScheduler scheduler;
    private final VelocityPlayerDirectory players;
    private final Log4jConsoleTap consoleTap;
    private final VelocityConsoleBridge console;
    private final VelocityCommandRegistrar commands;
    private final VelocityIntegrations integrations;

    VelocityPlatform(
            Object plugin,
            ProxyServer proxy,
            HeimdallLogger logger,
            ServerRole role,
            Path dataDirectory,
            HeimdallExecutors executors,
            VelocityText text) {
        this.role = role;
        this.dataDirectory = dataDirectory;
        this.scheduler = new VelocityScheduler(plugin, proxy, logger);
        this.players = new VelocityPlayerDirectory(proxy, text);
        this.consoleTap = new Log4jConsoleTap(logger, executors.io());
        this.console = new VelocityConsoleBridge(proxy, consoleTap);
        this.commands = new VelocityCommandRegistrar(proxy.getCommandManager(), logger, text);
        this.integrations = new VelocityIntegrations(logger, executors.io());
    }

    /**
     * Attaches the console tap, eagerly.
     *
     * <p>Same reasoning as the Bukkit side: attaching a root log4j appender is version-sensitive
     * enough that it should be exercised by every boot the smoke matrix does, rather than first
     * failing when a customer switches the dashboard console on. With no taps registered the
     * appender discards immediately.
     */
    boolean attachConsoleTap() {
        return consoleTap.attach();
    }

    @Override
    public ServerRole role() {
        return role;
    }

    @Override
    public Path dataDirectory() {
        return dataDirectory;
    }

    @Override
    public Executor mainThread() {
        return scheduler;
    }

    @Override
    public PlayerDirectory players() {
        return players;
    }

    @Override
    public SchedulerBridge scheduler() {
        return scheduler;
    }

    @Override
    public ConsoleBridge console() {
        return console;
    }

    @Override
    public CommandRegistrar commands() {
        return commands;
    }

    @Override
    public Integrations integrations() {
        return integrations;
    }

    @Override
    public void close() {
        // The tap is the only thing here holding something the proxy itself owns; leaving a root
        // appender attached to a plugin that has been unloaded is how a reload leaks one per cycle.
        consoleTap.close();
    }
}
