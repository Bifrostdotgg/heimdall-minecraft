package com.heimdall.core.log;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A {@link HeimdallLogger} backed by {@code java.util.logging}.
 *
 * <p>JUL is in the JDK, so this is the one implementation guaranteed to work everywhere —
 * including in tests, in the setup tooling, and on a platform whose own logger is not available
 * yet. Platform modules replace it with a wrapper around the server's native logger, which is what
 * gets the plugin name into the console prefix.
 *
 * <p>{@link LogLevel#DEBUG} maps to {@code INFO} with a {@code [DEBUG]} marker rather than to
 * {@code FINE}: server consoles ship with JUL's default level, so {@code FINE} would be swallowed
 * and the debug toggle would silently do nothing. Gating is Heimdall's job (see {@link
 * AbstractHeimdallLogger}), so by the time a record reaches here somebody has already asked for it.
 */
public final class JulLogger extends AbstractHeimdallLogger {

    private final Logger delegate;

    /** Wraps an existing JUL logger. */
    public JulLogger(Logger delegate) {
        this(delegate, false);
    }

    /** Wraps an existing JUL logger, with debug logging initially on or off. */
    public JulLogger(Logger delegate, boolean debugEnabled) {
        super(debugEnabled);
        if (delegate == null) {
            throw new IllegalArgumentException("delegate logger is required");
        }
        this.delegate = delegate;
    }

    /** A logger named {@code name}, resolved through {@link Logger#getLogger(String)}. */
    public static JulLogger named(String name) {
        return new JulLogger(Logger.getLogger(name));
    }

    @Override
    protected void write(LogLevel level, String message, Throwable throwable) {
        switch (level) {
            case DEBUG:
                delegate.log(Level.INFO, "[DEBUG] " + message, throwable);
                break;
            case WARN:
                delegate.log(Level.WARNING, message, throwable);
                break;
            case SEVERE:
                delegate.log(Level.SEVERE, message, throwable);
                break;
            case INFO:
            default:
                delegate.log(Level.INFO, message, throwable);
                break;
        }
    }
}
