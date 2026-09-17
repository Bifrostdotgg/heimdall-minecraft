package com.heimdall.core.punish;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The words a punishment is described in, and the one function that makes user text safe to put
 * next to them.
 *
 * <p>Its own class because the screens and the announcements now say the same things - "Permanent
 * ban", "temporarily muted", "3d 4h" - in two different places, and a type that reads
 * "Temporary ban" on the disconnect screen and "banned" in chat is the sort of disagreement
 * nobody notices until a player quotes one at a moderator reading the other.
 *
 * <p>Everything here is a pure function of its arguments, with no state and no platform, so the
 * vocabulary is testable without a server.
 */
public final class PunishmentText {

    /** What an issuer with no recorded name is called. */
    public static final String CONSOLE = "Console";

    /** {@code §#rrggbb}, the serializer's own hex spelling. Stripped before the pair form. */
    private static final Pattern HEX_CODE = Pattern.compile("[\u00A7&]#[0-9A-Fa-f]{6}");

    /** One legacy code: a colour, a format, reset, or the {@code x} that opens the hex run. */
    private static final Pattern LEGACY_CODE = Pattern.compile("[\u00A7&][0-9A-Fa-fK-Ok-oRrXx]");

    /**
     * A MiniMessage-shaped tag. Anchored on a letter, a slash or a bang after the {@code <} so an
     * ordinary {@code <3} or {@code a < b} in a reason survives being written by a human.
     */
    private static final Pattern TAG = Pattern.compile("<[A-Za-z/!][^<>]*>");

    /**
     * How many times {@link #sanitise} repeats before giving up.
     *
     * <p>Generous: each pass strips at least one character or stops, so anything a human types
     * settles in two or three. A reason long enough to need more is adversarial, and by then the
     * §-codes and tags left in it have been thinned that many times over.
     */
    private static final int MAX_CLEAN_PASSES = 8;

    private static final long SECONDS_PER_MINUTE = 60L;
    private static final long SECONDS_PER_HOUR = 60L * 60L;
    private static final long SECONDS_PER_DAY = 24L * 60L * 60L;

    /** {@code yyyy/MM/dd HH:mm}, in whatever zone the server is set to. */
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm");

    private PunishmentText() {
    }

    /**
     * What a punishment of this type is called, on a screen.
     *
     * <p>The permanence is part of the name rather than a separate line, because "Temporary ban"
     * with no length beside it is still an answer and "Ban" with no length is not.
     */
    public static String typeLabel(String type, boolean permanent) {
        String key = key(type);
        if ("ban".equals(key) || "tempban".equals(key)) {
            return permanent ? "Permanent ban" : "Temporary ban";
        }
        if ("ipban".equals(key) || "geo".equals(key) || "subnet".equals(key)) {
            // A country ban and a subnet ban are IP bans with a wider selector, and the player
            // reading the screen was refused for the same reason: where they connected from.
            return permanent ? "Permanent IP ban" : "Temporary IP ban";
        }
        if ("mute".equals(key) || "tempmute".equals(key)) {
            return permanent ? "Permanent mute" : "Temporary mute";
        }
        if ("kick".equals(key)) {
            return "Kick";
        }
        if ("warn".equals(key)) {
            return "Warning";
        }
        return capitalise(key);
    }

    /**
     * How a new punishment is announced, or {@code null} for a type nobody is announced for.
     *
     * <p>{@code null} for {@code geo}, {@code subnet} and {@code freeze}: none of them name a
     * player, and a country-wide ban is not a moderation event a server should read as one.
     */
    public static String issueVerb(String type, boolean permanent) {
        String key = key(type);
        if ("ban".equals(key) || "tempban".equals(key)) {
            return permanent ? "banned" : "temporarily banned";
        }
        if ("ipban".equals(key)) {
            return permanent ? "IP-banned" : "temporarily IP-banned";
        }
        if ("mute".equals(key) || "tempmute".equals(key)) {
            return permanent ? "muted" : "temporarily muted";
        }
        if ("kick".equals(key)) {
            return "kicked";
        }
        if ("warn".equals(key)) {
            return "warned";
        }
        return null;
    }

    /**
     * How a lifted punishment is announced, from either spelling of the action.
     *
     * <p>A moderator types {@code unban}; a {@code punish.revoke} frame from the bot names the
     * punishment type ({@code ban}) that is being lifted. One table, so the two cannot word the
     * same event differently.
     */
    public static String revokeVerb(String typeOrVerb) {
        String key = key(typeOrVerb);
        if ("ban".equals(key) || "tempban".equals(key) || "ipban".equals(key)
                || "unban".equals(key)) {
            return "unbanned";
        }
        if ("mute".equals(key) || "tempmute".equals(key) || "unmute".equals(key)) {
            return "unmuted";
        }
        if ("warn".equals(key) || "unwarn".equals(key)) {
            return "unwarned";
        }
        if ("rollback".equals(key)) {
            return "revoked a punishment for";
        }
        return null;
    }

