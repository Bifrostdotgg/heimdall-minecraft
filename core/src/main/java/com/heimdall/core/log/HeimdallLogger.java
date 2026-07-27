package com.heimdall.core.log;

import java.util.function.Supplier;

/**
 * The only logging surface core, the API module and the feature modules ever see.
 *
 * <p><strong>The debug toggle lives on the logger, and it is a field.</strong> v2 asked its config
 * provider whether debug was enabled <em>on every debug call</em> — {@code
 * config.getBoolean("logging.debug", false)} — and on Bukkit that re-reads the backing
 * configuration section, several times per login attempt, on the login thread. Here the flag is a
 * single volatile boolean set once by whoever owns configuration, so a debug call on the hot path
 * costs one field read.
 *
 * <p>The {@link Supplier} overload exists for the other half of that cost: string concatenation
 * happens at the call site whether or not anyone will read the result. Use it whenever the message
 * is built rather than constant.
 *
 * <p>Implementations must be safe to call from any thread.
 */
public interface HeimdallLogger {

    /** Logs an ordinary operational message. */
    void info(String message);

    /** Logs a recoverable problem. */
    void warn(String message);

    /** Logs a failure that degrades or disables a feature. */
    void severe(String message);

    /**
     * Logs a failure with its cause.
     *
     * <p>Emitted at {@link LogLevel#SEVERE}. The throwable is passed to the platform logger rather
     * than flattened into the message, so the server's own log format decides how much of the
     * stack trace to render.
     */
    void error(String message, Throwable throwable);

    /** Logs diagnostic detail, if {@link #isDebugEnabled()}. */
    void debug(String message);

    /**
     * Logs diagnostic detail, invoking {@code message} only if {@link #isDebugEnabled()}.
     *
     * <p>Prefer this whenever the message is concatenated or formatted.
     */
    void debug(Supplier<String> message);

    /** Whether {@link LogLevel#DEBUG} messages are currently emitted. */
    boolean isDebugEnabled();

    /**
     * Turns debug logging on or off.
     *
     * <p>Takes effect immediately for every thread — the flag is volatile — so a {@code /hd
     * debug} toggle or a pushed config change does not need to reach through anything else.
     */
    void setDebugEnabled(boolean debugEnabled);
}
