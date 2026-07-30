package com.heimdall.platform.bungee;

import com.heimdall.core.json.Payload;
import com.heimdall.core.platform.PlayerDirectory;
import com.heimdall.core.platform.PlayerHandle;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.connection.Server;

/**
 * The proxy's connected-player list, as a {@link PlayerDirectory}.
 *
 * <p>"Online" on a proxy means connected to the <em>network</em>, not to any particular backend
 * server, which is the right scope for everything Heimdall does with it: a whitelist decision and a
 * role sync are about the person, and the proxy is the only place that sees all of them.
 *
 * <h2>What BungeeCord's own roster actually guarantees</h2>
 *
 * <p>Stated with its evidence rather than assumed, because the equivalent claim on the Bukkit side
 * was written down for a year and was false. {@code BungeeCord.getPlayers()} is
 *
 * <pre>{@code
 * @Locked.Read("connectionLock")
 * public Collection<ProxiedPlayer> getPlayers() {
 *     return Collections.unmodifiableCollection( new HashSet( connections.values() ) );
 * }
 * }</pre>
 *
 * <p>and {@code addConnection}/{@code removeConnection} take {@code connectionLock}'s write lock.
 * So the returned collection is a <strong>copy taken under a read lock</strong>: not a live view, and
 * not built while another thread is mutating the map. Iterating it cannot throw
 * {@link ConcurrentModificationException}, and neither can building it.
 *
 * <p>That has held across the range this plugin supports — current BungeeCord expresses the lock as
 * a Lombok {@code @Locked.Read} annotation, and the 2020-era source it replaced spells out
 * {@code connectionLock.readLock().lock()} around the identical body.
 *
 * <p>The reads are nevertheless routed through {@link #raceTolerant}, and that is not belt-and-braces
 * for a property just demonstrated: the floor here is an API version, not a proxy build, and this
 * plugin will run on BungeeCord forks and on builds older than any source that was read. A retry
 * costs a few microseconds on a path that essentially never takes it, and the alternative failure is
 * a dashboard panel showing an empty server. Where this genuinely differs from
 * {@code BukkitPlayerDirectory} is that there the retry is load-bearing on every server generation;
 * here it is insurance.
 */
final class BungeePlayerDirectory implements PlayerDirectory {

    /**
     * How many times a read is re-attempted before the race is treated as something else.
     *
     * <p>The same five as the Bukkit side, for the same reason: the cost of an extra attempt is
     * microseconds and the cost of giving up is a wrong answer on somebody's dashboard.
     */
    private static final int SNAPSHOT_ATTEMPTS = 5;

    /** What {@link #describe} reports for a player who is on no backend. v2's literal string. */
    private static final String UNKNOWN_SERVER = "unknown";

    private final ProxyServer proxy;
    private final BungeeText text;

    BungeePlayerDirectory(ProxyServer proxy, BungeeText text) {
        this.proxy = proxy;
        this.text = text;
    }

    @Override
    public Optional<PlayerHandle> byUuid(final UUID uuid) {
        if (uuid == null) {
            return Optional.empty();
        }
        return raceTolerant(new Read<Optional<PlayerHandle>>() {
            @Override
            public Optional<PlayerHandle> read() {
                return wrap(proxy.getPlayer(uuid));
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
                // getPlayer(String), not matchPlayer(String). The latter does prefix matching and
                // would resolve "ste" to Steve — a command that kicked the wrong player because two
                // usernames share a prefix is not a bug anybody wants to explain. getPlayer reads a
                // CaseInsensitiveMap, so it is exact and case-insensitive, which is what
                // PlayerDirectory promises.
                return wrap(proxy.getPlayer(username));
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

    /**
     * Which backend this player is on, under {@code server} — v2's proxy roster field, verbatim, and
     * the same key the Velocity binding answers with so the dashboard's third column does not depend
     * on which proxy it is looking at.
     *
     * <p>{@code "unknown"} rather than an omitted key for a player who is connected to the proxy but
     * not (yet) to any backend, which is v2's literal string and a genuinely common state: it is
     * every player between the login handshake and their first server connect, and everyone in a
     * queue plugin's holding pattern. On BungeeCord that state is {@code getServer() == null}
     * outright, which makes it the ordinary path rather than an error one.
     *
     * <p>No {@code ip} key, even though the proxy knows the address perfectly well. That is v2's
     * roster shape kept deliberately rather than by omission — the dashboard's proxy panel has never
     * shown an address column, and quietly starting to publish every proxied player's IP to it is not
     * a change this handler gets to make on the way past.
     */
    @Override
    public Payload describe(PlayerHandle player) {
        if (!(player instanceof BungeePlayerHandle)) {
            return Payload.empty();
        }
        String server = UNKNOWN_SERVER;
        try {
            Server connected = ((BungeePlayerHandle) player).player().getServer();
            if (connected != null && connected.getInfo() != null
                    && connected.getInfo().getName() != null) {
                server = connected.getInfo().getName();
            }
        } catch (RuntimeException gone) {
            // A connection torn down between the roster snapshot and this read. One row's worth of
            // "unknown" beats failing the whole reply.
            server = UNKNOWN_SERVER;
        }
        return Payload.builder().put("server", server).build();
    }

    /** One read of the proxy's player list, which may race and is expected to be retried. */
    private interface Read<T> {
        T read();
    }

    /**
     * Runs a read, retrying the momentary races and distinguishing them from a proxy that is gone.
     *
     * @param whenUnavailable what to answer when the proxy is not in a state to be asked at all —
     *     mid-shutdown, most plausibly. That is a real state with a real answer ("nobody"), unlike a
     *     race, which has no answer yet.
     */
    private <T> T raceTolerant(Read<T> read, T whenUnavailable) {
        RuntimeException lastRace = null;
        for (int attempt = 0; attempt < SNAPSHOT_ATTEMPTS; attempt++) {
            try {
                return read.read();
            } catch (ConcurrentModificationException raced) {
                lastRace = raced;
            } catch (IndexOutOfBoundsException raced) {
                lastRace = raced;
            } catch (RuntimeException notReady) {
                return whenUnavailable;
            }
        }
        // Not a race any more. Reporting it beats answering "nobody is online" to a dashboard panel
        // that would render that as a quiet network.
        throw new IllegalStateException(
                "the proxy's player list kept changing under a read after " + SNAPSHOT_ATTEMPTS
                        + " attempts", lastRace);
    }

    /** One pass over the proxy's roster. */
    private Collection<PlayerHandle> snapshot() {
        Collection<ProxiedPlayer> connected = proxy.getPlayers();
        if (connected == null) {
            return Collections.emptyList();
        }
        List<PlayerHandle> handles = new ArrayList<PlayerHandle>(connected.size());
        for (ProxiedPlayer player : connected) {
            if (player != null) {
                handles.add(new BungeePlayerHandle(player, text));
            }
        }
        return Collections.unmodifiableList(handles);
    }

    private Optional<PlayerHandle> wrap(ProxiedPlayer player) {
        return player == null
                ? Optional.<PlayerHandle>empty()
                : Optional.<PlayerHandle>of(new BungeePlayerHandle(player, text));
    }
}
