package com.heimdall.shell.contract;

import java.util.List;

/**
 * What a core supplies for one command: the code behind a shell-owned relay.
 *
 * <p>The sender is the platform's own object ({@code CommandSender} on Bukkit and BungeeCord,
 * {@code CommandSource} on Velocity), passed through untouched. Both methods may be called on any
 * thread the platform dispatches commands on, and both must contain their own failures: the relay
 * catches a {@code Throwable} too, but only to say "that command failed".
 */
public interface CommandTarget {

    /** Runs the command. */
    void execute(Object sender, String label, String[] args);

    /**
     * Tab completions for the arguments typed so far.
     *
     * @return the suggestions; {@code null} lets the platform fall back to its default (online
     *     player names on Bukkit), an empty list suggests nothing
     */
    List<String> complete(Object sender, String alias, String[] args);
}
