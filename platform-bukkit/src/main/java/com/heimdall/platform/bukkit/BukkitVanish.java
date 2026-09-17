package com.heimdall.platform.bukkit;

import com.heimdall.core.log.HeimdallLogger;
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
 * <h2>An unreadable answer counts as vanished</h2>
 *
 * <p>A {@link MetadataValue} is supplied by whichever third-party plugin set it, and
 * {@link MetadataValue#asBoolean()} on a lazy implementation runs that plugin's code on this thread.
 * When it throws, this answers <strong>true</strong>.
 *
 * <p>That is the opposite of the usual "degrade to the harmless default", and it is deliberate,
 * because here the two outcomes are not symmetric. A server that declared {@code vanish@1} has told
 * the bot it can see who is hidden, so the bot reads a row without the flag as a player who may be
 * published. Guessing "not vanished" therefore leaks a hidden staff member into a Discord roster;
 * guessing "vanished" hides someone who was not, and the worst that costs is one name missing from
 * one refresh. Only the first mistake is irreversible.
 *
 * <p>The guess is cheap because it is rare: a server with no vanish plugin does not reach the catch
 * at all. {@code MetadataStoreBase.getMetadata} is synchronised and returns an empty list for a key
 * nobody has set, so "no vanish plugin" and "a vanish plugin whose value blew up" are genuinely
 * distinguishable states rather than one shrug.
 *
 * <h2>Threading, and what the guard cannot reach</h2>
 *
 * <p>Both callers read from {@code heimdall-io} or {@code heimdall-ws} rather than the main thread,
 * exactly where they already read the online list and a player's address. Reading the store itself is
 * safe from there: {@code MetadataStoreBase.getMetadata} is synchronised and hands back a copy, so a
 * plugin setting the key underneath a read cannot raise a
 * {@link java.util.ConcurrentModificationException} out of this class.
 *
 * <p>What the values <em>do</em> off the main thread is another matter, and one case is worth naming
 * because no catch here helps with it. VanishNoPacket stores a {@code LazyMetadataValue} whose
 * {@code asBoolean()} calls back into {@code VanishManager.isVanished}, which looks the player up
 * through {@code getServer().getPlayer(name)} - a main-thread read, re-entered from ours. Its own
 * {@code VanishCheck} catches {@link Exception} and answers {@code false}, so a race there does not
 * arrive as an exception this class can fail closed on: it arrives as a clean "not vanished".
 *
 * <p><strong>So the residual risk is real and is stated rather than papered over:</strong> on a
 * VanishNoPacket server, a vanished player can be reported unflagged for the one roster reply that
 * lost that race. It cannot be fixed from here, only by hopping to the main thread for every roster
 * row, which is the blocking this platform's directory exists to avoid. The next refresh corrects
 * it, and the count on the heartbeat is re-sent every tick.
 */
final class BukkitVanish {

    /** The metadata key every vanish plugin in wide use marks a hidden player with. */
    static final String METADATA_KEY = "vanished";

    private BukkitVanish() {
    }

    /**
     * Whether this player is currently hidden.
     *
     * <p>{@code false} for a null player, and <strong>true</strong> for anything that throws: see the
     * class comment for why the unreadable case is not the harmless one.
     *
     * @param logger where the guess is recorded, or {@code null} to make it silently
     */
    static boolean isVanished(HeimdallLogger logger, final Player player) {
        if (player == null) {
            return false;
        }
        try {
            List<MetadataValue> values = player.getMetadata(METADATA_KEY);
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
                    debug(logger, player, thirdParty);
                    return true;
                }
            }
        } catch (RuntimeException unreadable) {
            // The store itself, which is synchronised and cannot race: something is wrong with this
            // player rather than with the timing, and an unanswerable question about a staff member
            // is answered the safe way round.
            debug(logger, player, unreadable);
            return true;
        }
        return false;
    }

    /**
     * How many of these players are hidden.
     *
     * <p>Takes a list the caller already owns rather than the server's live view, so nothing here can
     * raise a {@link java.util.ConcurrentModificationException}: the snapshot is
     * {@link BukkitPlayerDirectory#raceTolerantCopy}'s job, and taking it once is what keeps this
     * count and the {@code onlinePlayers} beside it describing the same instant.
     */
    static int count(HeimdallLogger logger, Collection<? extends Player> online) {
        int hidden = 0;
        for (Player player : online) {
            if (isVanished(logger, player)) {
                hidden++;
            }
        }
        return hidden;
    }

    private static void debug(HeimdallLogger logger, final Player player, final RuntimeException e) {
        if (logger == null) {
            return;
        }
        try {
            // A supplier, so a server with no debug logging pays nothing for the name lookup -
            // which is itself a call into the server, and is wrapped for the same reason everything
            // else here is.
            logger.debug(() -> "vanish state for " + describe(player)
                    + " could not be read, counting them as vanished: " + e);
        } catch (RuntimeException unloggable) {
            // This is the recovery path. A caller is part way through a roster reply the bot is
            // waiting on, and an exception escaping from the diagnostics about a swallowed
            // exception would truncate that reply over a line nobody was going to read.
        }
    }

    /** The player's name, or something harmless when even that cannot be asked for. */
    private static String describe(Player player) {
        try {
            return String.valueOf(player.getName());
        } catch (RuntimeException gone) {
            return "a player on their way out";
        }
    }
}
