package com.heimdall.platform.bukkit;

import com.heimdall.core.platform.PlayerHandle;
import java.util.UUID;
import java.util.concurrent.Executor;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/**
 * One online player, wrapped so platform-free code can act on them.
 *
 * <p><strong>The thread hop lives here, not at the call site.</strong> Almost nothing in the Bukkit
 * API may be touched off the main thread, and a role sync arriving over the tunnel lands on
 * {@code heimdall-io}. Putting the hop in the handle means a module that kicks a player is correct
 * by construction; putting it at the call site means it is correct until somebody adds a second
 * call site.
 *
 * <p>{@link #uuid()} and {@link #name()} are snapshotted at construction and are safe from any
 * thread without a hop — they are the two things a caller most often wants for a log line, and
 * bouncing to the main thread to read a field would be absurd.
 *
 * <p>Every action tolerates the player having disconnected since the lookup. That race is the
 * normal case, not an error: the handle is a snapshot, and a sync that fires one tick after a quit
 * must not surface as a stack trace.
 */
final class BukkitPlayerHandle implements PlayerHandle {

    private final Player player;
    private final UUID uuid;
    private final String name;
    private final Executor mainThread;
    private final BukkitMessenger messenger;

    BukkitPlayerHandle(Player player, Executor mainThread, BukkitMessenger messenger) {
        this.player = player;
        this.uuid = player.getUniqueId();
        this.name = player.getName();
        this.mainThread = mainThread;
        this.messenger = messenger;
    }

    @Override
    public UUID uuid() {
        return uuid;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public void kick(final Component reason) {
        mainThread.execute(new Runnable() {
            @Override
            public void run() {
                messenger.kick(player, reason);
            }
        });
    }

    @Override
    public void sendMessage(final Component message) {
        // Sending is one of the few things Bukkit tolerates asynchronously, but the Adventure
        // platform may reflect into the connection to do it, so this takes the same route as
        // everything else rather than relying on an undocumented tolerance.
        mainThread.execute(new Runnable() {
            @Override
            public void run() {
                if (player.isOnline()) {
                    messenger.send(player, message);
                }
            }
        });
    }

    @Override
    public boolean hasPermission(String node) {
        if (node == null || node.isEmpty()) {
            return false;
        }
        try {
            // Read-only and answered from an in-memory map by every permission plugin in use, so
            // this is the one method that does not hop — a caller asking "may they?" from an IO
            // thread and getting an answer next tick would have to restructure around a future.
            return player.hasPermission(node);
        } catch (RuntimeException gone) {
            return false;
        }
    }

    /**
     * The wrapped player, for the one caller in this package that needs more than the handle offers.
     *
     * <p>Package-private and deliberately not on {@link PlayerHandle}: the interface has no
     * {@code unwrap()} precisely so platform-free code cannot reach through it, and
     * {@link BukkitPlayerDirectory#describe} is inside the platform module where a {@code Player} is
     * an ordinary type.
     */
    Player player() {
        return player;
    }

    @Override
    public String toString() {
        return "BukkitPlayerHandle{" + name + "/" + uuid + "}";
    }
}
