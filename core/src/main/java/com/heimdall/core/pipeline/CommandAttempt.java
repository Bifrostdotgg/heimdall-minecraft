package com.heimdall.core.pipeline;

import com.heimdall.core.util.Strings;
import java.util.Locale;
import java.util.UUID;

/**
 * One command a player typed, in flight for the duration of one dispatch.
 *
 * <p>Immutable. Exists so mute blocked-command lists (later) can cancel {@code /msg} without
 * each module inventing its own preprocess listener. The pipeline does not store these.
 */
public final class CommandAttempt {

    private final UUID senderUuid;
    private final String senderName;
    private final String raw;
    private final String label;

    private CommandAttempt(UUID senderUuid, String senderName, String raw) {
        if (senderUuid == null) {
            throw new IllegalArgumentException("senderUuid is required");
        }
        this.senderUuid = senderUuid;
        this.senderName = Strings.trimToEmpty(senderName);
        this.raw = raw == null ? "" : raw;
        this.label = commandLabel(this.raw);
    }

    public static CommandAttempt of(UUID senderUuid, String senderName, String raw) {
        return new CommandAttempt(senderUuid, senderName, raw);
    }

    /**
     * The first token, slash stripped, namespace stripped, lower-cased.
     *
     * <p>{@code /msg}, {@code /minecraft:msg} and {@code /essentials:msg} all become {@code msg}.
     * {@code /me} does not become a prefix of {@code /menu}.
     */
    public static String commandLabel(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.startsWith("/")) {
            text = text.substring(1);
        }
        int space = indexOfWhitespace(text);
        if (space >= 0) {
            text = text.substring(0, space);
        }
        int colon = text.lastIndexOf(':');
        if (colon >= 0 && colon < text.length() - 1) {
            text = text.substring(colon + 1);
        }
        return text.toLowerCase(Locale.ROOT);
    }

    private static int indexOfWhitespace(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isWhitespace(text.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    public UUID senderUuid() {
        return senderUuid;
    }

    public String senderName() {
        return senderName;
    }

    /** The line as typed, including the leading slash when the platform supplied one. */
    public String raw() {
        return raw;
    }

    /** See {@link #commandLabel(String)}. */
    public String label() {
        return label;
    }

    @Override
    public String toString() {
        return "CommandAttempt{sender='" + senderName + "', label='" + label + "'}";
    }
}
