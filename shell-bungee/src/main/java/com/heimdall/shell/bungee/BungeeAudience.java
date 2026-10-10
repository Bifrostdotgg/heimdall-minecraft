package com.heimdall.shell.bungee;

import com.heimdall.shell.hotswap.ShellAudience;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;

/** {@link ShellAudience} on BungeeCord, through its own legacy-text components. */
final class BungeeAudience implements ShellAudience {

    private final ProxyServer proxy;

    BungeeAudience(ProxyServer proxy) {
        this.proxy = proxy;
    }

    @Override
    public void send(Object sender, String legacyText) {
        if (!(sender instanceof CommandSender)) {
            return;
        }
        try {
            ((CommandSender) sender).sendMessage(TextComponent.fromLegacyText(legacyText));
        } catch (Throwable gone) {
            // A player who left between the command and the answer.
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
            for (ProxiedPlayer player : proxy.getPlayers()) {
                if (hasPermission(player, node)) {
                    send(player, legacyText);
                }
            }
        } catch (Throwable unavailable) {
            // The console line the caller already wrote is the alert that matters.
        }
    }
}
