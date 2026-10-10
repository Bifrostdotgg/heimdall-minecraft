package com.heimdall.shell.velocity;

import com.heimdall.shell.hotswap.ShellAudience;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * {@link ShellAudience} on Velocity, through the proxy's own Adventure.
 *
 * <p>The shell is the one place in Heimdall that can use Velocity's Adventure directly: the core
 * shades and relocates its own copy, so its {@code Component} is not the proxy's (which is why the
 * core needs {@code VelocityText}), but the shell jar relocates nothing except Gson.
 */
final class VelocityAudience implements ShellAudience {

    private final ProxyServer proxy;

    VelocityAudience(ProxyServer proxy) {
        this.proxy = proxy;
    }

    @Override
    public void send(Object sender, String legacyText) {
        if (!(sender instanceof CommandSource)) {
            return;
        }
        try {
            ((CommandSource) sender).sendMessage(
                    LegacyComponentSerializer.legacySection().deserialize(legacyText));
        } catch (Throwable gone) {
            // A player who left between the command and the answer.
        }
    }

    @Override
    public boolean hasPermission(Object sender, String node) {
        if (!(sender instanceof CommandSource)) {
            return false;
        }
        try {
            return ((CommandSource) sender).hasPermission(node);
        } catch (Throwable unknown) {
            return false;
        }
    }

    @Override
    public void alertOnline(String node, String legacyText) {
        try {
            for (Player player : proxy.getAllPlayers()) {
                if (hasPermission(player, node)) {
                    send(player, legacyText);
                }
            }
        } catch (Throwable unavailable) {
            // The console line the caller already wrote is the alert that matters.
        }
    }
}
