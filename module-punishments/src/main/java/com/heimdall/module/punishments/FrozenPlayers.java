package com.heimdall.module.punishments;

import com.heimdall.core.platform.PlayerHandle;
import com.heimdall.core.punish.HiddenPunishments;
import com.heimdall.core.punish.PunishmentAnnouncement;
import com.heimdall.core.punish.PunishmentPermissions;
import com.heimdall.core.util.Registration;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * The parts of a freeze that are about people rather than rows: who hears a frozen player's chat,
 * what they are shown, and the bans waiting on a frozen player who logged out.
 *
 * <p>Kept out of {@link HeimdallPunishmentsModule} so the rules are testable without a server and
 * the module stays the wiring.
 */
final class FrozenPlayers {

    /** Freezes and unfreezes a player, hears their chat, and is told when they log out. */
    static final String PERMISSION = PunishmentPermissions.nodeFor("freeze");

    /** The reason a logout ban is filed under. Fixed text, so nothing typed reaches a command line. */
    static final String LOGOUT_BAN_REASON = "Logged out while frozen";

    /**
     * How long a frozen player who disconnected has to come back before a logout ban fires.
     *
     * <p>Two things this absorbs. A dropped connection that reconnects at once is not somebody
     * escaping a check. And a proxy that is shutting down disconnects every player before it
     * tells plugins it is stopping, so without a wait a restart would ban everyone who was frozen;
     * the module's disable cancels anything still waiting.
     */
    static final long LOGOUT_GRACE_MS = 30_000L;

    /** How often a frozen player is reminded, in milliseconds. */
    static final long REMINDER_PERIOD_MS = 15_000L;

    /** The most of a frozen player's line relayed to staff. Chat is capped well below this. */
    static final int MAX_SAID_CHARS = 256;

    /**
     * How long a kick counts for. Only kicks that end the connection are recorded, and the
     * disconnect follows at once, so this only has to outlast the hop to the quit event.
     */
    static final long KICK_MEMORY_MS = 2_000L;

    /**
     * When each frozen player was last kicked, and whether Heimdall did it. Bounded: entries go
     * on read, on prune and on stop. Negative values mark a kick Heimdall issued.
     */
    private final Map<UUID, Long> recentKicks = new ConcurrentHashMap<UUID, Long>();

    /** Logout bans waiting out {@link #LOGOUT_GRACE_MS}, by player. */
    private final Map<UUID, Registration> pendingLogoutBans =
            new ConcurrentHashMap<UUID, Registration>();

    /**
     * Whether a staff member hears this frozen player.
     *
     * <p>The freeze node, or {@code heimdall.admin}. A hidden freeze narrows that to holders of
     * {@link HiddenPunishments#PERMISSION}, the same rule as the muted-sign notice: "[Frozen]
     * Steve: ..." tells every recipient the one fact a hidden freeze keeps from them.
     */
    static boolean hears(PlayerHandle staff, boolean hidden) {
        if (hidden) return staff.hasPermission(HiddenPunishments.PERMISSION);
        return staff.hasPermission(PERMISSION)
                || staff.hasPermission(PunishmentAnnouncement.ADMIN_PERMISSION);
    }

    /**
     * A frozen player's chat line, as staff and the player themselves see it.
     *
     * <p>Plain text components throughout, so a player cannot put formatting, a click or a hover
     * into staff chat. Null when there is nothing to relay.
     */
    static Component chatLine(String senderName, String said, boolean hidden) {
        if (said == null) return null;
        String text = said.trim();
        if (text.isEmpty()) return null;
        if (text.length() > MAX_SAID_CHARS) {
            int cut = Character.isHighSurrogate(text.charAt(MAX_SAID_CHARS - 1))
                    ? MAX_SAID_CHARS - 1 : MAX_SAID_CHARS;
            text = text.substring(0, cut) + "...";
        }
        String name = senderName == null || senderName.isEmpty() ? "A player" : senderName;
        return Component.text("[Frozen] " + (hidden ? "(hidden) " : ""), NamedTextColor.AQUA)
                .append(Component.text(name, NamedTextColor.WHITE))
                .append(Component.text(": ", NamedTextColor.GRAY))
                .append(Component.text(text, NamedTextColor.WHITE));
    }

    /** The staff notice for a frozen player who disconnected, naming what happens next. */
    static Component logoutAlert(String name, boolean hidden, boolean banPending) {
        String who = name == null || name.isEmpty() ? "A frozen player" : name;
        Component line = Component.text("[Heimdall] " + (hidden ? "(hidden) " : ""),
                        NamedTextColor.DARK_GRAY)
                .append(Component.text(who, NamedTextColor.WHITE))
                .append(Component.text(" logged out while frozen.", NamedTextColor.RED));
        if (banPending) {
            line = line.append(Component.text(" They will be banned unless they are back within "
                    + (LOGOUT_GRACE_MS / 1000L) + " seconds.", NamedTextColor.GRAY));
        }
        return line;
    }

