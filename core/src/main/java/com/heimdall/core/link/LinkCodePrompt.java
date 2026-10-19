package com.heimdall.core.link;

import com.heimdall.core.util.Strings;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * A link code to put in front of a player, and every word they are shown with it.
 *
 * <p>The words live here, once, so the dialog on Paper and the chat lines everywhere else cannot
 * drift apart: a player who sees the dialog on one server and the chat version on another reads the
 * same instruction. The platform that draws a dialog takes its text from the accessors below rather
 * than writing its own.
 *
 * <p>The instruction names the Discord side as it actually looks (a linking panel with a "Confirm
 * Code" button), not as a slash command: the link is started from that panel, so it is where the
 * player already is.
 *
 * <p>Immutable and safe to share between threads.
 */
public final class LinkCodePrompt {

    /** The one instruction, in the dialog and in chat alike. */
    public static final String INSTRUCTION =
            "Enter this code in Discord: open the linking panel and press Confirm Code.";

    /** The dialog's title. */
    public static final String TITLE = "Link your Discord account";

    /** The dialog's button, where the platform can copy to the clipboard. */
    public static final String COPY_LABEL = "Copy code";

    /** The dialog button's tooltip. */
    public static final String COPY_TOOLTIP = "Copy the code to your clipboard";

    /** The dialog's button, where it cannot. */
    public static final String CLOSE_LABEL = "Close";

    /** The hover text on the code in chat. */
    static final String HOVER = "Click to copy the code";

    private static final long MINUTE_MS = 60_000L;

    private final String code;

    /** Epoch millis, or {@code 0} when the bot did not say or said something unreadable. */
    private final long expiresAtMs;

    private LinkCodePrompt(String code, long expiresAtMs) {
        this.code = code;
        this.expiresAtMs = expiresAtMs;
    }

    /**
     * @param code the code; trimmed, and stripped of {@code §} so it can be placed inside legacy
     *     text without restyling whatever follows it
     * @param expiresAt ISO-8601 as the bot sends it ({@code 2026-10-19T12:05:00.000Z}, or with an
     *     offset), epoch millis as digits, or {@code null}. Unreadable is treated as unknown rather
     *     than as expired: a code with no expiry line is still a code the player can use.
     * @throws IllegalArgumentException if the code is blank, which is nothing to show
     */
    public static LinkCodePrompt of(String code, String expiresAt) {
        String cleaned = code == null ? "" : code.replace("\u00A7", "").trim();
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("a code is required");
        }
        return new LinkCodePrompt(cleaned, parseExpiry(expiresAt));
    }

    /** The code itself. */
    public String code() {
        return code;
    }

    /** When it expires, epoch millis, or {@code 0} if that is not known. */
    public long expiresAtMs() {
        return expiresAtMs;
    }

    /**
     * Whether the code has already run out.
     *
     * <p>Only ever true for a known expiry. Showing a dead code would send the player to Discord to
     * be told it is wrong, which is worse than showing nothing.
     */
    public boolean isExpired(long nowMs) {
        return expiresAtMs > 0 && nowMs >= expiresAtMs;
    }

    /**
     * When it expires, as a sentence: {@code "This code expires in about 4 minutes."}.
     *
     * <p>Relative, never a clock time: the server, the bot and the player are routinely in three
     * different time zones. Rounded to the nearest minute and never below one, with its own wording
     * for the last minute. Empty when the expiry is unknown.
     */
    public String expiryText(long nowMs) {
        if (expiresAtMs <= 0) {
            return "";
        }
        long remaining = expiresAtMs - nowMs;
        if (remaining < MINUTE_MS) {
            return "This code expires in less than a minute.";
        }
        long minutes = Math.max(1L, Math.round(remaining / (double) MINUTE_MS));
        return "This code expires in about " + minutes + (minutes == 1 ? " minute." : " minutes.");
    }

    /**
     * The chat version: the code on the first line, the instruction and the expiry on the second.
     *
     * <p>The code is click-to-copy with a hover hint. Where a platform's text path cannot carry
     * click and hover events (both proxies send legacy text, departures D44 and D76) the same two
     * lines arrive as plain text, which still says everything a player needs.
     */
    public List<Component> chatLines(long nowMs) {
        Component codePart = Component.text(code, NamedTextColor.GREEN)
                .decorate(TextDecoration.BOLD)
                .clickEvent(ClickEvent.copyToClipboard(code))
                .hoverEvent(HoverEvent.showText(Component.text(HOVER, NamedTextColor.GRAY)));
        Component first = Component.text()
                .append(Component.text("Your Discord link code: ", NamedTextColor.YELLOW))
                .append(codePart)
                .build();
        String expiry = expiryText(nowMs);
        Component second = Component.text(
                expiry.isEmpty() ? INSTRUCTION : INSTRUCTION + " " + expiry, NamedTextColor.GRAY);
        List<Component> lines = new ArrayList<Component>(2);
        lines.add(first);
        lines.add(second);
        return Collections.unmodifiableList(lines);
    }

    /**
     * Reads the bot's expiry.
     *
     * <p>{@code OffsetDateTime} first because it accepts both {@code Z} and an explicit offset on
     * Java 8, where {@code Instant.parse} accepts only {@code Z}; digits as epoch millis for a bot
     * that ever sends a number.
     */
    static long parseExpiry(String raw) {
        String value = Strings.trimToNull(raw);
        if (value == null) {
            return 0L;
        }
        try {
            if (isDigits(value)) {
                return Long.parseLong(value);
            }
            return OffsetDateTime.parse(value).toInstant().toEpochMilli();
        } catch (DateTimeParseException | NumberFormatException | ArithmeticException notOffset) {
            try {
                return Instant.parse(value).toEpochMilli();
            } catch (DateTimeParseException | ArithmeticException unreadable) {
                return 0L;
            }
        }
    }

    private static boolean isDigits(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        return "LinkCodePrompt{code='" + code + "', expiresAtMs=" + expiresAtMs + "}";
    }
}
