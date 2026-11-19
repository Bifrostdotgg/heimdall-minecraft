package com.heimdall.core.wiring;

import com.heimdall.core.json.Payload;
import com.heimdall.core.platform.Integrations;
import com.heimdall.core.platform.PlatformFacade;
import com.heimdall.core.platform.PlayerHandle;
import com.heimdall.core.platform.Vanish;
import com.heimdall.core.tunnel.HealthSnapshotSource;
import java.util.Collection;

/**
 * Tells the bot which online players are hidden by a vanish plugin. Departure D92.
 *
 * <p>Two places carry it: {@code vanished: true} on a {@code player_list} row, and
 * {@code vanishedPlayers} beside {@code onlinePlayers} on each health snapshot. The bot decides who
 * may see a hidden player ({@code minecraft.server.view_vanished}); this only reports, and never
 * drops a row, because the roster has no idea who is about to read it.
 *
 * <p>Every answer comes from {@link Integrations#vanish()}, the same source the chat bridge asks
 * before it relays a join (D88): PremiumVanish or SuperVanish on all three platforms, plus the
 * {@code vanished} metadata convention on the Bukkit family. One source, so the roster and the
 * bridge cannot disagree about who is hidden.
 *
 * <h2>Threading</h2>
 *
 * <p>{@link Vanish#isVanished} must not run on a server's main thread. Both callers here run off it:
 * the roster handler on {@code heimdall-io} (where {@code RemoteRequestWiring} subscribes it), the
 * snapshot on the heartbeat ({@code heimdall-ws}).
 *
 * <h2>What it cannot see</h2>
 *
 * <p>Whatever {@link Vanish} cannot see. Two cases answer "visible" for a vanished player: a
 * PremiumVanish whose API moved in an update (D88 then treats it as absent, logged once as an
 * error), and a lookup that could not take the resolve lock in time. A proxy without PremiumVanish's
 * proxy half sees nobody as hidden.
 */
final class VanishReporting {

    /** The row key, written only when true so older bots read a row exactly as before. */
    static final String ROW_KEY = "vanished";

    static final String ONLINE_KEY = "onlinePlayers";

    static final String VANISHED_KEY = "vanishedPlayers";

    private VanishReporting() {
    }

    /**
     * Whether {@code player} is hidden, or not yet known not to be.
     *
     * <p>A join the vanish integration has not decided yet counts as hidden. On a proxy, PremiumVanish
     * learns a player's state only once they reach a backend, so a vanished player sitting in an
     * auth limbo, or in the first moments after connecting, answers "not invisible". The chat bridge
     * waits that out before announcing a join (D88); a roster read in the same window would publish
     * them. {@link Vanish#joinWaitMillis} with an unbounded elapsed time is positive exactly while
     * that is still open on a proxy, and zero on a backend, whose settle window counts from a join
     * that by then is long past.
     *
     * <p>{@code true} when the question throws. {@link Vanish} promises not to, and already answers
     * {@code true} when the vanish plugin itself throws; this guards the integration lookup too.
     * Guessing "hidden" costs one name missing from one refresh, guessing "visible" publishes a
     * hidden staff member, and only the second cannot be taken back.
     */
    static boolean isVanished(PlatformFacade platform, PlayerHandle player) {
        try {
            Integrations integrations = platform.integrations();
            Vanish vanish = integrations == null ? null : integrations.vanish();
            if (vanish == null) {
                return false;
            }
            return vanish.isVanished(player) || vanish.joinWaitMillis(player, Long.MAX_VALUE) > 0L;
        } catch (Throwable unreadable) {
            return true;
        }
    }

    /**
     * Wraps a platform's health source so each snapshot carries {@code vanishedPlayers}.
     *
     * <p>{@code null} stays {@code null}: a platform with no source sends no health at all.
     */
    static HealthSnapshotSource counting(final HealthSnapshotSource source, final PlatformFacade platform) {
        if (source == null) {
            return null;
        }
        return new HealthSnapshotSource() {
            @Override
            public Payload snapshot() {
                return withVanishCount(source.snapshot(), platform);
            }
        };
    }

    /**
     * {@code snapshot} with both player counts taken from one read of the roster.
     *
     * <p><strong>The two counts travel together or not at all.</strong> The bot shows
     * {@code onlinePlayers - vanishedPlayers} to anyone who may not see hidden staff, so the two
     * have to describe the same instant, which is why {@code onlinePlayers} is overwritten from the
     * same list rather than kept from the platform's own read. And when the roster cannot be read,
     * {@code onlinePlayers} is removed rather than sent alone: a bot given only the total would
     * publish a number that still counts hidden staff. A one-tick gap in a chart is the cheaper
     * mistake, and the next heartbeat is seconds away.
     *
     * <p>A snapshot without {@code onlinePlayers} (a platform asked before it finished starting)
     * stays without both: there was no count to correct.
     */
    static Payload withVanishCount(Payload snapshot, PlatformFacade platform) {
        if (snapshot == null || !snapshot.has(ONLINE_KEY)) {
            return snapshot;
        }
        int online;
        int hidden = 0;
        try {
            Collection<PlayerHandle> players = platform.players().onlinePlayers();
            if (players == null) {
                return snapshot.without(ONLINE_KEY, VANISHED_KEY);
            }
            online = players.size();
            for (PlayerHandle player : players) {
                if (player != null && isVanished(platform, player)) {
                    hidden++;
                }
            }
        } catch (Throwable unavailable) {
            // Throwable, not just RuntimeException: an Error escaping here would escape the
            // heartbeat task too, and a periodic task that throws is never run again.
            return snapshot.without(ONLINE_KEY, VANISHED_KEY);
        }
        return snapshot.toBuilder()
                .put(ONLINE_KEY, online)
                .put(VANISHED_KEY, hidden)
                .build();
    }
}
