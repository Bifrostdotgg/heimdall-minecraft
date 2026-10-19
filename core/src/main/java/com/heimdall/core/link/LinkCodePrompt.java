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

    /**
     * Always shown, in the dialog and in chat.
     *
     * <p>The attack it answers: somebody starts a link in Discord naming a player who is online,
     * then asks that player to "read me the code", which links the victim's Minecraft account to
     * the attacker's Discord. The code is only ever for the player to type themselves.
     */
    public static final String WARNING =
            "Only enter this code yourself, in Discord. Never give it to anyone. "
                    + "If you didn't ask for it, ignore it.";

    /** The longest requester text shown; a Discord display name and username fit well inside. */
    static final int MAX_REQUESTED_BY = 100;

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

    /** Who started the link in Discord, cleaned; empty when the bot did not say. */
    private final String requestedBy;

    private LinkCodePrompt(String code, long expiresAtMs, String requestedBy) {
        this.code = code;
        this.expiresAtMs = expiresAtMs;
        this.requestedBy = requestedBy;
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
        return of(code, expiresAt, null);
    }

    /**
     * The same, with who started the link in Discord.
     *
     * @param requestedBy the bot's {@code requestedBy}, e.g. {@code "@lerndmina (Wild)"}, or
     *     {@code null}. A Discord display name is anybody's to choose, so it is cleaned: section
     *     signs, control characters, format characters (bidi overrides, zero-width, tags) and
     *     unpaired surrogates go (it is rendered through legacy text on some paths, where a
     *     {@code §} would restyle what follows), whitespace runs collapse, and it is cut to
     *     {@value #MAX_REQUESTED_BY} code points.
     */
    public static LinkCodePrompt of(String code, String expiresAt, String requestedBy) {
        String cleaned = code == null ? "" : code.replace("\u00A7", "").trim();
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("a code is required");
        }
        return new LinkCodePrompt(cleaned, parseExpiry(expiresAt), cleanRequester(requestedBy));
    }

    /** The code itself. */
    public String code() {
        return code;
    }

    /** Who started the link in Discord, cleaned, or empty when the bot did not say. */
    public String requestedBy() {
        return requestedBy;
    }

    /** {@code "Requested by @lerndmina (Wild) in Discord."}, or empty when nobody is named. */
    public String requestedByText() {
        return requestedBy.isEmpty() ? "" : "Requested by " + requestedBy + " in Discord.";
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
     * The chat version: the code; the instruction and the expiry; who asked, when the bot said; and
     * the warning, always.
     *
     * <p>The code is click-to-copy with a hover hint. Where a platform's text path cannot carry
     * click and hover events (both proxies send legacy text, departures D44 and D76) the same lines
     * arrive as plain text, which still says everything a player needs.
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
        List<Component> lines = new ArrayList<Component>(4);
        lines.add(first);
        lines.add(Component.text(
                expiry.isEmpty() ? INSTRUCTION : INSTRUCTION + " " + expiry, NamedTextColor.GRAY));
        if (!requestedBy.isEmpty()) {
            lines.add(Component.text(
                    "Requested by " + unlinkable(requestedBy) + " in Discord.", NamedTextColor.WHITE));
        }
        lines.add(Component.text(WARNING, NamedTextColor.RED));
        return Collections.unmodifiableList(lines);
    }

    /** Strips what a display name could use to restyle or break the line, and bounds it. */
    static String cleanRequester(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(Math.min(raw.length(), MAX_REQUESTED_BY));
        boolean space = false;
        int kept = 0;
        // By code point, not by char: the tag characters (U+E0000 to U+E007F) are format
        // characters too, and as surrogate pairs each half reads as a plain SURROGATE.
        for (int i = 0; i < raw.length() && kept < MAX_REQUESTED_BY; ) {
            int c = raw.codePointAt(i);
            i += Character.charCount(c);
            // Format characters include the bidi overrides and zero-width joiners a name could use
            // to disguise itself.
            int type = Character.getType(c);
            if (c == '\u00A7' || type == Character.FORMAT || type == Character.SURROGATE
                    || (Character.isISOControl(c) && !Character.isWhitespace(c))) {
                continue;
            }
            if (Character.isWhitespace(c)) {
                space = out.length() > 0;
                continue;
            }
            if (space) {
                out.append(' ');
                kept++;
                space = false;
            }
            out.appendCodePoint(c);
            kept++;
        }
        return out.toString().trim();
    }

    /**
     * The requester line for chat, with every {@code .} swapped for a one-dot leader
     * ({@code U+2024}), which looks the same.
     *
     * <p>BungeeCord sends chat as legacy text through {@code fromLegacyText} (D76), and so does
     * Bukkit's fallback; on those paths a word shaped like a domain becomes a clickable link.
     * Velocity's serializer does not extract links, so there the swap is only harmless. A display name is anybody's to
     * choose, so "free-rank.gg" beside a link code would be a link the warning sits under. Only the
     * chat line needs this: the dialog's text never links.
     */
    static String unlinkable(String text) {
        return text.replace('.', '\u2024');
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
        return "LinkCodePrompt{code='" + code + "', expiresAtMs=" + expiresAtMs
                + ", requestedBy='" + requestedBy + "'}";
    }
}
