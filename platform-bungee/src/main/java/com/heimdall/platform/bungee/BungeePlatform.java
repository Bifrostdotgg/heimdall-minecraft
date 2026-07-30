package com.heimdall.platform.bungee;

import com.heimdall.core.command.CommandRegistrar;
import com.heimdall.core.concurrent.HeimdallExecutors;
import com.heimdall.core.config.ServerRole;
import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.platform.ConsoleBridge;
import com.heimdall.core.platform.Integrations;
import com.heimdall.core.platform.PlatformFacade;
import com.heimdall.core.platform.PlayerDirectory;
import com.heimdall.core.platform.SchedulerBridge;
import com.heimdall.platform.common.JulConsoleTap;
import java.nio.file.Path;
import java.util.concurrent.Executor;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.plugin.Plugin;

/**
 * The proxy's answer to every question core asks about its server.
 *
 * <p>Assembly only, and the same shape as {@code VelocityPlatform} for the same structural reason: a
 * proxy has no main thread, so the executor and the scheduler bridge are the same object doing almost
 * nothing, and no accessor here needs a thread hop behind it.
 *
 * <p>The role is fixed at {@link ServerRole#GATEKEEPER} unless {@code bootstrap.yml} says otherwise —
 * a proxy is by definition the thing in front, and it owns the login decision for everything behind
 * it. Detection is settled before this is built; see {@code BungeeBootstrap}.
 *
 * <p>The one component that is not the Velocity binding's twin is the console tap: BungeeCord runs
 * {@code java.util.logging} rather than log4j, so it is a {@link JulConsoleTap} — and it is attached
 * to the <em>proxy's own logger</em>, which is the finding that whole class is built on.
 */
final class BungeePlatform implements PlatformFacade, AutoCloseable {

    private final ServerRole role;
    private final Path dataDirectory;
    private final BungeeScheduler scheduler;
    private final BungeePlayerDirectory players;
    private final JulConsoleTap consoleTap;
    private final BungeeConsoleBridge console;
    private final BungeeCommandRegistrar commands;
    private final BungeeIntegrations integrations;

    BungeePlatform(
            Plugin plugin,
            ProxyServer proxy,
            HeimdallLogger logger,
            ServerRole role,
            Path dataDirectory,
            HeimdallExecutors executors,
            BungeeText text) {
        this.role = role;
        this.dataDirectory = dataDirectory;
        this.scheduler = new BungeeScheduler(plugin, proxy, logger);
        this.players = new BungeePlayerDirectory(proxy, text);
        // proxy.getLogger(), never Logger.getLogger("") — BungeeCord's logger has no parent and does
        // not use parent handlers, so the JUL root sees nothing it writes. See JulConsoleTap.
        this.consoleTap = new JulConsoleTap(logger, executors.io(), proxy.getLogger());
        this.console = new BungeeConsoleBridge(proxy, consoleTap);
        this.commands = new BungeeCommandRegistrar(plugin, proxy, logger, text);
        this.integrations = new BungeeIntegrations(logger, executors.io());
    }

    /**
     * Attaches the console tap, eagerly.
     *
     * <p>Same reasoning as the other two platforms: attaching to a server's logging backend is
     * version-sensitive enough that it should be exercised by every boot the smoke matrix does,
     * rather than first failing when a customer switches the dashboard console on. With no taps
     * registered the handler discards immediately.
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
        // The tap is the only thing here holding something the proxy itself owns; leaving a handler
        // on the proxy's logger after the plugin has been unloaded is how a reload leaks one per
        // cycle — and unlike an appender with no taps, a handler on a JUL logger is invoked for every
        // line the proxy writes for the rest of its life.
        consoleTap.close();
    }
}
