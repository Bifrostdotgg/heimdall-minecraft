package com.heimdall.platform.bukkit;

import com.heimdall.core.command.CommandSource;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * A Bukkit {@code CommandSender}, as a module is allowed to see it.
 *
 * <p>Holds the sender itself rather than a snapshot of its fields, because the answer to
 * {@code hasPermission} can change between the invocation and whenever an asynchronous handler gets
 * round to asking — a staff member demoted mid-command should not still be staff by the time the
 * reply lands.
 *
 * <p>{@link #uuid()} is {@code null} for the console and for a command block. That is what
 * {@code /offend} relies on to record "issued by Console" rather than fabricating an identity, which
 * is the bug behind issue #797 / MC-7 in a different place.
 */
final class BukkitCommandSource implements CommandSource {

    private final CommandSender sender;
    private final BukkitMessenger messenger;

    BukkitCommandSource(CommandSender sender, BukkitMessenger messenger) {
        this.sender = sender;
        this.messenger = messenger;
    }

    /** The sender underneath, for the registrar's own use. Never handed to a module. */
    CommandSender sender() {
        return sender;
    }

    @Override
    public String name() {
        return sender.getName();
    }

    @Override
    public UUID uuid() {
        return sender instanceof Player ? ((Player) sender).getUniqueId() : null;
    }

    @Override
    public boolean isPlayer() {
        return sender instanceof Player;
    }

    @Override
    public boolean hasPermission(String node) {
        // Bukkit's own answer, which on a server with a permissions plugin is that plugin's. The
        // console answers true to everything, which is the behaviour every operator expects.
        return node == null || node.isEmpty() || sender.hasPermission(node);
    }

    @Override
    public void sendMessage(Component message) {
        messenger.send(sender, message);
    }

    @Override
    public String toString() {
        return "BukkitCommandSource{" + sender.getName() + "}";
    }
}
