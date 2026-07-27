package com.heimdall.core.util;

/**
 * The handful of string predicates core needs, written out because the source level is Java 8.
 *
 * <p>{@code String.isBlank()} arrived in Java 11 and {@code String.strip()} in 11 as well; core
 * compiles at {@code --release 8} for Spigot 1.8.8, so neither is available. Re-deriving them at
 * each call site is how "is this null, empty, or just spaces?" ends up meaning three different
 * things in three different files.
 */
public final class Strings {

    private Strings() {
    }

    /** Whether the value is {@code null}, empty, or entirely whitespace. */
    public static boolean isBlank(String value) {
        if (value == null) {
            return true;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isWhitespace(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** The inverse of {@link #isBlank(String)}. */
    public static boolean isNotBlank(String value) {
        return !isBlank(value);
    }

    /** The trimmed value, or {@code ""} when it is {@code null}. Never returns {@code null}. */
    public static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    /** The trimmed value, or {@code null} when it is blank. */
    public static String trimToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }
}
