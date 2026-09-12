package com.heimdall.core.punish;

import com.heimdall.core.util.Strings;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LiteBans-shaped option parser: flags anywhere, the first whole duration token anywhere after the
 * target is the duration, and everything else is the reason.
 *
 * <h2>Whole tokens, and the duration can be anywhere</h2>
 *
 * <p>Two rules that used to be one mistake each. The duration was only read from the token
 * immediately after the name, so {@code /ban Steve griefing 1d} banned Steve forever with the
 * reason "griefing 1d" - the shape a moderator types when the reason came to mind first. And
 * {@code looksLikeDuration} searched inside the token rather than matching it, so {@code abc5m}
 * was a duration and {@code 1day-old} was five minutes and a bit of a lie.
 *
 * <p>So: the FIRST token after the target that is a duration <em>in its entirety</em> becomes the
 * duration and leaves the reason; every other non-flag token stays in the reason, in the order it
 * was typed. A second duration-looking token is reason text, which is what "banned for 2 houses
 * 1d" needs to mean.
 *
 * <h2>Seconds, not minutes</h2>
 *
 * <p>{@code /ban Steve 30s} used to round up to a minute, because the whole pipeline was
 * minute-granular. Nothing about a punishment needs that: the wire, the mirror and the screens all
 * carry instants, and a 30 second mute is a thing moderators ask for.
 */
public final class PunishmentParser {

    /**
     * One duration token, whole, with at least one count-and-unit pair and nothing else.
     *
     * <p>Anchored at both ends on purpose - see the class javadoc. The unit alternatives are
     * ordered longest-prefix-first within each family only where it matters ({@code mo} before
     * {@code m}); the regex engine backtracks into the longer spellings for the rest, so
     * {@code 5minutes} and {@code 5m} both match.
     */
    private static final Pattern DURATION = Pattern.compile(
            "(?i)^(?:\\d++(?:mo|months?|y|years?|w|weeks?|d|days?|h|hrs?|hours?"
                    + "|m|mins?|minutes?|s|secs?|seconds?))+$");

    /** One count-and-unit pair inside an already-validated token. */
    private static final Pattern PART = Pattern.compile(
            "(?i)(\\d++)(mo|months?|y|years?|w|weeks?|d|days?|h|hrs?|hours?"
                    + "|m|mins?|minutes?|s|secs?|seconds?)");

    /** Longest token the duration matcher will look at. A real one is under ten characters. */
    private static final int MAX_DURATION_TOKEN_CHARS = 40;

    private static final long SECONDS_PER_MINUTE = 60L;
    private static final long SECONDS_PER_HOUR = 60L * 60L;
    private static final long SECONDS_PER_DAY = 24L * SECONDS_PER_HOUR;
    private static final long SECONDS_PER_WEEK = 7L * SECONDS_PER_DAY;
    private static final long SECONDS_PER_MONTH = 30L * SECONDS_PER_DAY;
    private static final long SECONDS_PER_YEAR = 365L * SECONDS_PER_DAY;

    /**
     * The longest punishment the parser will accept: a hundred years.
     *
     * <p>A cap rather than saturation, and the same one the shared TypeScript grammar uses. Past
     * it a moderator has typed a number, not a length - {@code 999999999999y} is a slip, and a
     * ban that silently became "the maximum" would hide it. The token is refused, so it stays in
     * the reason where it is visible.
     */
    static final long MAX_SECONDS = 100L * SECONDS_PER_YEAR;

    /** The longest punishment any surface will issue, in years. */
    public static final int MAX_ISSUE_YEARS = 10;

    /**
     * The longest punishment any surface will issue, in seconds.
     *
     * <p>Stricter than {@link #MAX_SECONDS}, and deliberately a different number. The grammar
     * accepts up to a hundred years because it also reads lengths back - an imported row from
     * another plugin, a punishment issued before the ceiling existed - and refusing to render
     * one would be worse than showing it. What a moderator may <em>issue</em> is ten years,
     * which is the same ceiling the slash command and the dashboard form enforce: past that the
     * length is indistinguishable from permanent to everyone involved, and {@code perm} says so
     * honestly.
     */
    public static final long MAX_ISSUE_SECONDS = MAX_ISSUE_YEARS * SECONDS_PER_YEAR;

    private PunishmentParser() {
    }

    public static final class Parsed {
        public final boolean silent;
        public final boolean publicFlag;
        public final String target;
        /** How long it lasts, in seconds, or {@code null} for permanent. */
        public final Long durationSeconds;
        public final String reason;
        /** From {@code --sender=}; hook/import may use it. Native /hd issue must ignore it. */
        public final String senderOverride;

        Parsed(boolean silent, boolean publicFlag, String target, Long durationSeconds,
                String reason, String senderOverride) {
            this.silent = silent;
            this.publicFlag = publicFlag;
            this.target = target;
            this.durationSeconds = durationSeconds;
            this.reason = reason;
            this.senderOverride = senderOverride;
        }
    }

    /**
     * The options, split from everything else, with nothing else interpreted.
     *
     * <p>Its own type because the revoke verbs need exactly this and nothing more:
     * {@code /unban Steve 3d ban evasion} has no duration, so running it through {@link #parse}
     * would swallow {@code 3d} as one and hand back a truncated reason. One place decides how
     * {@code -s}, {@code -p} and {@code --sender=} are spelled, and two callers read it
     * differently on purpose.
     */
    public static final class Flags {
        public final boolean silent;
        public final boolean publicFlag;
        public final String senderOverride;
        /** Every argument that was not an option, in order. */
        public final List<String> rest;

