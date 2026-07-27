package com.heimdall.core.log;

import java.util.function.Supplier;

/**
 * Everything a {@link HeimdallLogger} implementation does that is not platform-specific.
 *
 * <p>Subclasses implement one method, {@link #write(LogLevel, String, Throwable)}. The debug
 * toggle, the supplier gating and the level routing are settled here so that three platform
 * loggers cannot end up with three different answers to "is debug on?".
 */
public abstract class AbstractHeimdallLogger implements HeimdallLogger {

    /**
     * Written by whoever owns configuration (main thread, command thread) and read from the IO and
     * scheduler threads, so it must be volatile — a debug flag that never becomes visible to the
     * worker actually doing the interesting work is worse than no flag.
     */
    private volatile boolean debugEnabled;

    protected AbstractHeimdallLogger() {
        this(false);
    }

    protected AbstractHeimdallLogger(boolean debugEnabled) {
        this.debugEnabled = debugEnabled;
    }

    /**
     * Emits one record. Called only for levels that are actually enabled.
     *
     * @param level the severity
     * @param message the already-rendered message, never {@code null}
     * @param throwable the cause, or {@code null}
     */
    protected abstract void write(LogLevel level, String message, Throwable throwable);

    @Override
    public final void info(String message) {
        write(LogLevel.INFO, String.valueOf(message), null);
    }

    @Override
    public final void warn(String message) {
        write(LogLevel.WARN, String.valueOf(message), null);
    }

    @Override
    public final void severe(String message) {
        write(LogLevel.SEVERE, String.valueOf(message), null);
    }

    @Override
    public final void error(String message, Throwable throwable) {
        write(LogLevel.SEVERE, String.valueOf(message), throwable);
    }

    @Override
    public final void debug(String message) {
        if (debugEnabled) {
            write(LogLevel.DEBUG, String.valueOf(message), null);
        }
    }

    @Override
    public final void debug(Supplier<String> message) {
        if (!debugEnabled) {
            return;
        }
        String rendered;
        try {
            rendered = message == null ? "null" : message.get();
        } catch (RuntimeException e) {
            // A broken debug message must never take down the caller — which, on the login path,
            // would mean a failed login for a player whose only crime was that debug was on.
            write(LogLevel.WARN, "debug message supplier threw", e);
            return;
        }
        write(LogLevel.DEBUG, String.valueOf(rendered), null);
    }

    @Override
    public final boolean isDebugEnabled() {
        return debugEnabled;
    }

    @Override
    public final void setDebugEnabled(boolean debugEnabled) {
        this.debugEnabled = debugEnabled;
    }
}
