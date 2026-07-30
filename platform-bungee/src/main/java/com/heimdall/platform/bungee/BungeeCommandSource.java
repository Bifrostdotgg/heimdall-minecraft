package com.heimdall.platform.bungee;

import com.heimdall.core.command.CommandSource;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.md_5.bungee.api.connection.ProxiedPlayer;

/**
 * A BungeeCord command sender, as a module is allowed to see it.
 *
 * <p>The sender is held rather than snapshotted: {@code hasPermission} can legitimately answer
 * differently between the invocation and an asynchronous handler's reply, and the later answer is the
 * correct one.
 *
 * <p>{@code "CONSOLE"} for a non-player sender rather than {@code sender.getName()}, which on
 * BungeeCord's console is the string {@code "CONSOLE"} anyway but on a command block or a
 * plugin-supplied sender is whatever that plugin chose. The three platforms report the same word for
 * the same thing, which is what makes an audit line comparable across a network.
 */
final class BungeeCommandSource implements CommandSource {

    private final net.md_5.bungee.api.CommandSender sender;
    private final BungeeText text;

    BungeeCommandSource(net.md_5.bungee.api.CommandSender sender, BungeeText text) {
        this.sender = sender;
        this.text = text;
    }

    @Override
    public String name() {
        return sender instanceof ProxiedPlayer ? ((ProxiedPlayer) sender).getName() : "CONSOLE";
    }

    @Override
    public UUID uuid() {
        return sender instanceof ProxiedPlayer ? ((ProxiedPlayer) sender).getUniqueId() : null;
    }

    @Override
    public boolean isPlayer() {
        return sender instanceof ProxiedPlayer;
    }

    @Override
    public boolean hasPermission(String node) {
        // BungeeCord's console sender answers true to everything, same as Bukkit's and Velocity's.
        return node == null || node.isEmpty() || sender.hasPermission(node);
    }

    @Override
    public void sendMessage(Component message) {
        try {
            sender.sendMessage(text.toComponents(message));
        } catch (RuntimeException gone) {
            // A player who disconnected between issuing a command and its asynchronous reply. A
            // command that could not print its answer is bad; one that took the proxy's command
            // thread down with it is worse.
        }
    }

    @Override
    public String toString() {
        return "BungeeCommandSource{" + name() + "}";
    }
}
