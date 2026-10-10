package com.heimdall.shell.bukkit;

import com.heimdall.shell.hotswap.ShellAudience;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@link ShellAudience} on the Bukkit family: {@code sendMessage(String)}, which takes {@code §}
 * codes on every supported version.
 */
final class BukkitAudience implements ShellAudience {

    @Override
    public void send(Object sender, String legacyText) {
        if (!(sender instanceof CommandSender)) {
            return;
        }
        try {
            ((CommandSender) sender).sendMessage(legacyText);
        } catch (Throwable gone) {
            // A player who left between the command and the answer. Nothing to tell anyone.
        }
    }

    @Override
    public boolean hasPermission(Object sender, String node) {
        if (!(sender instanceof CommandSender)) {
            return false;
        }
        try {
            return ((CommandSender) sender).hasPermission(node);
        } catch (Throwable unknown) {
            return false;
        }
    }

    @Override
    public void alertOnline(String node, String legacyText) {
        try {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (hasPermission(player, node)) {
                    send(player, legacyText);
                }
            }
        } catch (Throwable unavailable) {
            // The console line the caller already wrote is the alert that matters.
        }
    }
}
