package com.heimdall.core.platform;

import com.heimdall.core.json.Payload;
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

    /**
     * What <em>this</em> platform can say about an online player, beyond their uuid and name.
     *
     * <p>The dashboard's Online Players panel shows a different third column depending on what it is
     * looking at: a backend server reports the address a player connected from, a proxy reports which
     * backend they are currently on. Those are not two renderings of one fact — a Bukkit server has no
     * concept of "which backend", and asking a proxy for a player's address answers a question the v2
     * panel never asked — so neither belongs in a single flattened shape that both platforms have to
     * pretend to fill in.
     *
     * <p>So the split is: <strong>core owns the keys every platform has</strong> ({@code uuid},
     * {@code username}, written once in {@code RemoteRequestWiring}), and a platform owns only its own
     * extra. Two platforms therefore cannot disagree about the shared half — which is exactly how v2's
     * two entry points drifted apart — and adding a platform means answering one question rather than
     * transcribing a wire format for the third time.
     *
     * <p>{@link Payload#empty()} is a perfectly good answer, and means "this platform has nothing to
     * add". Returning {@code null} is not: the caller merges this into a frame the bot is waiting on.
     *
     * <p>Called for every player in a roster reply, so it must be a field read rather than a lookup,
     * and it must not block. The handle is one this directory produced; a foreign implementation gets
     * {@link Payload#empty()} rather than an exception, because a reply the dashboard can render beats
     * a request that dies over a nicety.
     */
    Payload describe(PlayerHandle player);
}