    /**
     * The staff notice for a frozen player who was kicked rather than logging out. A kick from
     * anything but Heimdall can be one the player provoked (spamming until the server kicks
     * them), so it still holds the logout ban, and the notice says how to stop it.
     */
    static Component kickedAlert(String name, boolean hidden, boolean banPending) {
        String who = name == null || name.isEmpty() ? "A frozen player" : name;
        Component line = Component.text("[Heimdall] " + (hidden ? "(hidden) " : ""),
                        NamedTextColor.DARK_GRAY)
                .append(Component.text(who, NamedTextColor.WHITE))
                .append(Component.text(" was kicked while frozen.", NamedTextColor.RED));
        if (banPending) {
            line = line.append(Component.text(" They will be banned unless they are back within "
                    + (LOGOUT_GRACE_MS / 1000L) + " seconds or are unfrozen.", NamedTextColor.GRAY));
        }
        return line;
    }

    /** What a player is told when their freeze is lifted. */
    static Component released() {
        return Component.text("You are no longer frozen.", NamedTextColor.GREEN);
    }

    /** What a frozen player is told when a command is refused. */
    static Component commandRefused() {
        return Component.text("You are frozen, so commands are blocked. "
                + "Talk to staff in chat.", NamedTextColor.RED);
    }

    /** Sends one line to every online player who hears frozen players, the sender excluded. */
    static void tellStaff(Collection<PlayerHandle> online, Component line, boolean hidden,
            UUID except) {
        if (online == null || line == null) return;
        for (PlayerHandle staff : online) {
            try {
                if (except != null && except.equals(staff.uuid())) continue;
                if (!hears(staff, hidden)) continue;
                staff.sendMessage(line);
            } catch (RuntimeException gone) {
                // Somebody who left between the snapshot and the send.
            }
        }
    }

    /**
     * A ban length in the words LiteBans' own command takes: the largest unit that divides it
     * exactly, minutes rounded up below an hour. {@code null} for permanent, which is LiteBans'
     * own default when no length is given.
     */
    static String liteBansDuration(long seconds) {
        if (seconds <= 0L) return null;
        if (seconds % 86_400L == 0L) return (seconds / 86_400L) + "d";
        if (seconds % 3_600L == 0L) return (seconds / 3_600L) + "h";
        return ((seconds + 59L) / 60L) + "m";
    }

    /**
     * Holds a logout ban until the grace period ends, replacing any already waiting for the same
     * player. The registration is what cancels it.
     */
    void hold(UUID player, Registration timer) {
        if (player == null || timer == null) return;
        Registration previous = pendingLogoutBans.put(player, timer);
        if (previous != null && previous != timer) previous.close();
    }

    /** What became of a frozen player's last kick, read once as they disconnect. */
    enum Kick {
        /** Not kicked: they left on their own. */
        NONE,
        /** Kicked by something else: another plugin, the server, a kick they provoked. */
        OTHER,
        /** Kicked by a Heimdall kick punishment, which staff issued. */
        HEIMDALL
    }

    /** A frozen player was kicked off the network. {@code byHeimdall} for Heimdall's own kicks. */
    void noteKicked(UUID player, long now, boolean byHeimdall) {
        if (player == null) return;
        pruneKicks(now);
        Long previous = recentKicks.get(player);
        // A Heimdall kick is not downgraded by the platform's own report of the same kick.
        if (!byHeimdall && previous != null && previous.longValue() < 0L
                && now + previous.longValue() <= KICK_MEMORY_MS) {
            return;
        }
        recentKicks.put(player, byHeimdall ? -now : now);
    }

    /** How this player was last kicked, if moments ago, consuming the record. */
    Kick lastKick(UUID player, long now) {
        if (player == null) return Kick.NONE;
        Long at = recentKicks.remove(player);
        if (at == null) return Kick.NONE;
        long when = Math.abs(at.longValue());
        if (now - when > KICK_MEMORY_MS) return Kick.NONE;
        return at.longValue() < 0L ? Kick.HEIMDALL : Kick.OTHER;
    }

    private void pruneKicks(long now) {
        Iterator<Map.Entry<UUID, Long>> entries = recentKicks.entrySet().iterator();
        while (entries.hasNext()) {
            if (now - Math.abs(entries.next().getValue().longValue()) > KICK_MEMORY_MS) {
                entries.remove();
            }
        }
    }

    /** Whether a logout ban is waiting for this player. */
    boolean holding(UUID player) {
        return player != null && pendingLogoutBans.containsKey(player);
    }

    /**
     * Takes the waiting ban off the books as it fires, answering whether it was still waiting:
     * false when the player came back, was unfrozen or the module stopped in the meantime.
     */
    boolean release(UUID player, Registration timer) {
        return player != null && pendingLogoutBans.remove(player, timer);
    }

    /** The player came back, or was unfrozen: nothing fires. */
    void cancel(UUID player) {
        if (player == null) return;
        Registration pending = pendingLogoutBans.remove(player);
        if (pending != null) pending.close();
    }

    /** The module is stopping: nothing that was waiting fires. */
    void clear() {
        recentKicks.clear();
        Iterator<Registration> waiting = pendingLogoutBans.values().iterator();
        while (waiting.hasNext()) {
            Registration pending = waiting.next();
            waiting.remove();
            pending.close();
        }
    }
}
