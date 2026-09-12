package com.heimdall.core.text;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;

/**
 * A MiniMessage template with {@code {token}} placeholders and optional {@code [ ... ]} segments.
 *
 * <p>The punishment screens and the two announcement lines are written by guild owners on the
 * dashboard, and the same template has to read correctly for a permanent ban and a temporary one,
 * for a guild with an appeal form and one without, and for a punishment with a reason and one
 * issued in a hurry. Writing a variant for every combination is eight screens becoming forty; the
 * alternative is one rule.
 *
 * <h2>The rule</h2>
 *
 * <p><strong>Text inside {@code [ ... ]} is dropped when any placeholder inside it resolved
 * empty.</strong> So {@code [<gray>Reason</gray> » {reason}]} disappears on a punishment with no
 * reason, and the Length line disappears on a permanent ban, without either template knowing that
 * the other case exists.
 *
 * <p><strong>A line the drop left with no words on it is removed.</strong> "No words" means: with
 * the {@code <...>} tag shapes taken out, nothing but whitespace - so {@code <gray></gray>}, which
 * is what {@code <gray>[Reason: {reason}]</gray>} leaves behind, goes the same way a bare blank
 * line does. Otherwise a permanent ban screen carries an empty row where its Length used to be,
 * and the empty rows accumulate with each dropped segment. A line that lost nothing is never
 * considered, so a line that was already blank in the template is kept, because that one was a
 * decision.
 *
 * <p><strong>That means a decoration on the same line as a dropped segment goes with it.</strong>
 * Put a divider, a bullet or any other formatting-only flourish on its own line, not at the end of
 * a row that can disappear: a line holding only a divider and a dropped Reason clause has no words
 * left, so the divider is removed too. The defaults in
 * {@link com.heimdall.core.punish.PunishmentScreens} are written that way on purpose.
 *
 * <p><strong>A literal bracket is written {@code \[} or {@code \]}.</strong> Nothing else is
 * escapable: a backslash before any other character is a backslash.
 *
 * <p><strong>Nesting is not supported.</strong> The first unescaped {@code ]} closes a segment,
 * and a {@code [} inside one is a literal bracket. Nested optional segments would need a
 * precedence rule for "the inner one is empty but the outer one is not", and nobody writing a ban
 * screen wants to reason about that. Two adjacent segments do what nesting is usually reached
 * for.
 *
 * <h2>Values are escaped, once, before anything is parsed</h2>
 *
 * <p>Every value goes through {@link Msg#escapeMini(String)} on its way in, and the finished
 * string is parsed as MiniMessage exactly once. A reason of {@code <red>} is therefore text, not
 * formatting, and a reason of {@code </click>} cannot close a tag the template opened.
 *
 * <p>{@link Values#putRaw} is the one exception, for a value that is itself already-rendered
 * template output - {@code {base}}, and nothing else. It is not an escape hatch for a value that
 * came from a player.
 *
 * <h2>Threading</h2>
 *
 * <p>Stateless. {@link Values} is not thread-safe and is not meant to be: one is built and used
 * on the thread rendering one message.
 */
public final class Template {

    private Template() {
    }

    /** An empty value set, to be filled in and handed to {@link #fill} or {@link #render}. */
    public static Values values() {
        return new Values();
    }

