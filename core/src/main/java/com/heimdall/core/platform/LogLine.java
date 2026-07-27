package com.heimdall.core.platform;

import com.heimdall.core.util.Strings;

/**
 * One line the server printed to its console.
 *
 * <p>Immutable, three fields, and no reference to whatever logging framework produced it. That last
 * part is the point: the tap is a Log4j appender on both supported platforms, and a
 * {@code LogEvent} escaping into core would put an {@code org.apache.logging} type in the signature
 * of everything that touches the console feed — including the module that ships it to the dashboard,
 * which must stay platform-free.
 *
 * <p>{@link #message()} arrives already stripped of ANSI colour codes by the platform tap. Consoles
 * emit them, the dashboard renders them as mojibake, and stripping at the source means every
 * consumer gets clean text rather than each one remembering to.
 */
public final class LogLine {

    private final long timestampMs;
    private final String level;
    private final String message;

    /**
     * @param timestampMs when the line was emitted, epoch millis
     * @param level the level's name, e.g. {@code INFO}; {@code null} becomes {@code ""}
     * @param message the text, ANSI-stripped; {@code null} becomes {@code ""}
     */
    public LogLine(long timestampMs, String level, String message) {
        this.timestampMs = timestampMs;
        this.level = Strings.trimToEmpty(level);
        this.message = message == null ? "" : message;
    }

    /** When the line was emitted, in epoch millis. */
    public long timestampMs() {
        return timestampMs;
    }

    /** The level's name, e.g. {@code INFO}, {@code WARN}, {@code ERROR}. */
    public String level() {
        return level;
    }

    /** The text, with ANSI escape sequences already removed. */
    public String message() {
        return message;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof LogLine)) {
            return false;
        }
        LogLine that = (LogLine) other;
        return timestampMs == that.timestampMs
                && level.equals(that.level)
                && message.equals(that.message);
    }

    @Override
    public int hashCode() {
        int result = (int) (timestampMs ^ (timestampMs >>> 32));
        result = 31 * result + level.hashCode();
        result = 31 * result + message.hashCode();
        return result;
    }

    /**
     * Renders the level and the length, never the text.
     *
     * <p>A console line can carry anything the server printed, including a token somebody pasted
     * into chat. {@code toString()} ends up in debug logs and exception messages, so it deliberately
     * cannot be the thing that copies one there.
     */
    @Override
    public String toString() {
        return "LogLine{level='" + level + "', length=" + message.length() + "}";
    }
}
