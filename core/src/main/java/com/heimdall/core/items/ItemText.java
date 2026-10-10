package com.heimdall.core.items;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One line of tooltip text, flattened into styled runs.
 *
 * <p>A Minecraft text component is a tree (text, children, translations, inherited style). A tooltip
 * only ever needs the result: a left-to-right sequence of runs, each with a resolved colour and set of
 * decorations. Flattening once, at parse time, keeps the renderer free of component semantics and
 * makes the value trivially comparable, which is what the render cache keys on.
 *
 * <p>Style fields are <em>tri-state</em>: {@code null} means "not specified here", and the renderer
 * fills it from the line's own default (a lore line defaults to dark purple italic, a custom name to
 * the item's rarity colour in italic). That distinction is exactly the one vanilla makes, and
 * collapsing it at parse time would render {@code italic:0b} lore and unstyled lore the same.
 *
 * <p>Immutable.
 */
public final class ItemText {

    /** The empty line. */
    public static final ItemText EMPTY = new ItemText(Collections.<Span>emptyList());

    private final List<Span> spans;

    private ItemText(List<Span> spans) {
        this.spans = spans;
    }

    /** A line made of the given runs, in order. Empty runs are dropped. */
    public static ItemText of(List<Span> spans) {
        if (spans == null || spans.isEmpty()) {
            return EMPTY;
        }
        List<Span> kept = new ArrayList<Span>(spans.size());
        for (Span span : spans) {
            if (span != null && !span.text().isEmpty()) {
                kept.add(span);
            }
        }
        return kept.isEmpty() ? EMPTY : new ItemText(Collections.unmodifiableList(kept));
    }

    /** One unstyled run. */
    public static ItemText plain(String text) {
        if (text == null || text.isEmpty()) {
            return EMPTY;
        }
        return new ItemText(Collections.singletonList(
                new Span(text, null, null, null, null, null, null)));
    }

    /** The runs, in order. Unmodifiable. */
    public List<Span> spans() {
        return spans;
    }

    /** Whether there is no visible text at all. */
    public boolean isEmpty() {
        return spans.isEmpty();
    }

    /** The text without any styling. */
    public String plain() {
        StringBuilder out = new StringBuilder();
        for (Span span : spans) {
            out.append(span.text());
        }
        return out.toString();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ItemText && spans.equals(((ItemText) other).spans);
    }

    @Override
    public int hashCode() {
        return spans.hashCode();
    }

    /**
     * Renders the run count and total length only. Item text is player-authored (anvil names, lore
     * written by other plugins on a player's behalf) and this ends up in debug output the same way
     * {@code ChatMessage.toString()} does, so it follows the same rule.
     */
    @Override
    public String toString() {
        return "ItemText{spans=" + spans.size() + ", length=" + plain().length() + "}";
    }

    /** One styled run of text. Immutable. */
    public static final class Span {

        private final String text;
        private final Integer color;
        private final Boolean bold;
        private final Boolean italic;
        private final Boolean underlined;
        private final Boolean strikethrough;
        private final Boolean obfuscated;

        public Span(String text, Integer color, Boolean bold, Boolean italic, Boolean underlined,
                Boolean strikethrough, Boolean obfuscated) {
            this.text = text == null ? "" : text;
            this.color = color;
            this.bold = bold;
            this.italic = italic;
            this.underlined = underlined;
            this.strikethrough = strikethrough;
            this.obfuscated = obfuscated;
        }

        public String text() {
            return text;
        }

        /** RGB ({@code 0xRRGGBB}), or {@code null} when the line's default applies. */
        public Integer color() {
            return color;
        }

        public Boolean bold() {
            return bold;
        }

        public Boolean italic() {
            return italic;
        }

        public Boolean underlined() {
            return underlined;
        }

        public Boolean strikethrough() {
            return strikethrough;
        }

        public Boolean obfuscated() {
            return obfuscated;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Span)) {
                return false;
            }
            Span that = (Span) other;
            return text.equals(that.text) && eq(color, that.color) && eq(bold, that.bold)
                    && eq(italic, that.italic) && eq(underlined, that.underlined)
                    && eq(strikethrough, that.strikethrough) && eq(obfuscated, that.obfuscated);
        }

        @Override
        public int hashCode() {
            int h = text.hashCode();
            h = 31 * h + (color == null ? 0 : color.hashCode());
            h = 31 * h + (bold == null ? 0 : bold.hashCode());
            h = 31 * h + (italic == null ? 0 : italic.hashCode());
            h = 31 * h + (underlined == null ? 0 : underlined.hashCode());
            h = 31 * h + (strikethrough == null ? 0 : strikethrough.hashCode());
            h = 31 * h + (obfuscated == null ? 0 : obfuscated.hashCode());
            return h;
        }

        /** A stable one-line description for cache keys. Contains the text: never log it. */
        String key() {
            return color + "," + flag(bold) + flag(italic) + flag(underlined) + flag(strikethrough)
                    + flag(obfuscated) + "," + text.length() + ":" + text;
        }

        private static String flag(Boolean value) {
            return value == null ? "-" : value ? "1" : "0";
        }

        private static boolean eq(Object a, Object b) {
            return a == null ? b == null : a.equals(b);
        }

        @Override
        public String toString() {
            return "Span{length=" + text.length() + ", color=" + color + "}";
        }
    }

    /** A stable description for cache keys. Contains the text: never log it. */
    String key() {
        StringBuilder out = new StringBuilder("[");
        for (Span span : spans) {
            out.append(span.key()).append('|');
        }
        return out.append(']').toString();
    }
}