        Flags(boolean silent, boolean publicFlag, String senderOverride, List<String> rest) {
            this.silent = silent;
            this.publicFlag = publicFlag;
            this.senderOverride = senderOverride;
            this.rest = rest;
        }
    }

    /** Strips the options out of an argument list. Never throws; an empty list yields empty. */
    public static Flags flags(List<String> args) {
        boolean silent = false;
        boolean pub = false;
        String senderOverride = null;
        List<String> rest = new ArrayList<String>();
        if (args == null) {
            return new Flags(false, false, null, rest);
        }
        for (String raw : args) {
            if (raw == null) continue;
            String token = raw.trim();
            if ("-s".equalsIgnoreCase(token)) {
                silent = true;
                continue;
            }
            if ("-p".equalsIgnoreCase(token)) {
                pub = true;
                continue;
            }
            if (token.length() > 9 && token.toLowerCase(Locale.ROOT).startsWith("--sender=")) {
                senderOverride = token.substring(9);
                continue;
            }
            rest.add(token);
        }
        return new Flags(silent, pub, senderOverride, rest);
    }

    public static Parsed parse(List<String> args) {
        if (args == null || args.isEmpty()) {
            throw new IllegalArgumentException("a target is required");
        }
        Flags options = flags(args);
        List<String> rest = options.rest;
        if (rest.isEmpty()) {
            throw new IllegalArgumentException("a target is required");
        }
        String target = rest.get(0);
        Long duration = null;
        boolean durationTaken = false;
        List<String> reason = new ArrayList<String>();
        for (int i = 1; i < rest.size(); i++) {
            String token = rest.get(i);
            if (!durationTaken && looksLikeDuration(token)) {
                durationTaken = true;
                duration = parseDurationSeconds(token);
                continue;
            }
            reason.add(token);
        }
        return new Parsed(options.silent, options.publicFlag, target, duration,
                join(reason), options.senderOverride);
    }

    /**
     * Whether this token, in its entirety, is a usable duration.
     *
     * <p>Whole-token rather than a substring search, which is the difference between
     * {@code /ban Steve 1day-old account} reading as a permanent ban with that reason and reading
     * as a one-day ban on an account whose age nobody mentioned.
     *
     * <p>{@code 0s} and {@code 200y} are <strong>not</strong> durations, which matters more than
     * it looks. A refused token stays in the reason, so {@code /ban Steve 0s spam} bans Steve
     * permanently for "0s spam" - visibly odd, and the moderator fixes it. Had it parsed as
     * "no duration" instead, the same command would have read as a permanent ban with the token
     * silently eaten, which looks exactly like what was asked for.
     */
    public static boolean looksLikeDuration(String token) {
        if (Strings.isBlank(token)) return false;
        String t = token.trim();
        if (t.equalsIgnoreCase("perm") || t.equalsIgnoreCase("permanent")) return true;
        return totalSeconds(t) != null;
    }

    /**
     * @return seconds, or {@code null} for permanent and for anything that is not a duration
     */
    public static Long parseDurationSeconds(String token) {
        if (Strings.isBlank(token)) return null;
        String t = token.trim();
        if (t.equalsIgnoreCase("perm") || t.equalsIgnoreCase("permanent")) return null;
        return totalSeconds(t);
    }

    /**
     * The length of a whole duration token, or {@code null} when it is not one.
     *
     * <p>A {@code Long} rather than an {@code Int}: the cap is a hundred years, and a hundred
     * years in seconds is 3,153,600,000, which does not fit in a signed int. Saturating at
     * {@code Integer.MAX_VALUE} instead would make {@code 100y} and {@code 68y} the same
     * punishment, and the wire carries a JSON number that has no such limit.
     */
    private static Long totalSeconds(String token) {
        // Nothing a human types is longer than this, and the cap keeps a pathological token out
        // of the matcher entirely rather than trusting the pattern to shrug it off.
        if (token.length() > MAX_DURATION_TOKEN_CHARS) return null;
        if (!DURATION.matcher(token).matches()) return null;
        Matcher matcher = PART.matcher(token);
        long seconds = 0L;
        while (matcher.find()) {
            long n;
            try {
                n = Long.parseLong(matcher.group(1));
            } catch (NumberFormatException tooManyDigits) {
                // Longer than a long. Whatever it is, it is past the cap.
                return null;
            }
            String unit = matcher.group(2).toLowerCase(Locale.ROOT);
            long unitSeconds = secondsPerUnit(unit);
            if (n > MAX_SECONDS / unitSeconds) {
                return null;
            }
            seconds += n * unitSeconds;
            if (seconds > MAX_SECONDS) {
                return null;
            }
        }
        // Zero is refused rather than clamped to a second: somebody typed a length they meant,
        // and a one-second ban is not it.
        return seconds <= 0L ? null : Long.valueOf(seconds);
    }

    private static long secondsPerUnit(String unit) {
        if (unit.startsWith("mo")) return SECONDS_PER_MONTH;
        if (unit.startsWith("y")) return SECONDS_PER_YEAR;
        if (unit.startsWith("w")) return SECONDS_PER_WEEK;
        if (unit.startsWith("d")) return SECONDS_PER_DAY;
        if (unit.startsWith("h")) return SECONDS_PER_HOUR;
        if (unit.startsWith("s")) return 1L;
        return SECONDS_PER_MINUTE;
    }

    private static String join(List<String> parts) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (b.length() > 0) b.append(' ');
            b.append(parts.get(i));
        }
        return b.toString();
    }
}
