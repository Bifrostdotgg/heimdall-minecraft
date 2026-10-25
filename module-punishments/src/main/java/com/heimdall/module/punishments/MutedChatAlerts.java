package com.heimdall.module.punishments;

import com.heimdall.core.platform.PlayerHandle;
import com.heimdall.core.punish.HiddenPunishments;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * Tells opted-in staff that a muted player tried to talk: "Steve tried to speak while muted", and
 * to holders of {@link #CONTENT_PERMISSION} a second line with what they said.
 *
 * <p>Raised only on the server that refused the message, so staff elsewhere on a network do not
 * hear about it. What was said goes from this server's memory to the staff online here and
 * nowhere else: it is never stored, and never sent to the bot or to Discord.
 *
 * <p>Throttled per muted player: the first attempt alerts at once, then at most one alert per
 * {@link #WINDOW_MS}, carrying how many attempts it folded in. A player holding Enter cannot
 * flood staff chat. An attempt inside a window is counted but not shown until the next alert,
 * so a burst that stops is reported only as its first line.
 */
final class MutedChatAlerts {

    /** Who is told at all. Opt-in: nobody holds it by default, operators included. */
    static final String PERMISSION = "heimdall.punishments.notify.muted";

    /** Who also sees what was said. Granted per staff member, separately from the alert. */
    static final String CONTENT_PERMISSION = "heimdall.punishments.notify.muted.content";

    static final long WINDOW_MS = 30_000L;

    /** Longest said text shown, in characters. A command line has no client-side cap. */
    static final int MAX_SAID_CHARS = 256;

    private static final class Window {
        final String muteId;
        final long startedAt;
        final int folded;

        Window(String muteId, long startedAt, int folded) {
            this.muteId = muteId;
            this.startedAt = startedAt;
            this.folded = folded;
        }
    }

    private final Map<UUID, Window> windows = new ConcurrentHashMap<UUID, Window>();

    /**
     * Records an attempt, answering how many earlier attempts this alert folds in, or -1 when the
     * player is inside a window and nothing is shown. One atomic step, so two chat threads cannot
     * both open a window.
     *
     * <p>A window belongs to one mute. Attempts folded under a hidden mute must never surface as
     * "(4 more since)" on the next mute's alert, which ordinary staff may see, and a count from an
     * earlier mute is wrong anyway. A mute issued here changes id once, from its local stand-in to
     * the bot's, which costs at most one extra alert.
     */
    int admit(UUID player, String muteId, long now) {
        if (player == null) return -1;
        String id = muteId == null ? "" : muteId;
        final int[] shown = {-1};
        windows.compute(player, (key, open) -> {
            if (open == null || !open.muteId.equals(id)) {
                shown[0] = 0;
                return new Window(id, now, 0);
            }
            if (now - open.startedAt >= WINDOW_MS) {
                shown[0] = open.folded;
                return new Window(id, now, 0);
            }
            return new Window(id, open.startedAt, open.folded + 1);
        });
        return shown[0];
    }

    /**
     * Tells staff online here about one refused attempt, when the throttle lets it through.
     *
     * @param online read only when an alert is actually sent, so a throttled line costs no roster
     * @param command true for a blocked command, whose text is shown as typed
     * @param hidden the mute is hidden: "Steve tried to speak while muted" tells every recipient
     *     the one fact a hidden mute keeps from them, so the audience narrows to holders of
     *     {@link HiddenPunishments#PERMISSION} as well, the same rule as the sign notice
     */
    void raise(Supplier<Collection<PlayerHandle>> online, UUID sender, String senderName,
            String muteId, boolean command, boolean hidden, String said, long now) {
        int folded = admit(sender, muteId, now);
        if (folded < 0 || online == null) return;
        Collection<PlayerHandle> players = online.get();
        if (players == null) {
            // Nobody was told, so the window is given back: the next attempt alerts at once
            // rather than 30 s of attempts going unreported.
            windows.remove(sender);
            return;
        }
        Component alert = alert(senderName, command, hidden, folded);
        Component content = content(said, command);
        for (PlayerHandle staff : players) {
            try {
                if (sender.equals(staff.uuid()) || !staff.hasPermission(PERMISSION)) continue;
                if (hidden && !staff.hasPermission(HiddenPunishments.PERMISSION)) continue;
                staff.sendMessage(alert);
                if (content != null && staff.hasPermission(CONTENT_PERMISSION)) {
                    staff.sendMessage(content);
                }
            } catch (RuntimeException gone) {
                // Somebody who left between the snapshot and the send. They are not online to be
                // told, and the rest of the staff still are.
            }
        }
    }

    /** Forgets a player's window: they left, or their mute was lifted. */
    void forget(UUID player) {
        if (player != null) windows.remove(player);
    }

    void clear() {
        windows.clear();
    }

    static Component alert(String senderName, boolean command, boolean hidden, int folded) {
        String name = senderName == null || senderName.isEmpty() ? "A player" : senderName;
        Component line = Component.text("[Heimdall] " + (hidden ? "(hidden) " : ""),
                        NamedTextColor.DARK_GRAY)
                .append(Component.text(name, NamedTextColor.WHITE))
                .append(Component.text(command
                        ? " tried to use a blocked command while muted"
                        : " tried to speak while muted", NamedTextColor.GRAY));
        if (folded > 0) {
            line = line.append(
                    Component.text(" (" + folded + " more since)", NamedTextColor.DARK_GRAY));
        }
        return line;
    }

    /**
     * What was said, as plain text: a player cannot put formatting, a click or a hover into staff
     * chat. Null when there is nothing to show.
     */
    static Component content(String said, boolean command) {
        if (said == null) return null;
        String text = said.trim();
        if (text.isEmpty()) return null;
        if (text.length() > MAX_SAID_CHARS) {
            int cut = Character.isHighSurrogate(text.charAt(MAX_SAID_CHARS - 1))
                    ? MAX_SAID_CHARS - 1 : MAX_SAID_CHARS;
            text = text.substring(0, cut) + "...";
        }
        return Component.text("  " + (command ? text : "\"" + text + "\""), NamedTextColor.GRAY)
                .decorate(TextDecoration.ITALIC);
    }
}
