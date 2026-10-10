package com.heimdall.core.items;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns a text component, in any of the shapes an item hover can carry it, into an {@link ItemText}.
 *
 * <h2>The shapes</h2>
 *
 * <ul>
 *   <li><strong>1.21.5 and later:</strong> SNBT. A plain string is the text itself
 *       ({@code custom_name:'"Spoon"'}); a compound is a component
 *       ({@code {color:"white",italic:0b,text:"Warded Jar"}}); a list is a component followed by
 *       siblings that inherit its style.
 *   <li><strong>1.13 to 1.21.4:</strong> the same tree, but as a JSON document inside an SNBT
 *       string ({@code custom_name:'"{\"text\":\"Spoon\"}"'}, or a lore line in a legacy NBT
 *       holder). Recognised by its shape and parsed with the same reader, since JSON is a subset of
 *       what {@link Snbt} accepts.
 *   <li><strong>Below 1.13:</strong> a legacy string with {@code §} formatting codes.
 * </ul>
 *
 * <p>Nothing here throws. A component that cannot be read becomes its raw text, which on a tooltip
 * is a cosmetic fault, not a reason to drop the item.
 */
public final class TextComponents {

    /** Runs beyond this many on one line are dropped: a tooltip cannot show them anyway. */
    static final int MAX_SPANS = 256;

    /** Component trees deeper than this are cut off rather than recursed into. */
    static final int MAX_DEPTH = 32;

    /** Characters kept per line; the rest is dropped before it ever reaches a renderer. */
    static final int MAX_LINE_CHARS = 1024;

    /** The sixteen named colours, by name. */
    private static final Map<String, Integer> NAMED = new HashMap<String, Integer>();

    /** The same sixteen, by legacy code {@code 0-9a-f}. */
    private static final int[] LEGACY = {
        0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
        0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF,
    };

    static {
        String[] names = {
            "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold",
            "gray", "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white",
        };
        for (int i = 0; i < names.length; i++) {
            NAMED.put(names[i], LEGACY[i]);
        }
        NAMED.put("grey", LEGACY[7]);
        NAMED.put("dark_grey", LEGACY[8]);
    }

    private TextComponents() {
    }

    /** A named colour ({@code "dark_purple"}) or {@code #RRGGBB}, or {@code null} if neither. */
    public static Integer color(String spelled) {
        if (spelled == null) {
            return null;
        }
        String s = spelled.trim().toLowerCase(Locale.ROOT);
        Integer named = NAMED.get(s);
        if (named != null) {
            return named;
        }
        if (s.length() == 7 && s.charAt(0) == '#') {
            try {
                return Integer.parseInt(s.substring(1), 16);
            } catch (NumberFormatException notHex) {
                return null;
            }
        }
        return null;
    }

    /** The colour a legacy code ({@code 0-9a-f}) names, or {@code null}. */
    public static Integer legacyColor(char code) {
        int index = Character.digit(code, 16);
        return index < 0 ? null : LEGACY[index];
    }

    /**
     * Reads one component value as parsed by {@link Snbt}: a string (plain text, JSON, or legacy
     * {@code §} text), a compound, or a list.
     *
     * @param translations resolves {@code translate} keys; {@link ItemTranslations#NONE} is fine
     */
    public static ItemText read(Object value, ItemTranslations translations) {
        ItemTranslations tr = translations == null ? ItemTranslations.NONE : translations;
        if (value == null) {
            return ItemText.EMPTY;
        }
        if (value instanceof String) {
            String text = (String) value;
            Object json = jsonShaped(text);
            if (json != null) {
                value = json;
            } else if (text.indexOf('§') >= 0) {
                return legacy(text);
            } else {
                return ItemText.plain(cap(text));
            }
        }
        List<ItemText.Span> out = new ArrayList<ItemText.Span>();
        flatten(value, Style.NONE, out, tr, 0, new Budget(), new Budget());
        return ItemText.of(capLength(out));
    }

