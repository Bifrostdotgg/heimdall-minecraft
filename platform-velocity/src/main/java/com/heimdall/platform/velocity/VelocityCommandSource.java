package com.heimdall.platform.velocity;

import com.heimdall.core.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import java.util.UUID;
import net.kyori.adventure.text.Component;

/**
 * A Velocity command source, as a module is allowed to see it.
 *
 * <p>Velocity's own interface is also called {@code CommandSource}, so it is named in full below
 * rather than imported — importing either one would leave the other spelled out anyway, and the
 * fully-qualified name at least says which side of the seam each occurrence is on.
 *
 * <p>The source is held rather than snapshotted: {@code hasPermission} can legitimately answer
 * differently between the invocation and an asynchronous handler's reply, and the later answer is
 * the correct one.
 *
 * <p>Messages cross the shading boundary through {@link VelocityText} (departure D44). If that
 * bridge is unusable the message is dropped rather than thrown — a command that could not print its
 * answer is bad, and a command that took the proxy's command thread down with a
 * {@code NoSuchMethodError} is worse.
 */
final class VelocityCommandSource implements CommandSource {

    private final com.velocitypowered.api.command.CommandSource source;
    private final VelocityText text;

    VelocityCommandSource(com.velocitypowered.api.command.CommandSource source, VelocityText text) {
        this.source = source;
        this.text = text;
    }

    @Override
    public String name() {
        return source instanceof Player ? ((Player) source).getUsername() : "CONSOLE";
    }

    @Override
    public UUID uuid() {
        return source instanceof Player ? ((Player) source).getUniqueId() : null;
    }

    @Override
    public boolean isPlayer() {
        return source instanceof Player;
    }

    @Override
    public boolean hasPermission(String node) {
        // The console is a ConsoleCommandSource, which answers true to everything — same as Bukkit.
        return node == null || node.isEmpty() || source.hasPermission(node);
    }

    @Override
    public void sendMessage(Component message) {
        text.send(source, message);
    }

    @Override
    public String toString() {
        return "VelocityCommandSource{" + name() + "}";
    }
}
