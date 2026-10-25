package com.heimdall.platform.velocity;

import com.heimdall.core.log.HeimdallLogger;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.proxy.Player;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Refuses a frozen player's proxy commands, {@code /server} above all, which would otherwise let
 * them walk off to another backend.
 *
 * <p><strong>Only commands the proxy itself owns.</strong> A command bound for a backend is the
 * backend's to refuse, and it does. Refusing one here is also not safe: a backend command can
 * carry a signed argument, and Velocity disconnects a player whose signed command a plugin denies,
 * which for a frozen player would start the logout ban. A proxy command is never forwarded, so
 * denying one is the same as the proxy not running it.
 */
final class VelocityFrozenCommandListener {

    private final HeimdallLogger logger;
    private final CommandManager commands;
    private final Predicate<UUID> refuse;

    VelocityFrozenCommandListener(
            HeimdallLogger logger, CommandManager commands, Predicate<UUID> refuse) {
        this.logger = logger;
        this.commands = commands;
        this.refuse = refuse;
    }

    void onCommand(CommandExecuteEvent event) {
        try {
            if (!(event.getCommandSource() instanceof Player)) return;
            CommandExecuteEvent.CommandResult result = event.getResult();
            if (!result.isAllowed()) return;
            // The command the proxy will actually run: another listener may have rewritten it,
            // and checking the typed one would let a rewrite to /server through.
            String alias = alias(result.getCommand().orElse(event.getCommand()));
            if (alias.isEmpty() || !commands.hasCommand(alias)) return;
            if (refuse.test(((Player) event.getCommandSource()).getUniqueId())) {
                event.setResult(CommandExecuteEvent.CommandResult.denied());
            }
        } catch (Throwable broken) {
            // Fail open: a command that should have been refused is less harm than a proxy whose
            // command handling throws for everyone.
            logger.error("the frozen-player command check threw", broken);
        }
    }

    /** The command's first word, without a leading slash. */
    static String alias(String command) {
        if (command == null) return "";
        String trimmed = command.trim();
        if (trimmed.startsWith("/")) trimmed = trimmed.substring(1);
        int space = trimmed.indexOf(' ');
        return space < 0 ? trimmed : trimmed.substring(0, space);
    }
}
