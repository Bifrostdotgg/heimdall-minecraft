package com.heimdall.core.items;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Finds the item hovers ChatControl writes into a relayed line, and replaces each with {@code [Name]}.
 *
 * <h2>Where the input comes from</h2>
 *
 * <p>ChatControl's {@code [item]} variable turns into a MiniMessage hover in the text Heimdall reads
 * from {@code ChannelPostChatEvent#getMessage()}:
 *
 * <pre>{@code <hover:show_item:netherite_shovel:1:unbreakable:{}:custom_name:'"Spoon"'><gray>[<white>Spoon<gray>]}</pre>
 *
 * <p>Relayed verbatim that reaches Discord as a wall of markup. This class is the one sanctioned
 * edit to an otherwise verbatim line (departure D82 still holds for everything else; this is D86):
 * each well-formed item hover, together with the text it decorates, becomes the plain
 * {@code [Name]} the players saw, and the item itself is handed back for a tooltip image.
 *
 * <h2>Tokenising like MiniMessage, without MiniMessage</h2>
 *
 * <p>The shaded Adventure (4.13.1) predates data components, so the tag is tokenised here following
 * MiniMessage's own rules: arguments separated by {@code :}, an argument quoted with {@code '} or
 * {@code "} may contain {@code :} and {@code >}, and inside quotes a backslash escapes the active
 * quote character or itself. A {@code <} preceded by an odd number of backslashes is an escaped
 * literal, not a tag, which is how ChatControl writes markup a player typed without permission.
 *
 * <h2>What "the text it decorates" is</h2>
 *
 * <p>From the end of the tag to the matching {@code </hover>} (inclusive), or to the start of the
 * next hover, or to the end of the line: ChatControl omits the closing tag when the hover runs to the
 * end, and each variable key is replaced once per message, so several hovers in one line are
 * possible and each one's region stops where the next begins.
 *
 * <h2>Anything malformed is left alone</h2>
 *
 * <p>A tag that does not tokenise (no closing {@code >}, an unterminated quote) or does not describe
 * an item is not touched: the line keeps those bytes exactly. Failing to recognise a hover costs the
 * reader some markup, which is today's behaviour; mangling a line costs them words.
 *
 * <p>Stateless and thread-safe. Bounded: at most {@value #MAX_ATTEMPTS} candidate tags are
 * examined per line, so a line stuffed with broken openings costs a fixed number of scans. Never
 * logs, and never throws on any input.
 */
public final class HoverTags {

    /** Hovers acted on per line. ChatControl replaces each variable key once; this is a backstop. */
    static final int MAX_TAGS = 16;

    /** Candidate {@code <hover:} openings examined per line, well-formed or not. */
    static final int MAX_ATTEMPTS = 64;

    private HoverTags() {
    }

    /** One item hover found in a line: where it was, and what it described. */
    public static final class Found {

        private final int start;
        private final int end;
        private final ChatItem item;

        Found(int start, int end, ChatItem item) {
            this.start = start;
            this.end = end;
            this.item = item;
        }

        /** Index of the tag's {@code <}. */
        public int start() {
            return start;
        }

        /** Index just past the decorated text (and the closing tag, if there was one). */
        public int end() {
            return end;
        }

        public ChatItem item() {
            return item;
        }
    }

    /** A rewritten line and the items, in the order they appeared. */
    public static final class Rewrite {

        private final String text;
        private final List<ChatItem> items;
        private final List<String> names;

        Rewrite(String text, List<ChatItem> items, List<String> names) {
            this.text = text;
            this.items = Collections.unmodifiableList(items);
            this.names = Collections.unmodifiableList(names);
        }

        /** The line with every item hover replaced by {@code [Name]}. */
        public String text() {
            return text;
        }

        /** The items, one per replaced hover, in line order. */
        public List<ChatItem> items() {
            return items;
        }

        /** The name each item was replaced with (without the brackets), parallel to {@link #items()}. */
        public List<String> names() {
            return names;
        }

        /** Counts only: the text is a player's line. */
        @Override
        public String toString() {
            return "Rewrite{items=" + items.size() + ", length=" + text.length() + "}";
        }
    }

    /**
     * A cheap pre-check, so a line with no hover costs one scan and nothing else: whether
     * {@code show_item} appears at all, in any case.
     */
    public static boolean mightContainItem(String text) {
        return text != null && indexOfIgnoreCase(text, "show_item", 0) >= 0;
    }

    /**
     * Replaces every item hover in {@code text}.
     *
     * @return {@code null} when the line holds no well-formed item hover, in which case the caller
     *     must relay {@code text} unchanged
     */
    public static Rewrite rewrite(String text, ItemTranslations translations) {
        if (!mightContainItem(text)) {
            return null;
        }
        List<Found> found = scan(text, translations);
        if (found.isEmpty()) {
            return null;
        }
        StringBuilder out = new StringBuilder(text.length());
        List<ChatItem> items = new ArrayList<ChatItem>(found.size());
        List<String> names = new ArrayList<String>(found.size());
        int last = 0;
        for (Found hover : found) {
            String name = hover.item().displayName(translations);
            out.append(text, last, hover.start()).append('[').append(name).append(']');
            last = hover.end();
            items.add(hover.item());
            names.add(name);
        }
        out.append(text, last, text.length());
        return new Rewrite(out.toString(), items, names);
    }

    /** Every well-formed item hover in {@code text}, in order. Never {@code null}. */
    public static List<Found> scan(String text, ItemTranslations translations) {
        if (text == null || text.isEmpty()) {
            return Collections.emptyList();
        }
        List<Found> out = new ArrayList<Found>();
        int from = 0;
        int attempts = 0;
        while (out.size() < MAX_TAGS && attempts++ < MAX_ATTEMPTS) {
            int open = nextOpen(text, from);
            if (open < 0) {
                break;
            }
            Tag tag = tokenize(text, open + 1);
            if (tag == null) {
                from = open + 1;
                continue;
            }
            if (tag.args.size() < 3 || !"hover".equalsIgnoreCase(tag.args.get(0).trim())
                    || !"show_item".equalsIgnoreCase(tag.args.get(1).trim())) {
                // A whole tag that is not an item (show_text, show_entity): resume after it, so a
                // show_item quoted inside its argument is not mistaken for a tag of its own.
                from = tag.end;
                continue;
            }
            ChatItem item = ShowItem.parse(tag.args.subList(2, tag.args.size()), translations);
            if (item == null) {
                from = tag.end;
                continue;
            }
            int end = regionEnd(text, tag.end);
            out.add(new Found(open, end, item));
            from = end;
        }
        return out;
    }

    /** The next unescaped {@code <hover:} at or after {@code from}, or -1. */
    private static int nextOpen(String text, int from) {
        int at = from;
        while (true) {
            int found = indexOfIgnoreCase(text, "<hover:", at);
            if (found < 0) {
                return -1;
            }
            if (!escaped(text, found)) {
                return found;
            }
            at = found + 1;
        }
    }

    /** Where the decorated text after a tag ending at {@code from} stops. */
    private static int regionEnd(String text, int from) {
        int close = indexOfIgnoreCase(text, "</hover", from);
        while (close >= 0 && escaped(text, close)) {
            close = indexOfIgnoreCase(text, "</hover", close + 1);
        }
        int next = nextOpen(text, from);
        if (close >= 0 && (next < 0 || close < next)) {
            int gt = text.indexOf('>', close);
            return gt < 0 ? text.length() : gt + 1;
        }
        return next >= 0 ? next : text.length();
    }

    /** Whether the character at {@code index} is preceded by an odd run of backslashes. */
    private static boolean escaped(String text, int index) {
        int backslashes = 0;
        for (int i = index - 1; i >= 0 && text.charAt(i) == '\\'; i--) {
            backslashes++;
        }
        return backslashes % 2 == 1;
    }

    /** A tokenised tag: its arguments, unquoted and unescaped, and the index just past its {@code >}. */
    static final class Tag {

        final List<String> args;
        final int end;

        Tag(List<String> args, int end) {
            this.args = args;
            this.end = end;
        }
    }

    /**
     * Tokenises a tag whose name starts at {@code start} (just past the {@code <}).
     *
     * @return {@code null} if there is no closing {@code >} outside quotes, a quote is unterminated,
     *     or a quoted argument is followed by anything but a separator
     */
    static Tag tokenize(String text, int start) {
        List<String> args = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        int i = start;
        boolean argStart = true;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (argStart && (c == '\'' || c == '"')) {
                char quote = c;
                i++;
                boolean closed = false;
                while (i < text.length()) {
                    char q = text.charAt(i);
                    if (q == '\\' && i + 1 < text.length()
                            && (text.charAt(i + 1) == quote || text.charAt(i + 1) == '\\')) {
                        current.append(text.charAt(i + 1));
                        i += 2;
                        continue;
                    }
                    if (q == quote) {
                        closed = true;
                        i++;
                        break;
                    }
                    current.append(q);
                    i++;
                }
                if (!closed || i >= text.length()) {
                    return null;
                }
                char after = text.charAt(i);
                if (after != ':' && after != '>') {
                    return null;
                }
                argStart = false;
                continue;
            }
            if (c == ':') {
                args.add(current.toString());
                current.setLength(0);
                argStart = true;
                i++;
                continue;
            }
            if (c == '>') {
                args.add(current.toString());
                return new Tag(args, i + 1);
            }
            if (c == '<') {
                // An unquoted '<' cannot be inside a tag: this was never a tag.
                return null;
            }
            current.append(c);
            argStart = false;
            i++;
        }
        return null;
    }

    static int indexOfIgnoreCase(String text, String needle, int from) {
        int max = text.length() - needle.length();
        for (int i = Math.max(0, from); i <= max; i++) {
            if (text.regionMatches(true, i, needle, 0, needle.length())) {
                return i;
            }
        }
        return -1;
    }
}
