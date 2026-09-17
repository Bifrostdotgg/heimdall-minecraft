package com.heimdall.platform.bukkit;

import java.util.Collection;
import java.util.List;
import org.bukkit.entity.Player;
import org.bukkit.metadata.MetadataValue;

/**
 * Whether a player is hidden by a vanish plugin.
 *
 * <h2>One convention, no dependency</h2>
 *
 * <p>There is no vanish API in Bukkit, but there is a convention, and it is close to universal:
 * EssentialsX, SuperVanish, PremiumVanish, CMI and VanishNoPacket all set a
 * {@value #METADATA_KEY} metadata value on a hidden player, and plugins that want to respect vanish
 * all read exactly that. Reading the key is therefore the way to support five vanish plugins at once
 * without compiling against any of them, and without a soft-depend that decides at load time which
 * one an operator is allowed to use. A server running none of them simply never has the key.
 *
 * <p><strong>This reports; it never filters.</strong> Whether a hidden player is shown in Discord is
 * a permission the bot holds, and the plugin has no idea who is looking at the answer it produces.
 * See {@code Capabilities.VANISH}.
 *
 * <h2>Why every read is wrapped</h2>
 *
 * <p>A {@link MetadataValue} is supplied by whichever third-party plugin set it, and
 * {@link MetadataValue#asBoolean()} on a lazy implementation runs that plugin's code on this thread.
 * It can throw, and the two callers cannot afford one: {@code describe} is building a row in a reply
 * the bot is waiting on, and the health count rides the heartbeat that doubles as this server's
 * liveness signal. A value that throws counts as "not vanished" - the same answer as a server with no
 * vanish plugin at all, which is the one failure mode nobody has to be told about.
 *
 * <p>Both callers read from {@code heimdall-io} or {@code heimdall-ws} rather than the main thread,
 * exactly where they already read the online list and a player's address. Metadata is a per-player
 * map inside the server, so this adds no new threading assumption; a store mutated underneath a read
 * throws, and a throw here is already "not vanished".
 */
final class BukkitVanish {

    /** The metadata key every vanish plugin in wide use marks a hidden player with. */
    static final String METADATA_KEY = "vanished";

    private BukkitVanish() {
    }

    /** Whether this player is currently hidden. {@code false} for null, and for anything unreadable. */
    static boolean isVanished(Player player) {
        if (player == null) {
            return false;
        }
        try {
            List<MetadataValue> values = player.getMetadata(METADATA_KEY);
            if (values == null) {
                return false;
            }
            for (MetadataValue value : values) {
                if (value == null) {
                    continue;
                }
                try {
                    if (value.asBoolean()) {
                        // Any plugin saying yes is a yes. A server running two vanish plugins has
                        // one of them still holding a stale false, and "hidden by something" is the
                        // answer that keeps a hidden staff member hidden.
                        return true;
                    }
                } catch (RuntimeException thirdParty) {
                    // One plugin's value cannot decide the answer for the rest, and it certainly
                    // cannot cost the caller its reply.
                }
            }
        } catch (RuntimeException unreadable) {
            // A player the server has already begun tearing down, or a metadata store being mutated
            // under the read. Neither is evidence that anybody is hidden.
        }
        return false;
    }

    /**
     * How many of these players are hidden.
     *
     * <p>May throw whatever iterating {@code online} throws: on the Bukkit family that collection is
     * the server's own live view, so a join or a quit landing mid-count raises
     * {@link java.util.ConcurrentModificationException}. The caller decides what to do about it, and
     * the health source simply omits the field for that tick - the next heartbeat is seconds away,
     * which is why this does not retry the way a one-shot roster request has to.
     */
    static int count(Collection<? extends Player> online) {
        if (online == null) {
            return 0;
        }
        int hidden = 0;
        for (Player player : online) {
            if (isVanished(player)) {
                hidden++;
            }
        }
        return hidden;
    }
}
