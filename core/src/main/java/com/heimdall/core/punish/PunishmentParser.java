package com.heimdall.core.punish;

import com.heimdall.core.util.Strings;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LiteBans-shaped option parser: flags anywhere, first duration-like token is
 * the duration, the rest is the reason.
 */
public final class PunishmentParser {

    private static final Pattern DURATION = Pattern.compile(
            "(?i)^(?:perm(?:anent)?|(\\d+)\\s*(mo|months?|y|years?|w|weeks?|d|days?|h|hours?|m|mins?|minutes?|s|secs?|seconds?))$");
    private static final Pattern TOKEN = Pattern.compile("(?i)(\\d+)(mo|months?|y|years?|w|weeks?|d|days?|h|hours?|m|mins?|minutes?|s|secs?|seconds?)");

    private PunishmentParser() {
    }

    public static final class Parsed {
        public final boolean silent;
        public final boolean publicFlag;
        /**
         * From {@code -h}. A hidden punishment is enforced as usual but kept out of in-game staff
         * lookups, so it is always {@link #silent} as well - one flag cannot hide a punishment from
         * the lookups while a chat line announces it.
         */
        public final boolean hidden;
        public final String target;
        public final Integer durationMinutes;
        public final String reason;
        /** From {@code --sender=}; hook/import may use it. Native /hd issue must ignore it. */
        public final String senderOverride;

        Parsed(boolean silent, boolean publicFlag, boolean hidden, String target,
                Integer durationMinutes, String reason, String senderOverride) {
            this.silent = silent;
            this.publicFlag = publicFlag;
            this.hidden = hidden;
            this.target = target;
            this.durationMinutes = durationMinutes;
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
     * {@code -s}, {@code -p}, {@code -h} and {@code --sender=} are spelled, and two callers read it
     * differently on purpose.
     */
    public static final class Flags {
        public final boolean silent;
        public final boolean publicFlag;
        /** From {@code -h}; implies {@link #silent}. */
        public final boolean hidden;
        public final String senderOverride;
        /** Every argument that was not an option, in order. */
        public final List<String> rest;

        Flags(boolean silent, boolean publicFlag, boolean hidden, String senderOverride,
                List<String> rest) {
            this.silent = silent;
            this.publicFlag = publicFlag;
            this.hidden = hidden;
            this.senderOverride = senderOverride;
            this.rest = rest;
        }
    }

    /** Strips the options out of an argument list. Never throws; an empty list yields empty. */
    public static Flags flags(List<String> args) {
        boolean silent = false;
        boolean pub = false;
        boolean hidden = false;
        String senderOverride = null;
        List<String> rest = new ArrayList<String>();
        if (args == null) {
            return new Flags(false, false, false, null, rest);
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
            if ("-h".equalsIgnoreCase(token)) {
                // Hidden implies silent here rather than at the call sites, so no caller can read
                // one without the other and announce a punishment it is also hiding from lookups.
                hidden = true;
                silent = true;
                continue;
            }
            if (token.length() > 9 && token.toLowerCase(Locale.ROOT).startsWith("--sender=")) {
                senderOverride = token.substring(9);
                continue;
            }
            rest.add(token);
        }
        return new Flags(silent, pub, hidden, senderOverride, rest);
    }

    public static Parsed parse(List<String> args) {
        if (args == null || args.isEmpty()) {
            throw new IllegalArgumentException("a target is required");
        }
        Flags options = flags(args);
        boolean silent = options.silent;
        boolean pub = options.publicFlag;
        boolean hidden = options.hidden;
        String senderOverride = options.senderOverride;
        List<String> rest = options.rest;
        if (rest.isEmpty()) {
            throw new IllegalArgumentException("a target is required");
        }
        String target = rest.get(0);
        Integer duration = null;
        int reasonFrom = 1;
        if (rest.size() > 1 && looksLikeDuration(rest.get(1))) {
            duration = parseDurationMinutes(rest.get(1));
            reasonFrom = 2;
        }
        String reason = join(rest, reasonFrom);
        return new Parsed(silent, pub, hidden, target, duration, reason, senderOverride);
    }

    public static boolean looksLikeDuration(String token) {
        if (Strings.isBlank(token)) return false;
        String t = token.trim();
        if (t.equalsIgnoreCase("perm") || t.equalsIgnoreCase("permanent")) return true;
        return DURATION.matcher(t).matches() || TOKEN.matcher(t).find();
    }

    /**
     * @return minutes, or {@code null} for permanent
     */
    public static Integer parseDurationMinutes(String token) {
        if (Strings.isBlank(token)) return null;
        String t = token.trim();
        if (t.equalsIgnoreCase("perm") || t.equalsIgnoreCase("permanent")) return null;
        Matcher matcher = TOKEN.matcher(t);
        int minutes = 0;
        boolean any = false;
        while (matcher.find()) {
            any = true;
            int n = Integer.parseInt(matcher.group(1));
            String unit = matcher.group(2).toLowerCase(Locale.ROOT);
            if (unit.startsWith("mo")) minutes += n * 30 * 24 * 60;
            else if (unit.startsWith("y")) minutes += n * 365 * 24 * 60;
            else if (unit.startsWith("w")) minutes += n * 7 * 24 * 60;
            else if (unit.startsWith("d")) minutes += n * 24 * 60;
            else if (unit.startsWith("h")) minutes += n * 60;
            else if (unit.equals("s") || unit.startsWith("sec")) minutes += Math.max(1, n / 60);
            else minutes += n;
        }
        if (!any) return null;
        return Integer.valueOf(Math.max(1, minutes));
    }

    private static String join(List<String> parts, int from) {
        if (from >= parts.size()) return "";
        StringBuilder b = new StringBuilder();
        for (int i = from; i < parts.size(); i++) {
            if (b.length() > 0) b.append(' ');
            b.append(parts.get(i));
        }
        return b.toString();
    }
}
