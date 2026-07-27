package com.heimdall.platform.bukkit;

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
import java.nio.file.Path;
import java.util.concurrent.Executor;
import org.bukkit.plugin.Plugin;

/**
 * The Bukkit family's answer to every question core asks about its server.
 *
 * <p>Assembly only — each accessor hands back a collaborator built once in the constructor. The
 * interesting code is in those collaborators; what this class contributes is that they are built in
 * the right order and shut down in the reverse one.
 *
 * <p>The role is resolved by the caller and passed in rather than computed here, because it depends
 * on {@code bootstrap.yml} (an explicit role always wins) and this class is built before the
 * bootstrap is read. Detection itself is {@link BukkitRoleDetector}.
 */
final class BukkitPlatform implements PlatformFacade, AutoCloseable {

    private final ServerRole role;
    private final Path dataDirectory;
    private final BukkitMainThread mainThread;
    private final BukkitMessenger messenger;
    private final BukkitPlayerDirectory players;
    private final Log4jConsoleTap consoleTap;
    private final BukkitConsoleBridge console;
    private final BukkitCommandRegistrar commands;
    private final BukkitIntegrations integrations;

    /**
     * Builds every collaborator, and cleans up after itself if one of them throws.
     *
     * <p>Two of these hold something the <em>server</em> owns — the messenger holds an Adventure
     * audience provider, and the tap is about to be able to hold a root log4j appender. If a
     * constructor after either of them throws, this object never reaches
     * {@code BukkitBootstrap.enable()}'s field, so {@link #close()} will never be called on it and
     * nothing else holds a reference. On a server that is reloaded that is a leak per cycle, of
     * exactly the kind {@code close()} exists to prevent.
     *
     * <p>So the tail is wrapped: anything already built is closed and the original failure is
     * rethrown unchanged, because it is the one the operator needs to see.
     */
    BukkitPlatform(
            Plugin plugin, HeimdallLogger logger, ServerRole role, HeimdallExecutors executors) {
        this.role = role;
        this.dataDirectory = plugin.getDataFolder().toPath();
        this.mainThread = new BukkitMainThread(plugin, logger);
        this.messenger = new BukkitMessenger(plugin, logger);
        BukkitPlayerDirectory builtPlayers = null;
        Log4jConsoleTap builtTap = null;
        try {
            builtPlayers = new BukkitPlayerDirectory(mainThread, messenger);
            builtTap = new Log4jConsoleTap(logger, executors.io());
            this.players = builtPlayers;
            this.consoleTap = builtTap;
            this.console = new BukkitConsoleBridge(logger, mainThread, consoleTap);
            this.commands = new BukkitCommandRegistrar(plugin, logger, messenger);
            this.integrations = new BukkitIntegrations(logger, executors.io());
        } catch (Throwable halfBuilt) {
            closeQuietly(builtTap);
            closeQuietly(messenger);
            throw halfBuilt;
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Throwable ignored) {
            // Unwinding a construction that has already failed. The failure being unwound is the
            // one worth reporting, and it is about to be rethrown; a second one from the cleanup
            // would only replace it with something less useful.
        }
    }

    /**
     * Attaches the console tap.
     *
     * <p>Separate from construction, and done <em>eagerly</em> at enable rather than lazily when the
     * console module first asks. Attaching a root log4j appender is the single most
     * version-sensitive thing this plugin does — the API that works on Minecraft 1.8.8's 2.0-beta9
     * is not the API v2 used — so doing it on every boot means the boot-smoke matrix exercises it on
     * every supported server, instead of the first failure being a customer switching the console on.
     *
     * <p>With no taps registered the appender discards immediately, so the cost of being eager is a
     * volatile read per log line.
     *
     * @return whether the tap attached
     */
    boolean attachConsoleTap() {
        return consoleTap.attach();
    }

    /** The messenger, so the command handler can answer a sender in components. */
    BukkitMessenger messenger() {
        return messenger;
    }

    /** The online-player list, so the session listener can wrap a player it was handed. */
    BukkitPlayerDirectory playerDirectory() {
        return players;
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
        return mainThread;
    }

    @Override
    public PlayerDirectory players() {
        return players;
    }

    @Override
    public SchedulerBridge scheduler() {
        return mainThread;
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
        // Reverse of construction. The tap goes first: it is the only thing here holding a
        // reference the server itself owns, and leaving a root appender attached to a plugin that
        // has been disabled is how a reload leaks one per cycle.
        consoleTap.close();
        messenger.close();
    }
}
