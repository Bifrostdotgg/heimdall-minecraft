package com.heimdall.platform.bukkit;

import com.heimdall.core.json.Payload;
import com.heimdall.core.platform.PlayerDirectory;
import com.heimdall.core.platform.PlayerHandle;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Bukkit's online-player list, as a {@link PlayerDirectory}.
 *
 * <p>Every lookup is a read of a concurrent map inside CraftBukkit and is safe from any thread —
 * which matters, because the callers are tunnel handlers on {@code heimdall-io}, not tick-loop code.
 * What is <em>not</em> safe off the main thread is acting on the result, and that hop lives inside
 * {@link BukkitPlayerHandle}.
 *
 * <p>{@link #onlinePlayers()} copies. Bukkit's own collection is a live view, and a caller iterating
 * it while somebody joins gets a {@link java.util.ConcurrentModificationException} on some versions
 * and a silently short list on others.
 */
final class BukkitPlayerDirectory implements PlayerDirectory {

    private final Executor mainThread;
    private final BukkitMessenger messenger;

    BukkitPlayerDirectory(Executor mainThread, BukkitMessenger messenger) {
        this.mainThread = mainThread;
        this.messenger = messenger;
    }

    @Override
    public Optional<PlayerHandle> byUuid(UUID uuid) {
        if (uuid == null) {
            return Optional.empty();
        }
        return lookedUp(Bukkit.getPlayer(uuid));
    }

    @Override
    public Optional<PlayerHandle> byName(String username) {
        if (username == null || username.isEmpty()) {
            return Optional.empty();
        }
        // getPlayerExact is case-insensitive but does not do Bukkit's prefix matching, which would
        // resolve "ste" to Steve — a command that kicked the wrong player because two usernames
        // share a prefix is not a bug anybody wants to explain.
        return lookedUp(Bukkit.getPlayerExact(username));
    }

    @Override
    public Collection<PlayerHandle> onlinePlayers() {
        Collection<? extends Player> online;
        try {
            online = Bukkit.getOnlinePlayers();
        } catch (RuntimeException notReady) {
            // Asked before the server has finished starting, or after it has begun stopping.
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
