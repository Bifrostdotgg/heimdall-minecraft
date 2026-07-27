package com.heimdall.platform.bukkit;

import com.heimdall.core.BuildConstants;
import com.heimdall.core.config.ServerRole;
import com.heimdall.core.text.Msg;
import com.heimdall.core.wiring.HeimdallRuntime;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

/**
 * {@code /hd} — the command shell, with only its status line behind it for now.
 *
 * <p>Registered in phase 1c so the boot-smoke matrix can prove the command actually exists on every
 * supported server: a {@code commands:} block in {@code plugin.yml} that the entry point never
 * claims produces a command answering "Unknown command", with nothing in any log to say why, and
 * the plugin loads perfectly either way. The subcommand tree — setup, status, reload, whitelist,
 * modules — lands in phase 1e.
 *
 * <p>What it prints is deliberately the facts a support conversation starts with: which version is
 * running, what role this instance <em>resolved</em> to, and whether the tunnel is up. The resolved
 * role is the interesting one — the configured value is {@code auto} on almost every install, and
 * "why is my backend server not enforcing the whitelist?" is answered by the resolved value alone.
 */
final class HeimdallBukkitCommand implements CommandExecutor {

    private static final String PERMISSION = "heimdall.admin";

    private final HeimdallRuntime runtime;
    private final BukkitMessenger messenger;
    private final ServerRole role;

    HeimdallBukkitCommand(HeimdallRuntime runtime, BukkitMessenger messenger, ServerRole role) {
        this.runtime = runtime;
        this.messenger = messenger;
        this.role = role;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            messenger.send(sender, Msg.legacy("§cYou do not have permission to use that."));
            return true;
        }
        messenger.send(sender, Msg.legacy("§6Heimdall §fv" + BuildConstants.VERSION));
        messenger.send(sender, Msg.legacy("§7role: §f" + role.wireName()));
        messenger.send(sender, Msg.legacy("§7status: " + status()));
        if (args.length > 0) {
            messenger.send(sender, Msg.legacy(
                    "§7Subcommands arrive in the next phase; §f/" + label + " §7takes none yet."));
        }
        return true;
    }

    private String status() {
        if (!runtime.isConfigured()) {
            return "§enot set up — no bootstrap.yml yet";
        }
        return runtime.tunnel() != null && runtime.tunnel().isConnected()
                ? "§aconnected"
                : "§econfigured, not connected";
    }
}
