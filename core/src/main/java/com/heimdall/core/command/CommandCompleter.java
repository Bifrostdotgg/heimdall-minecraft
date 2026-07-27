package com.heimdall.core.command;

import java.util.List;

/**
 * Suggestions for the argument the sender is part-way through typing.
 *
 * <p>Called on a server thread on every keystroke of a tab press, so it must be a read of something
 * already in memory. A completer that asked the bot for offense slugs would put a network round trip
 * on the tick loop; the offenses module keeps a refreshed cache precisely so this can be a list
 * filter.
 *
 * <p>The last element of {@code args} is the partial word. An empty list means "the sender pressed
 * tab with nothing typed", and returning everything is the right answer there.
 */
public interface CommandCompleter {

    /**
     * @return the candidates, already filtered to the partial word; {@code null} or an empty list
     *     means "no suggestions", which on Bukkit falls back to the server's own player-name
     *     completion and on Velocity offers nothing
     */
    List<String> complete(CommandSource source, List<String> args);
}