    /**
     * Substitutes, drops and cleans up, without parsing.
     *
     * @param template the raw template; {@code null} or empty yields {@code ""}
     * @return MiniMessage source, ready to be parsed once
     */
    public static String fill(String template, Values values) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        // The one door every guild-authored template goes through, and so the one place worth
        // checking whether it will render as it reads. See Msg#checkTemplate.
        Msg.checkTemplate(template);
        Values used = values == null ? new Values() : values;
        Output out = new Output();
        scan(template, used, out, true);
        return out.finish();
    }

    /**
     * {@link #fill}, then parsed as MiniMessage. A broken template renders as its own plain text.
     *
     * <p>The template rather than the filled string is what the one warning is keyed on: the
     * values differ per punishment, so keying on the finished text would report the same broken
     * screen once per ban.
     */
    public static Component render(String template, Values values) {
        return Msg.mini(fill(template, values), template);
    }

    /**
     * The values one render uses, and whether each is escaped on the way in.
     *
     * <p>Insertion-ordered, which matters only for a diagnostic: rendering never depends on the
     * order values were supplied in.
     */
    public static final class Values {

        private final Map<String, String> byName = new LinkedHashMap<String, String>();
        private final Map<String, Boolean> raw = new LinkedHashMap<String, Boolean>();

        Values() {
        }

        /** Sets a value. It is escaped before substitution, so it cannot introduce tags. */
        public Values put(String name, String value) {
            if (name == null) return this;
            byName.put(name, value == null ? "" : value);
            raw.put(name, Boolean.FALSE);
            return this;
        }

        /**
         * Sets a value that is already MiniMessage and must not be escaped.
         *
         * <p>For {@code {base}}, whose value is the rendered base segment. Never for anything a
         * player, a moderator or a config string supplied.
         */
        public Values putRaw(String name, String value) {
            if (name == null) return this;
            byName.put(name, value == null ? "" : value);
            raw.put(name, Boolean.TRUE);
            return this;
        }

        /** Whether this token is missing or resolved to nothing. Unknown tokens are empty. */
        boolean isEmpty(String name) {
            String value = byName.get(name);
            return value == null || value.isEmpty();
        }

        /** The substitution for a token: escaped, unless it was supplied raw. */
        String resolve(String name) {
            String value = byName.get(name);
            if (value == null || value.isEmpty()) {
                return "";
            }
            return Boolean.TRUE.equals(raw.get(name)) ? value : Msg.escapeMini(value);
        }
    }

    /**
     * Walks a template once, appending to {@code out}.
     *
     * @param allowSegments {@code false} inside a segment, where a bracket is a literal bracket
     */
    private static void scan(String source, Values values, Output out, boolean allowSegments) {
        int i = 0;
        int length = source.length();
        while (i < length) {
            char c = source.charAt(i);
            if (c == '\\' && i + 1 < length && isBracket(source.charAt(i + 1))) {
                out.text(String.valueOf(source.charAt(i + 1)));
                i += 2;
                continue;
            }
            if (c == '[' && allowSegments) {
                int close = closingBracket(source, i + 1);
                if (close < 0) {
                    // An opening bracket with no partner is a bracket. Refusing to render the
                    // screen because somebody typed one would be a worse answer than printing it.
                    out.text("[");
                    i++;
                    continue;
                }
                Output segment = new Output();
                scan(source.substring(i + 1, close), values, segment, false);
                if (segment.sawEmptyToken) {
                    out.markEmptied();
                } else {
                    out.text(segment.raw());
                }
                i = close + 1;
                continue;
            }
            if (c == '{') {
                int end = tokenEnd(source, i);
                if (end > 0) {
                    String name = source.substring(i + 1, end);
                    if (values.isEmpty(name)) {
                        out.markEmptied();
                    }
                    out.text(values.resolve(name));
                    i = end + 1;
                    continue;
                }
            }
            out.text(String.valueOf(c));
            i++;
        }
    }

    /**
     * The first unescaped {@code ]} at or after {@code from}, or {@code -1}.
     *
     * <p>"Escaped" means exactly what {@link #scan} means by it: a backslash before a bracket, and
     * nothing else. This used to skip a backslash and whatever followed it, so the two disagreed
     * about a backslash before any other character - {@code [x\\] y]} closed at the first bracket
     * here and at the second there, and the segment the scanner then walked was not the substring
     * this method had measured. One rule, in one place, so a template cannot mean two things.
     */
    private static int closingBracket(String source, int from) {
        for (int i = from; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '\\' && i + 1 < source.length() && isBracket(source.charAt(i + 1))) {
                i++;
                continue;
            }
            if (c == ']') {
                return i;
            }
        }
        return -1;
    }

    /** The two characters a backslash escapes. Everything else is a backslash. */
    private static boolean isBracket(char c) {
        return c == '[' || c == ']';
    }

    /**
     * The index of the {@code }} closing a token that starts at {@code start}, or {@code -1} when
     * this is not a token. Names are letters, digits and underscores, so a MiniMessage template
     * containing an ordinary brace is left alone.
     */
    private static int tokenEnd(String source, int start) {
        for (int i = start + 1; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '}') {
                return i > start + 1 ? i : -1;
            }
            boolean nameChar = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_';
            if (!nameChar) {
                return -1;
            }
        }
        return -1;
    }

    /**
     * The rendered text, line by line, and which lines lost something.
     *
     * <p>Line-aware rather than one buffer because the blank-line rule needs to know whether a
     * line is empty <em>because</em> something on it was dropped. A template's own blank line is
     * a decision; one left behind by a missing appeal URL is litter.
     */
    private static final class Output {

        private final List<StringBuilder> lines = new ArrayList<StringBuilder>();
        private final List<Boolean> emptied = new ArrayList<Boolean>();
        private boolean sawEmptyToken;

        Output() {
            lines.add(new StringBuilder());
            emptied.add(Boolean.FALSE);
        }

        void text(String value) {
            if (value == null || value.isEmpty()) {
                return;
            }
            int from = 0;
            while (true) {
                int newline = value.indexOf('\n', from);
                if (newline < 0) {
                    current().append(value, from, value.length());
                    return;
                }
                current().append(value, from, newline);
                lines.add(new StringBuilder());
                emptied.add(Boolean.FALSE);
                from = newline + 1;
            }
        }

        void markEmptied() {
            sawEmptyToken = true;
            emptied.set(emptied.size() - 1, Boolean.TRUE);
        }

        /** Every line, joined, with nothing removed. What a kept segment contributes. */
        String raw() {
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < lines.size(); i++) {
                if (i > 0) out.append('\n');
                out.append(lines.get(i));
            }
            return out.toString();
        }

        /** Every line, joined, minus the ones a drop left behind. */
        String finish() {
            StringBuilder out = new StringBuilder();
            boolean first = true;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).toString();
                if (Boolean.TRUE.equals(emptied.get(i)) && isBlankUnderTheTags(line)) {
                    continue;
                }
                if (!first) out.append('\n');
                out.append(line);
                first = false;
            }
            return out.toString();
        }

        private StringBuilder current() {
            return lines.get(lines.size() - 1);
        }

        /**
         * Whether a line that lost something has any words left, as opposed to only formatting.
         *
         * <p>Trimming alone was not enough. A guild that wraps a whole row rather than only its
         * value - {@code <gray>[Reason: {reason}]</gray>}, which is what the dashboard's editor
         * makes natural - leaves {@code <gray></gray>} behind when the segment drops, and a pair
         * of tags with nothing between them renders as a blank line. That is the same litter the
         * rule exists to remove, wearing tags.
         *
         * <p>"Empty" is therefore: with the tag shapes taken out, nothing but whitespace. A line
         * that lost nothing is never considered, so a divider row made entirely of tags and
         * padding stays exactly where the guild put it.
         */
        private static boolean isBlankUnderTheTags(String line) {
            return Msg.stripTags(line).trim().isEmpty();
        }
    }
}
