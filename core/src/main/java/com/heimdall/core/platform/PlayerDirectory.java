package com.heimdall.core.platform;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * Who is online, and how to find one of them.
 *
 * <p><strong>Online players only.</strong> There is no offline lookup here, and that is a decision
 * rather than an omission: "resolve this name to a UUID" has a different answer on every platform
 * (Bukkit has a cache of everyone who has ever joined, a Velocity proxy has nothing at all), the
 * wrong answer is silent, and the bot already knows the mapping. A feature that needs an offline
 * player is a feature that should be asking the bot.
 *
 * <p>Every lookup returns an {@link Optional} because "not online" is the ordinary case, not an
 * error — a role sync arriving for someone who logged out a second ago is the normal shape of a
 * race, not a fault to log.
 *
 * <p>Implementations are safe to call from any thread and must not block.
 */
public interface PlayerDirectory {

    /** The online player with this UUID, if any. */
    Optional<PlayerHandle> byUuid(UUID uuid);

    /**
     * The online player with this username, if any.
     *
     * <p>Matched case-insensitively: an operator typing {@code /hd whois steve} means Steve.
     */
    Optional<PlayerHandle> byName(String username);

    /**
     * Everyone currently online.
     *
     * <p>A snapshot — iterating it cannot throw {@link java.util.ConcurrentModificationException}
     * because somebody joined while a caller was halfway through it.
     */
    Collection<PlayerHandle> onlinePlayers();
}
