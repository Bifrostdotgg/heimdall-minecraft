package com.heimdall.platform.bukkit;

import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.text.Msg;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Gets a {@link Component} in front of a player, on whatever server this is.
 *
 * <h2>Two paths, and the split is not arbitrary</h2>
 *
 * <p><strong>Chat goes through Adventure.</strong> {@link BukkitAudiences} is what makes hex
 * colours, click actions and hover text work on a modern server while degrading to §-codes on a
 * legacy one, and doing that by hand is the conversion sprawl v3 exists to delete.
 *
 * <p><strong>Kicks go through a legacy string.</strong> Not an oversight: {@code
 * Player#kickPlayer(String)} and {@code AsyncPlayerPreLoginEvent#disallow(Result, String)} take a
 * String on <em>every</em> supported version, including the ones where Adventure would have to
 * reflect into the disconnect packet to do better. A kick screen has no click handlers to lose, so
 * serialising to §-codes costs nothing real and removes a reflective path from the login route —
 * which is the one route where a failure is a player who cannot join rather than a message that
 * renders plainly.
 *
 * <h2>Audiences are optional</h2>
 *
 * <p>{@code BukkitAudiences.create} reflects into CraftBukkit internals, and a server it has never
 * seen can make it throw. That is caught: the messenger then serialises to §-codes and sends the
 * String, which is what v2 did on every version anyway. A plugin that failed to enable because a
 * text library could not introspect the server would be a far worse trade than plain-looking chat.
 */
final class BukkitMessenger implements AutoCloseable {

    private final HeimdallLogger logger;

    /** {@code null} when Adventure could not attach to this server. */
    private final BukkitAudiences audiences;

    BukkitMessenger(Plugin plugin, HeimdallLogger logger) {
        this.logger = logger;
        this.audiences = tryCreate(plugin, logger);
    }

    private static BukkitAudiences tryCreate(Plugin plugin, HeimdallLogger logger) {
        try {
            return BukkitAudiences.create(plugin);
        } catch (Throwable unsupported) {
            logger.warn("Adventure could not attach to this server; messages will be sent as "
                    + "legacy colour codes: " + unsupported);
            return null;
        }
    }

    /** Sends a message to anyone who can receive one — a player, the console, a command block. */
    void send(CommandSender recipient, Component message) {
        if (recipient == null || message == null) {
            return;
        }
        BukkitAudiences live = audiences;
        if (live != null) {
            try {
                live.sender(recipient).sendMessage(message);
                return;
            } catch (RuntimeException degraded) {
                logger.debug(() -> "Adventure send failed, falling back to legacy: " + degraded);
            }
        }
        recipient.sendMessage(Msg.toLegacy(message));
    }

    /**
     * Removes a player from the server.
     *
     * <p>Bukkit's kick API is main-thread-only, so callers reach this through
     * {@code SchedulerBridge.runOnEntityThread} rather than calling it directly.
     */
    void kick(Player player, Component reason) {
        if (player == null || !player.isOnline()) {
            return;
        }
        player.kickPlayer(Msg.toLegacy(reason));
    }

    @Override
    public void close() {
        BukkitAudiences live = audiences;
        if (live == null) {
            return;
        }
        try {
            live.close();
        } catch (RuntimeException ignored) {
            // Closing on the way out is housekeeping; a failure here must not become a disable-time
            // stack trace in a server log that the operator then reports as a Heimdall crash.
        }
    }
}
