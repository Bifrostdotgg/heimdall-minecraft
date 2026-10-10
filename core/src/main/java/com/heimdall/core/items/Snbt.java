package com.heimdall.core.items;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small, Java 8, dependency-free reader for Minecraft's stringified NBT (SNBT).
 *
 * <h2>Why this exists at all</h2>
 *
 * <p>The item hover that ChatControl writes into a relayed line carries every item component as an
 * SNBT value ({@code enchantments:'{"minecraft:efficiency":5}'}, {@code lore:'[{text:"..."}]'}). The
 * Adventure the plugin shades is 4.13.1, which predates data components and cannot read any of it,
 * and the server's own parser is a {@code net.minecraft} type core is not allowed to see. So the
 * grammar is read here, and only as far as an item tooltip needs it.
 *
 * <h2>What a value turns into</h2>
 *
 * <ul>
 *   <li>a compound: an unmodifiable {@link Map} of {@code String} to value, in source order;
 *   <li>a list: an unmodifiable {@link List} of values;
 *   <li>a typed array ({@code [B;...]}, {@code [I;...]}, {@code [L;...]}): {@code byte[]},
 *       {@code int[]} or {@code long[]};
 *   <li>a number: {@link Byte}, {@link Short}, {@link Integer}, {@link Long}, {@link Float} or
 *       {@link Double}, by suffix ({@code b s l f d}) or, without one, {@code Integer} for a whole
 *       number and {@code Double} for anything with a point or an exponent;
 *   <li>{@code true} / {@code false}: {@link Boolean}. Consumers treat a {@code Boolean} and a byte
 *       the same way ({@link #asBoolean}), because {@code 1b} and {@code true} mean the same thing;
 *   <li>everything else: a {@link String}, quoted or not.
 * </ul>
 *
 * <h2>JSON is accepted too, deliberately</h2>
 *
 * <p>For every document Heimdall reads with this class (pre-1.21.5 text components, which are JSON
 * strings inside the SNBT; resource-pack models; language files) JSON's grammar is a subset of the
 * above: quoted keys, quoted strings with JSON's escapes, numbers, {@code true}/{@code false}, and a
 * {@code null} that arrives as the string {@code "null"}. One parser for both keeps one set of
 * bounds and one set of tests.
 *
 * <h2>Bounded</h2>
 *
 * <p>Input length and nesting depth are capped. The input is player-influenced (lore, names) and, on
 * the pack side, operator files of any size; a pathological value must fail fast with a
 * {@link SyntaxException}, never recurse the stack away or allocate without limit.
 *
 * <p>Stateless and thread-safe: every call builds its own cursor.
 */
public final class Snbt {

    /** The longest input {@link #parse(String)} will look at. Generous: a language file is ~500 KB. */
    public static final int DEFAULT_MAX_LENGTH = 4 * 1024 * 1024;

    /** Deeper nesting than this is refused rather than recursed into. */
    public static final int MAX_DEPTH = 96;

    private Snbt() {
    }

    /** Thrown for anything that is not a well-formed value. The message never quotes the input. */
    public static final class SyntaxException extends Exception {

        private static final long serialVersionUID = 1L;

        SyntaxException(String message, int position) {
            super(message + " at offset " + position);
        }
    }

    /** Parses one complete value. Trailing whitespace is allowed, trailing anything else is not. */
    public static Object parse(String text) throws SyntaxException {
        return parse(text, DEFAULT_MAX_LENGTH);
    }

    /** {@link #parse(String)} with an explicit length cap. */
    public static Object parse(String text, int maxLength) throws SyntaxException {
        if (text == null) {
            throw new SyntaxException("no input", 0);
        }
        if (text.length() > maxLength) {
            throw new SyntaxException("input longer than " + maxLength + " characters", 0);
        }
        Cursor cursor = new Cursor(text);
        Object value = cursor.value(0);
        cursor.skipWhitespace();
        if (cursor.pos != text.length()) {
            throw new SyntaxException("unexpected trailing input", cursor.pos);
        }
        return value;
    }

    // ── Typed views, shared by every consumer ────────────────────────────────

    /** {@code value} as a compound, or {@code null} if it is not one. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    /** {@code value} as a list, or {@code null} if it is not one. Typed arrays are not lists. */
    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object value) {
        return value instanceof List ? (List<Object>) value : null;
    }

    /** {@code value} as a string, or {@code null} if it is not one. */
    public static String asString(Object value) {
        return value instanceof String ? (String) value : null;
    }

    /** A whole number of any width, or {@code fallback}; a fractional value is truncated. */
    public static Integer asInt(Object value, Integer fallback) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof Boolean) {
            return ((Boolean) value) ? 1 : 0;
        }
        return fallback;
    }

    /** Any number as a double, or {@code fallback}. */
    public static Double asDouble(Object value, Double fallback) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return fallback;
    }

    /**
     * {@code true}/{@code false}, a byte (or any number: non-zero is true), or the strings
     * {@code "true"}/{@code "false"}; anything else is {@code fallback}.
     */
    public static Boolean asBoolean(Object value, Boolean fallback) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue() != 0.0;
        }
        if ("true".equals(value)) {
            return Boolean.TRUE;
        }
        if ("false".equals(value)) {
            return Boolean.FALSE;
        }
        return fallback;
    }

    // ── The parser ───────────────────────────────────────────────────────────

    private static final class Cursor {

        private final String s;
        private int pos;

        Cursor(String s) {
            this.s = s;
        }

        void skipWhitespace() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        Object value(int depth) throws SyntaxException {
            if (depth > MAX_DEPTH) {
                throw new SyntaxException("nested deeper than " + MAX_DEPTH, pos);
            }
            skipWhitespace();
            if (pos >= s.length()) {
                throw new SyntaxException("expected a value", pos);
            }
            char c = s.charAt(pos);
            if (c == '{') {
                return compound(depth);
            }
            if (c == '[') {
                if (pos + 2 < s.length() && s.charAt(pos + 2) == ';') {
                    char kind = s.charAt(pos + 1);
                    if (kind == 'B' || kind == 'I' || kind == 'L') {
                        return typedArray(kind);
                    }
                }
                return list(depth);
            }
            if (c == '"' || c == '\'') {
                return quoted();
            }
            String token = unquoted();
            if (token.isEmpty()) {
                throw new SyntaxException("expected a value", pos);
            }
            return literal(token);
        }

        private Map<String, Object> compound(int depth) throws SyntaxException {
            pos++; // '{'
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return Collections.unmodifiableMap(out);
            }
            while (true) {
                skipWhitespace();
                String key;
                char c = peek();
                if (c == '"' || c == '\'') {
                    key = quoted();
                } else {
                    key = unquoted();
                    if (key.isEmpty()) {
                        throw new SyntaxException("expected a key", pos);
                    }
                }
                skipWhitespace();
                expect(':');
                out.put(key, value(depth + 1));
                skipWhitespace();
                char next = peek();
                if (next == ',') {
                    pos++;
                    skipWhitespace();
                    // A trailing comma is legal in 1.21.5+ SNBT, and harmless to accept everywhere.
                    if (peek() == '}') {
                        pos++;
                        break;
                    }
                    continue;
                }
                if (next == '}') {
                    pos++;
                    break;
                }
                throw new SyntaxException("expected ',' or '}'", pos);
            }
            return Collections.unmodifiableMap(out);
        }

        private List<Object> list(int depth) throws SyntaxException {
            pos++; // '['
            List<Object> out = new ArrayList<Object>();
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return Collections.unmodifiableList(out);
            }
            while (true) {
                out.add(value(depth + 1));
                skipWhitespace();
                char next = peek();
                if (next == ',') {
                    pos++;
                    skipWhitespace();
                    if (peek() == ']') {
                        pos++;
                        break;
                    }
                    continue;
                }
                if (next == ']') {
                    pos++;
                    break;
                }
                throw new SyntaxException("expected ',' or ']'", pos);
            }
            return Collections.unmodifiableList(out);
        }

        private Object typedArray(char kind) throws SyntaxException {
            pos += 3; // "[B;"
            List<Number> numbers = new ArrayList<Number>();
            skipWhitespace();
            if (peek() == ']') {
                pos++;
            } else {
                while (true) {
                    skipWhitespace();
                    Object element = literal(unquoted());
                    if (!(element instanceof Number) || element instanceof Float
                            || element instanceof Double) {
                        throw new SyntaxException("a typed array holds whole numbers only", pos);
                    }
                    numbers.add((Number) element);
                    skipWhitespace();
                    char next = peek();
                    if (next == ',') {
                        pos++;
                        continue;
                    }
                    if (next == ']') {
                        pos++;
                        break;
                    }
                    throw new SyntaxException("expected ',' or ']'", pos);
                }
            }
            if (kind == 'B') {
                byte[] out = new byte[numbers.size()];
                for (int i = 0; i < out.length; i++) {
                    out[i] = numbers.get(i).byteValue();
                }
                return out;
            }
            if (kind == 'I') {
                int[] out = new int[numbers.size()];
                for (int i = 0; i < out.length; i++) {
                    out[i] = numbers.get(i).intValue();
                }
                return out;
            }
            long[] out = new long[numbers.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = numbers.get(i).longValue();
            }
            return out;
        }

        private String quoted() throws SyntaxException {
            char quote = s.charAt(pos++);
            StringBuilder out = new StringBuilder();
            while (pos < s.length()) {
                char c = s.charAt(pos++);
                if (c == quote) {
                    return out.toString();
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                if (pos >= s.length()) {
                    break;
                }
                char e = s.charAt(pos++);
                switch (e) {
                    case 'n':
                        out.append('\n');
                        break;
                    case 't':
                        out.append('\t');
                        break;
                    case 'r':
                        out.append('\r');
                        break;
                    case 'b':
                        out.append('\b');
                        break;
                    case 'f':
                        out.append('\f');
                        break;
                    case 's':
                        out.append(' ');
                        break;
                    case 'u':
                        out.append((char) hex(4));
                        break;
                    case 'x':
                        out.append((char) hex(2));
                        break;
                    default:
                        // \\ \" \' \/ and anything unknown: the character itself. Lenient on purpose:
                        // an unknown escape in a lore line is not a reason to lose the whole item.
                        out.append(e);
                        break;
                }
            }
            throw new SyntaxException("unterminated string", pos);
        }

        private int hex(int digits) throws SyntaxException {
            if (pos + digits > s.length()) {
                throw new SyntaxException("truncated escape", pos);
            }
            try {
                int value = Integer.parseInt(s.substring(pos, pos + digits), 16);
                pos += digits;
                return value;
            } catch (NumberFormatException bad) {
                throw new SyntaxException("bad escape", pos);
            }
        }

        private String unquoted() {
            int start = pos;
            while (pos < s.length() && isUnquotedChar(s.charAt(pos))) {
                pos++;
            }
            return s.substring(start, pos);
        }

        private char peek() throws SyntaxException {
            if (pos >= s.length()) {
                throw new SyntaxException("unexpected end of input", pos);
            }
            return s.charAt(pos);
        }

        private void expect(char c) throws SyntaxException {
            if (peek() != c) {
                throw new SyntaxException("expected '" + c + "'", pos);
            }
            pos++;
        }
    }

    static boolean isUnquotedChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || c == '_' || c == '-' || c == '.' || c == '+';
    }

    /** Interprets an unquoted token: a boolean, a number by its suffix, or else the string itself. */
    static Object literal(String token) {
        if ("true".equals(token)) {
            return Boolean.TRUE;
        }
        if ("false".equals(token)) {
            return Boolean.FALSE;
        }
        if (token.isEmpty()) {
            return token;
        }
        char first = token.charAt(0);
        if (!(first == '-' || first == '+' || first == '.' || (first >= '0' && first <= '9'))) {
            return token;
        }
        char last = token.charAt(token.length() - 1);
        String body = token;
        char suffix = 0;
        if (Character.isLetter(last)) {
            suffix = Character.toLowerCase(last);
            body = token.substring(0, token.length() - 1);
        }
        try {
            if (isWhole(body)) {
                switch (suffix) {
                    case 'b':
                        return Byte.valueOf((byte) Long.parseLong(stripPlus(body)));
                    case 's':
                        return Short.valueOf((short) Long.parseLong(stripPlus(body)));
                    case 'l':
                        return Long.valueOf(Long.parseLong(stripPlus(body)));
                    case 'f':
                        return Float.valueOf(Float.parseFloat(body));
                    case 'd':
                        return Double.valueOf(Double.parseDouble(body));
                    case 0:
                        long wide = Long.parseLong(stripPlus(body));
                        if (wide >= Integer.MIN_VALUE && wide <= Integer.MAX_VALUE) {
                            return Integer.valueOf((int) wide);
                        }
                        return Long.valueOf(wide);
                    default:
                        return token;
                }
            }
            if (isDecimal(body)) {
                if (suffix == 'f') {
                    return Float.valueOf(Float.parseFloat(body));
                }
                if (suffix == 'd' || suffix == 0) {
                    return Double.valueOf(Double.parseDouble(body));
                }
                if (suffix == 'e') {
                    // "1e" is not a number; fall through to the token.
                    return token;
                }
            }
        } catch (NumberFormatException notANumber) {
            return token;
        }
        return token;
    }

    private static String stripPlus(String body) {
        return body.startsWith("+") ? body.substring(1) : body;
    }

    private static boolean isWhole(String body) {
        int i = 0;
        if (i < body.length() && (body.charAt(i) == '-' || body.charAt(i) == '+')) {
            i++;
        }
        if (i >= body.length()) {
            return false;
        }
        for (; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static boolean isDecimal(String body) {
        int i = 0;
        if (i < body.length() && (body.charAt(i) == '-' || body.charAt(i) == '+')) {
            i++;
        }
        boolean digits = false;
        boolean point = false;
        boolean exponent = false;
        for (; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c >= '0' && c <= '9') {
                digits = true;
            } else if (c == '.' && !point && !exponent) {
                point = true;
            } else if ((c == 'e' || c == 'E') && digits && !exponent) {
                exponent = true;
                digits = false;
                if (i + 1 < body.length()
                        && (body.charAt(i + 1) == '-' || body.charAt(i + 1) == '+')) {
                    i++;
                }
            } else {
                return false;
            }
        }
        return digits;
    }
}