    /**
     * Legacy {@code §}-coded text: {@code §0-§f} colours (which reset decorations), {@code §k-§o}
     * decorations, {@code §r} reset, and Spigot's {@code §x§R§R§G§G§B§B} hex form.
     */
    public static ItemText legacy(String text) {
        if (text == null || text.isEmpty()) {
            return ItemText.EMPTY;
        }
        List<ItemText.Span> out = new ArrayList<ItemText.Span>();
        StringBuilder run = new StringBuilder();
        Style style = Style.NONE;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '§' && i + 1 < text.length()) {
                char code = Character.toLowerCase(text.charAt(i + 1));
                Style next = null;
                int consumed = 2;
                if (code == 'x' && i + 14 <= text.length()) {
                    StringBuilder hex = new StringBuilder();
                    boolean ok = true;
                    for (int k = 0; k < 6; k++) {
                        int at = i + 2 + k * 2;
                        if (text.charAt(at) != '§'
                                || Character.digit(text.charAt(at + 1), 16) < 0) {
                            ok = false;
                            break;
                        }
                        hex.append(text.charAt(at + 1));
                    }
                    if (ok) {
                        next = Style.NONE.withColor(Integer.parseInt(hex.toString(), 16));
                        consumed = 14;
                    }
                }
                if (next == null) {
                    Integer color = legacyColor(code);
                    if (color != null) {
                        next = Style.NONE.withColor(color);
                    } else if (code == 'r') {
                        next = Style.NONE;
                    } else if (code == 'k') {
                        next = style.with(null, null, null, null, Boolean.TRUE);
                    } else if (code == 'l') {
                        next = style.with(Boolean.TRUE, null, null, null, null);
                    } else if (code == 'm') {
                        next = style.with(null, null, null, Boolean.TRUE, null);
                    } else if (code == 'n') {
                        next = style.with(null, null, Boolean.TRUE, null, null);
                    } else if (code == 'o') {
                        next = style.with(null, Boolean.TRUE, null, null, null);
                    }
                }
                if (next != null) {
                    if (run.length() > 0) {
                        out.add(style.span(run.toString()));
                        run.setLength(0);
                    }
                    style = next;
                    i += consumed;
                    continue;
                }
            }
            run.append(c);
            i++;
        }
        if (run.length() > 0) {
            out.add(style.span(run.toString()));
        }
        return ItemText.of(capLength(out));
    }

    /**
     * The parsed tree if {@code text} is shaped like a JSON component, else {@code null}.
     *
     * <p>Shape first, then parse: a 1.21.5 name is the text itself, and an anvil name like
     * {@code [Epic] Sword} must not be read as a list. So only a string that both starts and ends
     * like a JSON value is tried, and a parse failure means "it was text after all".
     */
    private static Object jsonShaped(String text) {
        String t = text.trim();
        if (t.length() < 2) {
            return null;
        }
        char first = t.charAt(0);
        char last = t.charAt(t.length() - 1);
        boolean shaped = (first == '{' && last == '}') || (first == '[' && last == ']')
                || (first == '"' && last == '"');
        if (!shaped) {
            return null;
        }
        try {
            Object parsed = Snbt.parse(t, 256 * 1024);
            if (parsed instanceof List && !looksLikeComponentList((List<?>) parsed)) {
                return null;
            }
            if (parsed instanceof Map && !looksLikeComponent((Map<?, ?>) parsed)) {
                return null;
            }
            return parsed;
        } catch (Snbt.SyntaxException notJson) {
            return null;
        }
    }

    private static boolean looksLikeComponent(Map<?, ?> map) {
        return map.containsKey("text") || map.containsKey("translate") || map.containsKey("extra")
                || map.containsKey("keybind") || map.containsKey("");
    }

    private static boolean looksLikeComponentList(List<?> list) {
        if (list.isEmpty()) {
            return false;
        }
        for (Object element : list) {
            if (element instanceof Map) {
                if (!looksLikeComponent((Map<?, ?>) element)) {
                    return false;
                }
            } else if (!(element instanceof String)) {
                return false;
            }
        }
        return true;
    }

    /**
     * What one {@link #read} may spend, shared by every node it visits, translation arguments
     * included.
     *
     * <p>Depth and span caps alone do not bound the work. A translation whose format repeats an
     * argument ({@code %1$s%1$s...}) nested a few levels deep multiplies at every level: a
     * 543-character custom name built that way expanded to gigabytes and took the chat thread down
     * with it. So every intermediate string is capped at {@link #MAX_LINE_CHARS}, and every read has
     * budgets of characters produced and nodes visited; once one is spent the rest of what it pays
     * for is dropped. There are two per read: one for the line itself and one shared by every
     * translation argument, so a wide fan-out of arguments cannot spend the line's share and leave
     * an empty name. A truncated name is a cosmetic fault; an exhausted heap is not.
     */
    static final class Budget {

        /** Nodes one read may visit. */
        static final int MAX_NODES = 512;

        /**
         * Characters one read may produce, across arguments, formats and output together. Far
         * beyond any real name or lore line, so it only ever stops an expansion.
         */
        static final int MAX_CHARS = 64 * MAX_LINE_CHARS;

        private int nodes = MAX_NODES;
        private int chars = MAX_CHARS;

        /** Takes one node; {@code false} once none are left. */
        boolean visit() {
            if (nodes <= 0) {
                return false;
            }
            nodes--;
            return true;
        }

        /** {@code text} cut to what is left of the character budget, which it then spends. */
        String spend(String text) {
            if (chars <= 0 || text.isEmpty()) {
                return "";
            }
            String kept = text.length() <= Math.min(chars, MAX_LINE_CHARS) ? text
                    : text.substring(0, Math.min(chars, MAX_LINE_CHARS));
            chars -= kept.length();
            return kept;
        }

        boolean spent() {
            return nodes <= 0 || chars <= 0;
        }
    }

    private static void flatten(Object node, Style inherited, List<ItemText.Span> out,
            ItemTranslations tr, int depth, Budget budget, Budget args) {
        if (node == null || depth > MAX_DEPTH || out.size() >= MAX_SPANS || !budget.visit()) {
            return;
        }
        if (node instanceof String) {
            add(out, inherited, budget.spend((String) node));
            return;
        }
        if (node instanceof Number || node instanceof Boolean) {
            add(out, inherited, budget.spend(String.valueOf(node)));
            return;
        }
        List<Object> list = Snbt.asList(node);
        if (list != null) {
            if (list.isEmpty()) {
                return;
            }
            // [a, b, c] is a with b and c as children: the rest inherit a's style, not each other's.
            Object head = list.get(0);
            flatten(head, inherited, out, tr, depth + 1, budget, args);
            Map<String, Object> headMap = Snbt.asMap(head);
            Style siblings = headMap == null ? inherited : inherited.merge(headMap);
            for (int i = 1; i < list.size() && !budget.spent(); i++) {
                flatten(list.get(i), siblings, out, tr, depth + 1, budget, args);
            }
            return;
        }
        Map<String, Object> map = Snbt.asMap(node);
        if (map == null) {
            return;
        }
        Style style = inherited.merge(map);
        String content = contentOf(map, tr, depth, budget, args);
        if (content != null) {
            add(out, style, budget.spend(content));
        }
        List<Object> extra = Snbt.asList(map.get("extra"));
        if (extra != null) {
            for (Object child : extra) {
                if (budget.spent()) {
                    break;
                }
                flatten(child, style, out, tr, depth + 1, budget, args);
            }
        }
    }

    private static void add(List<ItemText.Span> out, Style style, String text) {
        if (!text.isEmpty() && out.size() < MAX_SPANS) {
            out.add(style.span(text));
        }
    }

    private static String contentOf(Map<String, Object> map, ItemTranslations tr, int depth,
            Budget budget, Budget args) {
        Object text = map.get("text");
        if (text != null) {
            return cap(String.valueOf(text));
        }
        Object bare = map.get("");
        if (bare instanceof String) {
            // NBT's wrapper for a heterogeneous list element: {"": "text"}.
            return cap((String) bare);
        }
        String key = Snbt.asString(map.get("translate"));
        if (key != null) {
            String format = tr.translate(key);
            if (format == null) {
                String fallback = Snbt.asString(map.get("fallback"));
                format = fallback != null ? fallback : key;
            }
            List<Object> with = Snbt.asList(map.get("with"));
            List<String> values = new ArrayList<String>();
            if (with != null) {
                for (Object arg : with) {
                    if (args.spent() || values.size() >= 16) {
                        break;
                    }
                    // Arguments spend their own pool, never the line's: a fan-out of wide
                    // arguments can exhaust it, and the text around them still renders.
                    List<ItemText.Span> argSpans = new ArrayList<ItemText.Span>();
                    flatten(arg, Style.NONE, argSpans, tr, depth + 1, args, args);
                    values.add(cap(ItemText.of(argSpans).plain()));
                }
            }
            return format(cap(format), values);
        }
        String keybind = Snbt.asString(map.get("keybind"));
        if (keybind != null) {
            String translated = tr.translate(keybind);
            return cap(translated != null ? translated : keybind);
        }
        return null;
    }

    /**
     * Java-style {@code %s} and {@code %1$s} substitution, as Minecraft's language files use. The
     * result never exceeds {@link #MAX_LINE_CHARS}: appending stops at the cap, so a format that
     * repeats an argument cannot multiply it.
     */
    static String format(String format, List<String> args) {
        if (format.indexOf('%') < 0) {
            return cap(format);
        }
        StringBuilder out = new StringBuilder();
        int next = 0;
        int i = 0;
        while (i < format.length() && out.length() < MAX_LINE_CHARS) {
            char c = format.charAt(i);
            if (c != '%' || i + 1 >= format.length()) {
                out.append(c);
                i++;
                continue;
            }
            char d = format.charAt(i + 1);
            if (d == '%') {
                out.append('%');
                i += 2;
                continue;
            }
            if (d == 's') {
                append(out, next < args.size() ? args.get(next) : "");
                next++;
                i += 2;
                continue;
            }
            int j = i + 1;
            while (j < format.length() && j - i <= 3 && Character.isDigit(format.charAt(j))) {
                j++;
            }
            if (j > i + 1 && j + 1 < format.length() && format.charAt(j) == '$'
                    && format.charAt(j + 1) == 's') {
                int index = Integer.parseInt(format.substring(i + 1, j)) - 1;
                append(out, index >= 0 && index < args.size() ? args.get(index) : "");
                i = j + 2;
                continue;
            }
            out.append(c);
            i++;
        }
        return cap(out.toString());
    }

    /** Appends at most what keeps {@code out} within {@link #MAX_LINE_CHARS}. */
    private static void append(StringBuilder out, String text) {
        int room = MAX_LINE_CHARS - out.length();
        if (room > 0) {
            out.append(text, 0, Math.min(room, text.length()));
        }
    }

    private static String cap(String text) {
        return text.length() <= MAX_LINE_CHARS ? text : text.substring(0, MAX_LINE_CHARS);
    }

    private static List<ItemText.Span> capLength(List<ItemText.Span> spans) {
        int total = 0;
        List<ItemText.Span> out = new ArrayList<ItemText.Span>(spans.size());
        for (ItemText.Span span : spans) {
            if (out.size() >= MAX_SPANS || total >= MAX_LINE_CHARS) {
                break;
            }
            String text = span.text();
            if (total + text.length() > MAX_LINE_CHARS) {
                text = text.substring(0, MAX_LINE_CHARS - total);
                span = new ItemText.Span(text, span.color(), span.bold(), span.italic(),
                        span.underlined(), span.strikethrough(), span.obfuscated());
            }
            total += text.length();
            out.add(span);
        }
        return out.isEmpty() ? Collections.<ItemText.Span>emptyList() : out;
    }

    /** A resolved, inheritable style. Immutable. */
    private static final class Style {

        static final Style NONE = new Style(null, null, null, null, null, null);

        final Integer color;
        final Boolean bold;
        final Boolean italic;
        final Boolean underlined;
        final Boolean strikethrough;
        final Boolean obfuscated;

        Style(Integer color, Boolean bold, Boolean italic, Boolean underlined,
                Boolean strikethrough, Boolean obfuscated) {
            this.color = color;
            this.bold = bold;
            this.italic = italic;
            this.underlined = underlined;
            this.strikethrough = strikethrough;
            this.obfuscated = obfuscated;
        }

        Style withColor(Integer value) {
            return new Style(value, bold, italic, underlined, strikethrough, obfuscated);
        }

        /** Overrides only the decorations given as non-null. */
        Style with(Boolean b, Boolean i, Boolean u, Boolean s, Boolean o) {
            return new Style(color, b != null ? b : bold, i != null ? i : italic,
                    u != null ? u : underlined, s != null ? s : strikethrough,
                    o != null ? o : obfuscated);
        }

        /** This style with whatever {@code component} specifies laid over it. */
        Style merge(Map<String, Object> component) {
            Integer c = color;
            Object spelled = component.get("color");
            if (spelled instanceof String) {
                Integer parsed = color((String) spelled);
                if (parsed != null) {
                    c = parsed;
                }
            }
            return new Style(c,
                    pick(component.get("bold"), bold),
                    pick(component.get("italic"), italic),
                    pick(component.get("underlined"), underlined),
                    pick(component.get("strikethrough"), strikethrough),
                    pick(component.get("obfuscated"), obfuscated));
        }

        private static Boolean pick(Object value, Boolean fallback) {
            Boolean parsed = Snbt.asBoolean(value, null);
            return parsed != null ? parsed : fallback;
        }

        ItemText.Span span(String text) {
            return new ItemText.Span(text, color, bold, italic, underlined, strikethrough,
                    obfuscated);
        }
    }
}
