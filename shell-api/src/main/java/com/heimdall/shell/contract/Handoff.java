package com.heimdall.shell.contract;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Validates and deep-copies state one core generation leaves for the next.
 *
 * <h2>Plain JDK types only</h2>
 *
 * <p>The map is held by the shell while one core stops and the next starts, and it must not keep
 * the outgoing core's classloader alive or hand the incoming core an object whose class it cannot
 * see. So every value must be one of: {@code String}, {@code Boolean}, {@code Integer},
 * {@code Long}, {@code Double}, or a {@code List} or a {@code Map} with {@code String} keys of the
 * same, nested. {@code null} values are allowed. Anything else is refused with the path to the
 * offending value, at the moment it is handed over, so a core that breaks the rule finds out in its
 * own stop rather than in its successor's start.
 *
 * <p>Copies are taken on the way in, so the outgoing core cannot change the state after handing it
 * over, and the result is unmodifiable all the way down.
 *
 * <p>State that already lives on disk (the whitelist mirror, the punishment store) does not belong
 * here: the next generation reloads it. This is for the little that is only in memory.
 */
public final class Handoff {

    /** Deepest nesting accepted; a bound against a cycle, not a format limit. */
    static final int MAX_DEPTH = 16;

    private Handoff() {
    }

    /**
     * An unmodifiable deep copy of {@code state}.
     *
     * @throws IllegalArgumentException naming the first value that is not a plain JDK type
     */
    public static Map<String, Object> copyOf(Map<String, ?> state) {
        if (state == null) {
            return Collections.emptyMap();
        }
        return copyMap(state, "", 0);
    }

    private static Map<String, Object> copyMap(Map<?, ?> source, String path, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("handoff state is nested deeper than " + MAX_DEPTH
                    + " at '" + path + "'");
        }
        Map<String, Object> copy = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String)) {
                throw new IllegalArgumentException("handoff map keys must be strings, found "
                        + typeOf(entry.getKey()) + " at '" + path + "'");
            }
            String key = (String) entry.getKey();
            String child = path.isEmpty() ? key : path + "." + key;
            copy.put(key, copyValue(entry.getValue(), child, depth + 1));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Object copyValue(Object value, String path, int depth) {
        if (value == null || value instanceof String || value instanceof Boolean
                || value instanceof Integer || value instanceof Long || value instanceof Double) {
            return value;
        }
        if (value instanceof Map) {
            return copyMap((Map<?, ?>) value, path, depth);
        }
        if (value instanceof List) {
            if (depth > MAX_DEPTH) {
                throw new IllegalArgumentException("handoff state is nested deeper than "
                        + MAX_DEPTH + " at '" + path + "'");
            }
            List<Object> copy = new ArrayList<Object>();
            int index = 0;
            for (Object element : (List<?>) value) {
                copy.add(copyValue(element, path + "[" + index + "]", depth + 1));
                index++;
            }
            return Collections.unmodifiableList(copy);
        }
        throw new IllegalArgumentException("handoff state must be plain JDK values, found "
                + typeOf(value) + " at '" + path + "'");
    }

    private static String typeOf(Object value) {
        return value == null ? "null" : value.getClass().getName();
    }
}
