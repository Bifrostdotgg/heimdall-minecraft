package com.heimdall.core.command;

import java.util.UUID;
import net.kyori.adventure.text.Component;

/**
 * Whoever typed the command — a player, the console, or anything else the platform lets run one.
 *
 * <p>Five methods, chosen the same way {@code PlayerHandle}'s five were: this is what a Heimdall
 * command actually does to a sender. There is no {@code unwrap()} and no platform object behind it,
 * so a module cannot quietly start depending on being on Bukkit.
 *
 * <p>{@link #uuid()} is {@code null} for the console, and that is load-bearing rather than
 * incidental: {@code /offend} files an infraction against a UUID and records who issued it, and
 * "issued by the console" has to be expressible. A module that assumes a non-null UUID is a module
 * that throws the first time an operator runs the command from a server terminal.
 *
 * <p>Implementations are safe to call from any thread; where the platform is not, they hop.
 */
public interface CommandSource {

    /** The sender's display name — a username, or something like {@code CONSOLE}. */
    String name();

    /** The sender's Minecraft UUID, or {@code null} when they are not a player. */
    UUID uuid();

    /** Whether this source is a player rather than the console or a command block. */
    boolean isPlayer();

    /**
     * Whether the sender holds a permission node.
     *
     * <p>The <em>server's</em> permission system, not the dashboard's — see
     * {@code PlayerHandle#hasPermission}. The console answers {@code true} to everything on both
     * supported platforms.
     */
    boolean hasPermission(String node);

    /** Sends the sender a message. A no-op if they have since disconnected. */
    void sendMessage(Component message);
}