    /** The issuer's name, or {@link #CONSOLE} when there is not one worth showing. */
    public static String issuer(String staff) {
        String name = sanitise(staff);
        if (name.isEmpty() || "console".equals(name.toLowerCase(Locale.ROOT))) {
            return CONSOLE;
        }
        return name;
    }

    /**
     * A duration a human reads at a glance, or {@code null} for permanent.
     *
     * <p>Two units at most. "3d 4h" is the answer to "how long"; "3d 4h 17m 9s" is an answer to a
     * question nobody asked. Seconds in and seconds out when that is all there is, so a 30 second
     * mute reads as {@code 30s} rather than being rounded up to the minute.
     *
     * <p>The same shape as {@code formatDuration} in {@code packages/shared}, so the screen a
     * player sees and the dashboard row a moderator reads say the same thing.
     */
    public static String compactDuration(Long seconds) {
        if (seconds == null || seconds.longValue() <= 0L) {
            return null;
        }
        long total = seconds.longValue();
        long days = total / SECONDS_PER_DAY;
        long hours = (total % SECONDS_PER_DAY) / SECONDS_PER_HOUR;
        long minutes = (total % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE;
        long rest = total % SECONDS_PER_MINUTE;
        if (days > 0) {
            return hours > 0 ? days + "d " + hours + "h" : days + "d";
        }
        if (hours > 0) {
            return minutes > 0 ? hours + "h " + minutes + "m" : hours + "h";
        }
        if (minutes > 0) {
            return rest > 0 ? minutes + "m " + rest + "s" : minutes + "m";
        }
        return rest + "s";
    }

    /**
     * {@code yyyy/MM/dd HH:mm} in the server's own zone, or {@code ""} for an unknown instant.
     *
     * <p>The server's zone rather than UTC: the screen is read by a player on that server, and
     * "expires at 03:00" means something to them only if it is the clock the server runs on. The
     * wire stays ISO instants, so nothing about storage depends on where the box is.
     */
    public static String stamp(long epochMillis) {
        if (epochMillis <= 0L) {
            return "";
        }
        try {
            return STAMP.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()));
        } catch (RuntimeException unrenderable) {
            return "";
        }
    }

    /**
     * Makes one user-controlled segment safe to drop into a formatted line.
     *
     * <p>Three things a reason, a target name or an issuer name must not be able to do.
     *
     * <p><strong>Colour.</strong> An announcement is assembled as text and parsed once, and on
     * the Bukkit family every finished component is serialised back to §-codes on its way to a
     * kick screen. So a reason of {@code §r§8(silent) §fNotch} would reset the formatting and
     * print a convincing forgery of a silent announcement about somebody else. Both the § and the
     * &amp; spellings go, along with the {@code §x§f§f…} and {@code §#ffffff} hex forms, and any
     * § left over afterwards.
     *
     * <p><strong>Tags.</strong> The screens are MiniMessage. The template engine escapes values
     * on its way in, which is the real defence, and this is the belt for the paths that do not go
     * through it and for the legacy serialisation on the other end.
     *
     * <p><strong>Line breaks.</strong> Folded to spaces, so one announcement stays one line. A
     * screen is multi-line by design, but a reason that adds its own lines to one is a reason
     * that can push the appeal link off the bottom.
     *
     * <h2>Run to a fixpoint, not once</h2>
     *
     * <p>One pass in a fixed order composes into an escape. {@code <§4red>} is not a tag while
     * the §4 is in it, so the tag pass leaves it; the legacy pass then removes the §4 and hands
     * back a live {@code <red>} that nothing looks at again. {@code <&4red>} is the same trick in
     * the other spelling, and the two passes can be arranged into that shape whichever order they
     * run in, because each one's output is the other one's input.
     *
     * <p>So the passes repeat until the string stops changing. {@link #MAX_CLEAN_PASSES} bounds
     * it rather than trusting the loop to converge: every pass only deletes, so a string of
     * length n settles in at most n rounds and the cap is never the thing that ends it, but a
     * reason arrives from a moderator and an unbounded loop over user input is not something to
     * leave to a proof.
     */
    public static String sanitise(String value) {
        if (value == null) {
            return "";
        }
        String text = value.replace('\n', ' ').replace('\r', ' ');
        for (int pass = 0; pass < MAX_CLEAN_PASSES; pass++) {
            String before = text;
            text = TAG.matcher(text).replaceAll("");
            text = HEX_CODE.matcher(text).replaceAll("");
            text = LEGACY_CODE.matcher(text).replaceAll("");
            if (text.equals(before)) {
                break;
            }
        }
        return text.replace("\u00A7", "").trim();
    }

    private static String key(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String capitalise(String value) {
        if (value.isEmpty()) {
            return "";
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}
