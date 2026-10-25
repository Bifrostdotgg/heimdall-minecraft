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
     * How long after a logout ban starts waiting a Heimdall kick can still call it off. On a
     * network the backend can carry out a Heimdall kick before the proxy hears of it, so the proxy
     * sees an ordinary kick first and the kick punishment a moment later.
     */
    static final long KICK_WAIVE_MS = 10_000L;

    /**
     * Each frozen player's last kick. Bounded: entries go on read, on prune and on stop.
     */
    private final Map<UUID, KickRecord> recentKicks = new ConcurrentHashMap<UUID, KickRecord>();

    /** Logout bans waiting out {@link #LOGOUT_GRACE_MS}, by player. */
    private final Map<UUID, Held> pendingLogoutBans = new ConcurrentHashMap<UUID, Held>();

    /**
     * How long after a kick its server is pinged. Long enough for a server that is shutting down
     * to have closed its port after kicking everyone, far too short for it to be back up.
     */
    static final long EARLY_PING_MS = 3_000L;

    /** How long a ping has to answer before the server counts as not answering. */
    static final long PING_TIMEOUT_MS = 10_000L;

    /**
     * Frozen players who just left and are waiting on a lookup off the main thread before
     * anything is held. Bounded: each entry goes when the lookup settles, when they rejoin, and
     * on stop.
     */
    private final Map<UUID, Departure> departures = new ConcurrentHashMap<UUID, Departure>();

    /**
     * One departure waiting on a lookup. Its state moves once, by compare-and-set, so a Heimdall
     * kick and the lookup settling cannot both believe they went first.
     */
    static final class Departure {
        static final int OPEN = 0;
        static final int KICKED = 1;
        static final int SETTLED = 2;

        final java.util.concurrent.atomic.AtomicInteger state =
                new java.util.concurrent.atomic.AtomicInteger(OPEN);

        /** Whether a Heimdall kick for them arrived before the lookup settled. */
        boolean heimdallKicked() {
            return state.get() == KICKED;
        }
    }

    /** A waiting logout ban: the timer that fires it, and when it started waiting. */
    private static final class Held {
        final Registration timer;
        final long since;

        Held(Registration timer, long since) {
            this.timer = timer;
            this.since = since;
        }
    }

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

    /**
     * The staff notice for a held logout ban called off because the server the player was kicked
     * from no longer answers: a crash or a restart with no fallback server, not an escape.
     */
    static Component serverDownAlert(String name, boolean hidden) {
        String who = name == null || name.isEmpty() ? "A frozen player" : name;
        return Component.text("[Heimdall] " + (hidden ? "(hidden) " : ""), NamedTextColor.DARK_GRAY)
                .append(Component.text(who, NamedTextColor.WHITE))
                .append(Component.text(" was not banned for leaving while frozen: the server they"
                        + " were on is down.", NamedTextColor.GRAY));
    }

    /**
     * The staff notice for a logout ban that did not fire because LiteBans could not be asked
     * whether it already bans them: a ban from the console would override one staff just gave.
     */
    static Component liteBansUnknownAlert(String name, boolean hidden) {
        String who = name == null || name.isEmpty() ? "A frozen player" : name;
        return Component.text("[Heimdall] " + (hidden ? "(hidden) " : ""), NamedTextColor.DARK_GRAY)
                .append(Component.text(who, NamedTextColor.WHITE))
                .append(Component.text(" was not banned for leaving while frozen: LiteBans could"
                        + " not be checked. Ban them by hand if they should be.", NamedTextColor.GRAY));
    }

    /** The staff notice for a held logout ban called off because the kick was Heimdall's own. */
    static Component kickWaivedAlert(String name, boolean hidden) {
        String who = name == null || name.isEmpty() ? "A frozen player" : name;
        return Component.text("[Heimdall] " + (hidden ? "(hidden) " : ""), NamedTextColor.DARK_GRAY)
                .append(Component.text(who, NamedTextColor.WHITE))
                .append(Component.text(" was kicked by staff, so they will not be banned for"
                        + " leaving while frozen.", NamedTextColor.GRAY));
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
    void hold(UUID player, Registration timer, long now) {
        if (player == null || timer == null) return;
        Held previous = pendingLogoutBans.put(player, new Held(timer, now));
        if (previous != null && previous.timer != timer) previous.timer.close();
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

    /** One kick: when, by whom, and how to ask whether the server it came from is still up. */
    static final class KickRecord {
        static final KickRecord NONE = new KickRecord(0L, Kick.NONE, null);

        final long at;
        final Kick kind;
        /** Null for a Heimdall kick, and wherever the platform cannot ping the server. */
        final ServerProbe server;
        /** Set when the ping {@link #EARLY_PING_MS} after the kick got no answer. */
        volatile boolean serverDown;

        KickRecord(long at, Kick kind, ServerProbe server) {
            this.at = at;
            this.kind = kind;
            this.server = server;
        }
    }

    /**
     * A frozen player was kicked off the network. {@code byHeimdall} for Heimdall's own kicks;
     * {@code server} pings the server another kick came from, where the platform can.
     */
    KickRecord noteKicked(UUID player, long now, boolean byHeimdall, ServerProbe server) {
        if (player == null) return null;
        pruneKicks(now);
        KickRecord previous = recentKicks.get(player);
        // A Heimdall kick is not downgraded by the platform's own report of the same kick.
        if (!byHeimdall && previous != null && previous.kind == Kick.HEIMDALL
                && now - previous.at <= KICK_MEMORY_MS) {
            return null;
        }
        KickRecord kick = byHeimdall
                ? new KickRecord(now, Kick.HEIMDALL, null)
                : new KickRecord(now, Kick.OTHER, server);
        recentKicks.put(player, kick);
        return kick;
    }

    /** This player's last kick, if moments ago, consuming the record; never null. */
    KickRecord lastKick(UUID player, long now) {
        if (player == null) return KickRecord.NONE;
        KickRecord kick = recentKicks.remove(player);
        if (kick == null || now - kick.at > KICK_MEMORY_MS) return KickRecord.NONE;
        return kick;
    }

    private void pruneKicks(long now) {
        Iterator<Map.Entry<UUID, KickRecord>> entries = recentKicks.entrySet().iterator();
        while (entries.hasNext()) {
            if (now - entries.next().getValue().at > KICK_MEMORY_MS) {
                entries.remove();
            }
        }
    }

    /**
     * A Heimdall kick reached this server for a player who is already gone: calls off a logout
     * ban that started waiting in the last {@link #KICK_WAIVE_MS}, answering whether there was
     * one. An older one is a logout of their own that staff kicked after the fact.
     */
    Waiver waiveForHeimdallKick(UUID player, long now) {
        if (player == null) return Waiver.NONE;
        Departure departing = departures.get(player);
        if (departing != null
                && departing.state.compareAndSet(Departure.OPEN, Departure.KICKED)) {
            // Still being looked up: nothing is held yet, and nothing will be.
            return Waiver.BEFORE_HOLD;
        }
        Held held = pendingLogoutBans.get(player);
        if (held == null || now - held.since > KICK_WAIVE_MS) return Waiver.NONE;
        if (!pendingLogoutBans.remove(player, held)) return Waiver.NONE;
        held.timer.close();
        return Waiver.CALLED_OFF;
    }

    /** What a late Heimdall kick did to a logout ban. */
    enum Waiver {
        /** Nothing was waiting. */
        NONE,
        /** A held ban was called off; staff were told it was coming, so they are told it is not. */
        CALLED_OFF,
        /** It arrived before anything was held or said, so nothing ever will be. */
        BEFORE_HOLD
    }

    /** A frozen player left and a lookup has to run before anything is held. */
    Departure departing(UUID player) {
        Departure departure = new Departure();
        departures.put(player, departure);
        return departure;
    }

    /**
     * Done with this departure: true when it still stood, false when they rejoined, were
     * unfrozen or left again in the meantime, which a later lookup answers for.
     */
    boolean settle(UUID player, Departure departure) {
        return player != null && departures.remove(player, departure);
    }

    /**
     * The lookup for this departure answered: true while it still stands. Closes the door on a
     * late Heimdall kick, which from here looks for a held ban instead. The departure stays
     * listed until {@link #settle}, so a rejoin before then is still seen.
     */
    boolean claim(UUID player, Departure departure) {
        if (player == null || departures.get(player) != departure) return false;
        departure.state.compareAndSet(Departure.OPEN, Departure.SETTLED);
        return true;
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
        if (player == null) return false;
        Held held = pendingLogoutBans.get(player);
        return held != null && held.timer == timer && pendingLogoutBans.remove(player, held);
    }

    /** The player came back, or was unfrozen: nothing fires. */
    void cancel(UUID player) {
        if (player == null) return;
        departures.remove(player);
        Held pending = pendingLogoutBans.remove(player);
        if (pending != null) pending.timer.close();
    }

    /** The module is stopping: nothing that was waiting fires. */
    void clear() {
        recentKicks.clear();
        departures.clear();
        Iterator<Held> waiting = pendingLogoutBans.values().iterator();
        while (waiting.hasNext()) {
            Held pending = waiting.next();
            waiting.remove();
            pending.timer.close();
        }
    }
}
