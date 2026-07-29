package com.heimdall.platform.bukkit;

import com.heimdall.core.json.Payload;
import com.heimdall.core.platform.PlayerDirectory;
import com.heimdall.core.platform.PlayerHandle;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Bukkit's online-player list, as a {@link PlayerDirectory}.
 *
 * <h2>The real safety property, because the obvious one is false</h2>
 *
 * <p>This class used to claim that "every lookup is a read of a concurrent map inside CraftBukkit and
 * is safe from any thread". <strong>It is not.</strong> {@code Bukkit.getOnlinePlayers()} returns a
 * live transforming <em>view</em> over {@code PlayerList}'s plain {@code ArrayList}, mutated on the
 * main thread whenever anybody joins or quits, and {@code getPlayer(UUID)} walks the same list on
 * several server generations. Reading any of it from {@code heimdall-io} — which is where every
 * caller here is, since these are tunnel handlers rather than tick-loop code — can throw
 * {@link ConcurrentModificationException}, or {@link IndexOutOfBoundsException} when the view's
 * {@code size()} and {@code get()} disagree for an instant.
 *
 * <p>That is not theoretical and it is not harmless. An escaping CME reaches
 * {@code RemoteRequestWiring}, which turns it into {@code {players: [], error: …}} — and an operator
 * looking at the dashboard sees an empty Online Players panel on a server with forty people on it.
 * The symptom is indistinguishable from the missing-handler bug that panel already suffered once.
 *
 * <p>So every read is <strong>retried</strong> rather than merely copied. A race is by definition
 * momentary: it needs a join or a quit to land inside the microseconds a snapshot takes, so a second
 * attempt essentially always succeeds. {@value #SNAPSHOT_ATTEMPTS} consecutive failures is not a race,
 * and that case throws rather than answering "nobody is online" — a wrong roster reported as a
 * success is worse than a request that visibly failed.
 *
 * <p>Hopping to the main thread and snapshotting there would also be correct, and was rejected:
 * {@link PlayerDirectory} promises implementations "must not block", every caller would then wait a
 * tick behind whatever the server is doing, and a stalled server would turn a roster request into a
 * hung tunnel handler. Retrying costs nothing on the overwhelmingly common path.
 *
 * <p>What is still <em>not</em> safe off the main thread is acting on the result, and that hop lives
 * inside {@link BukkitPlayerHandle}. v2 had this same race and no retry; parity is not the bar for a
 * comment that asserts something false.
 */
final class BukkitPlayerDirectory implements PlayerDirectory {

    /**
     * How many times a read is re-attempted before the race is treated as something else.
     *
     * <p>Five rather than two because the cost of an extra attempt is a few microseconds and the cost
     * of giving up is a wrong answer on somebody's dashboard. It is a ceiling, not a budget: on a
     * server nobody is joining, the first attempt always wins.
     */
    private static final int SNAPSHOT_ATTEMPTS = 5;

    /**
     * How the online list is actually read.
     *
     * <p>A seam of exactly one method, and it exists for the same reason
     * {@link BukkitConsoleBridge.CommandSink} does: {@code Bukkit.getOnlinePlayers()} is static, so
     * the retry above — the difference between a correct roster and an empty panel — is otherwise
     * reachable only through a running server or a static-mocking library.
     */
    interface RosterSource {

        /** The server's live online-player view, whatever it currently is. */
        Collection<? extends Player> onlinePlayers();
    }

    private final Executor mainThread;
    private final BukkitMessenger messenger;
    private final RosterSource roster;

    BukkitPlayerDirectory(Executor mainThread, BukkitMessenger messenger) {
        this(mainThread, messenger, new RosterSource() {
            @Override
            public Collection<? extends Player> onlinePlayers() {
                return Bukkit.getOnlinePlayers();
            }
        });
    }

    /** For the tests that need to drive a roster that mutates underneath a reader. */
    BukkitPlayerDirectory(Executor mainThread, BukkitMessenger messenger, RosterSource roster) {
        this.mainThread = mainThread;
        this.messenger = messenger;
        this.roster = roster;
    }

    @Override
    public Optional<PlayerHandle> byUuid(final UUID uuid) {
        if (uuid == null) {
            return Optional.empty();
        }
        // Retried for the same reason the roster is: CraftServer.getPlayer(UUID) walks PlayerList's
        // own ArrayList on several server generations rather than reading a map.
        return raceTolerant(new Read<Optional<PlayerHandle>>() {
            @Override
            public Optional<PlayerHandle> read() {
                return lookedUp(Bukkit.getPlayer(uuid));
            }
        }, Optional.<PlayerHandle>empty());
    }

    @Override
    public Optional<PlayerHandle> byName(final String username) {
        if (username == null || username.isEmpty()) {
            return Optional.empty();
        }
        return raceTolerant(new Read<Optional<PlayerHandle>>() {
            @Override
            public Optional<PlayerHandle> read() {
                // getPlayerExact is case-insensitive but does not do Bukkit's prefix matching, which
                // would resolve "ste" to Steve — a command that kicked the wrong player because two
                // usernames share a prefix is not a bug anybody wants to explain.
                return lookedUp(Bukkit.getPlayerExact(username));
            }
        }, Optional.<PlayerHandle>empty());
    }

    @Override
    public Collection<PlayerHandle> onlinePlayers() {
        return raceTolerant(new Read<Collection<PlayerHandle>>() {
            @Override
            public Collection<PlayerHandle> read() {
                return snapshot();
            }
        }, Collections.<PlayerHandle>emptyList());
    }

    /** One read of the server's player list, which may race and is expected to be retried. */
    private interface Read<T> {
        T read();
    }

    /**
     * Runs a read, retrying the momentary races and distinguishing them from a server that is gone.
     *
     * @param whenUnavailable what to answer when the server is not in a state to be asked at all —
     *     before it has finished starting, or after it has begun stopping. That is a real state with
     *     a real answer ("nobody"), unlike a race, which has no answer yet.
     */
    private <T> T raceTolerant(Read<T> read, T whenUnavailable) {
        RuntimeException lastRace = null;
        for (int attempt = 0; attempt < SNAPSHOT_ATTEMPTS; attempt++) {
            try {
                return read.read();
            } catch (ConcurrentModificationException raced) {
                lastRace = raced;
            } catch (IndexOutOfBoundsException raced) {
                // The transforming view's size() and get() disagreeing for an instant. Same race,
                // different symptom depending on the server generation.
                lastRace = raced;
            } catch (RuntimeException notReady) {
                return whenUnavailable;
            }
        }
        // Not a race any more. Reporting it beats answering "nobody is online" to a dashboard panel
        // that would render that as a quiet server.
        throw new IllegalStateException(
                "the online player list kept changing under a read after " + SNAPSHOT_ATTEMPTS
                        + " attempts", lastRace);
    }

    /** One pass over the live view. Throws on a race; {@link #raceTolerant} is what tolerates it. */
    private Collection<PlayerHandle> snapshot() {
        Collection<? extends Player> online = roster.onlinePlayers();
        if (online == null) {
            return Collections.emptyList();
        }
        List<PlayerHandle> handles = new ArrayList<PlayerHandle>(online.size());
        for (Player player : online) {
            if (player != null) {
                handles.add(new BukkitPlayerHandle(player, mainThread, messenger));
            }
        }
        return Collections.unmodifiableList(handles);
    }

    /**
     * The address this player connected from, under {@code ip} — v2's Bukkit roster field, verbatim.
     *
     * <p>{@code "unknown"} rather than an omitted key when the address cannot be read, which is
     * v2's literal string and is what the dashboard's panel was written against. It happens: the
     * socket is closed between the roster snapshot and this read, and {@code getAddress()} answers
     * {@code null} for a player who is on their way out.
     *
     * <p>No {@code server} key, because a Bukkit server is not in front of anything — there is no
     * backend for a player to be "on". The proxy's directory answers that question instead, and
     * neither platform has to know the other exists.
     */
    @Override
    public Payload describe(PlayerHandle player) {
        if (!(player instanceof BukkitPlayerHandle)) {
            return Payload.empty();
        }
        String address = "unknown";
        try {
            InetSocketAddress socket = ((BukkitPlayerHandle) player).player().getAddress();
            if (socket != null && socket.getAddress() != null) {
                address = socket.getAddress().getHostAddress();
            }
        } catch (RuntimeException gone) {
            // A player the server has already begun tearing down. "unknown" is the honest answer and
            // is the same one v2 gave for a null address; failing the whole roster over one row is
            // not.
            address = "unknown";
        }
        return Payload.builder().put("ip", address).build();
    }

    /**
     * Wraps a player the caller already holds, without a lookup.
     *
     * <p>For the event listeners, which are handed a {@code Player} by the server. Looking the same
     * player up again by UUID would be a second read that can legitimately answer "not online": a
     * {@code PlayerQuitEvent} fires while they are still in the online list on some versions and
     * after they have left it on others, and a quit notification that silently stopped firing on
     * one server generation is exactly the kind of difference nobody finds.
     */
    PlayerHandle wrap(Player player) {
        return new BukkitPlayerHandle(player, mainThread, messenger);
    }

    private Optional<PlayerHandle> lookedUp(Player player) {
        return player == null || !player.isOnline()
                ? Optional.<PlayerHandle>empty()
                : Optional.<PlayerHandle>of(new BukkitPlayerHandle(player, mainThread, messenger));
    }
}
