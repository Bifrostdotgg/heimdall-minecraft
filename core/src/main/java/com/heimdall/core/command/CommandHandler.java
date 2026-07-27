package com.heimdall.core.command;

import java.util.List;

/**
 * What a command does.
 *
 * <h2>Threading</h2>
 *
 * <p>Invoked on whatever thread the platform dispatches commands on — the main server thread on
 * Bukkit, a proxy thread on Velocity. <strong>It must not block.</strong> Anything that talks to the
 * bot hands off to {@code heimdall-io} and answers the sender when the future completes; a handler
 * that waited for an HTTP round trip inline would stall the tick loop for its whole retry budget.
 *
 * <p>Permission is checked by the registrar before this runs, against
 * {@link CommandSpec#permission()}. A handler that needs a second, finer check does it itself.
 *
 * <p>A handler that throws is contained and logged, and the sender is told the command failed. It
 * never reaches the server's own error handling, which on Bukkit prints a stack trace at the player
 * and on Velocity says nothing at all.
 */
public interface CommandHandler {

    /**
     * @param args the arguments after the command name, never {@code null} and never containing
     *     the label itself
     */
    void execute(CommandSource source, List<String> args);
}
