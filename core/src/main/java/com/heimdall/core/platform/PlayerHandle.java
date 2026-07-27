package com.heimdall.core.platform;

import java.util.UUID;
import net.kyori.adventure.text.Component;

/**
 * One player, as platform-free code is allowed to see them.
 *
 * <p>Deliberately five methods. A handle is not a wrapper around the platform's player object —
 * there is no {@code unwrap()}, no location, no inventory — it is the set of things Heimdall
 * actually does to a player: identify them, talk to them, remove them, and ask what they may do.
 * Anything a module needs beyond that is a question about whether the behaviour belongs in a
 * platform module instead.
 *
 * <p><strong>A handle is a snapshot of a lookup, not a subscription.</strong> The player it names
 * may have disconnected between the lookup and the call, and every method here tolerates that by
 * doing nothing rather than throwing — a role sync that fires one tick after a quit must not
 * surface as an error in a server log.
 *
 * <p>Implementations are safe to call from any thread. Where the underlying platform is not — Bukkit
 * is not — the implementation hops to the right thread itself, because a caller that had to know
 * which methods needed that would get it wrong exactly once, in production.
 */
public interface PlayerHandle {

    /** The player's Minecraft UUID. */
    UUID uuid();

    /** Their username, as the platform reports it. Never normalised — see departure D8. */
    String name();

    /**
     * Removes the player from the server, showing {@code reason}.
     *
     * <p>A no-op if they have already gone.
     */
    void kick(Component reason);

    /** Sends a chat message. A no-op if they have already gone. */
    void sendMessage(Component message);

    /**
     * Whether the player holds a permission node.
     *
     * <p>This is the <em>server's</em> permission system — LuckPerms, Bukkit's own, whatever the
     * platform resolves — and it answers a question about the Minecraft server. It is not the
     * dashboard's permission model, which is about Discord.
     */
    boolean hasPermission(String node);
}
