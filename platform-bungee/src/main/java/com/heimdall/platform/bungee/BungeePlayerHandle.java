package com.heimdall.platform.bungee;

import com.heimdall.core.platform.PlayerHandle;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.md_5.bungee.api.connection.ProxiedPlayer;

/**
 * One connected player, as platform-free code sees them.
 *
 * <p>No thread hop anywhere. A proxy has no main thread and no tick loop — sending to a player is a
 * netty write and disconnecting is a netty close, both safe from any thread — so the hop the Bukkit
 * handle needs would be pure latency.
 *
 * <p>Text goes through {@link BungeeText}, which on this platform is a plain conversion rather than
 * the reflective boundary the Velocity binding needs; see that class.
 */
final class BungeePlayerHandle implements PlayerHandle {

    private final ProxiedPlayer player;
    private final BungeeText text;

    BungeePlayerHandle(ProxiedPlayer player, BungeeText text) {
        this.player = player;
        this.text = text;
    }

    @Override
    public UUID uuid() {
        return player.getUniqueId();
    }

    @Override
    public String name() {
        return player.getName();
    }

    @Override
    public void kick(Component reason) {
        try {
            player.disconnect(text.toComponents(reason));
        } catch (RuntimeException gone) {
            // A player who has already disconnected is not an error — the race between a role sync
            // and a quit is the ordinary case. BungeeCord's own disconnect on a dead channel is a
            // no-op, but a plugin between us and it may not be.
        }
    }

    @Override
    public void sendMessage(Component message) {
        try {
            player.sendMessage(text.toComponents(message));
        } catch (RuntimeException gone) {
            // Same race, same answer: a message to somebody who has left is nothing to report.
        }
    }

    @Override
    public boolean hasPermission(String node) {
        if (node == null || node.isEmpty()) {
            return false;
        }
        try {
            return player.hasPermission(node);
        } catch (RuntimeException gone) {
            return false;
        }
    }

    /**
     * The wrapped player, for the one caller in this package that needs more than the handle offers.
     *
     * <p>Package-private and deliberately absent from {@link PlayerHandle}: the interface has no
     * {@code unwrap()} so that platform-free code cannot reach through it, and
     * {@link BungeePlayerDirectory#describe} lives inside the platform module where a
     * {@code ProxiedPlayer} is an ordinary type.
     */
    ProxiedPlayer player() {
        return player;
    }

    @Override
    public String toString() {
        return "BungeePlayerHandle{" + player.getName() + "/" + player.getUniqueId() + "}";
    }
}
