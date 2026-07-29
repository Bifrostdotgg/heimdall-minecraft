package com.heimdall.platform.velocity;

import com.heimdall.core.platform.PlayerHandle;
import com.velocitypowered.api.proxy.Player;
import java.util.UUID;
import net.kyori.adventure.text.Component;

/**
 * One connected player, as platform-free code sees them.
 *
 * <p>No thread hop anywhere. A proxy has no main thread and no tick loop — every Velocity API here
 * is safe from any thread by design — so the hop the Bukkit handle needs would be pure latency.
 *
 * <p>Text goes through {@link VelocityText}, which is the only part of this that is not a direct
 * call; see that class for why a {@code Component} cannot cross this boundary as one.
 */
final class VelocityPlayerHandle implements PlayerHandle {

    private final Player player;
    private final VelocityText text;

    VelocityPlayerHandle(Player player, VelocityText text) {
        this.player = player;
        this.text = text;
    }

    @Override
    public UUID uuid() {
        return player.getUniqueId();
    }

    @Override
    public String name() {
        return player.getUsername();
    }

    @Override
    public void kick(Component reason) {
        // A player who has already disconnected is not an error — the race between a role sync and
        // a quit is the ordinary case, and Velocity ignores a disconnect on a dead connection.
        text.disconnect(player, reason);
    }

    @Override
    public void sendMessage(Component message) {
        text.send(player, message);
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
     * {@link VelocityPlayerDirectory#describe} lives inside the platform module where a Velocity
     * {@code Player} is an ordinary type.
     */
    Player player() {
        return player;
    }

    @Override
    public String toString() {
        return "VelocityPlayerHandle{" + player.getUsername() + "/" + player.getUniqueId() + "}";
    }
}
