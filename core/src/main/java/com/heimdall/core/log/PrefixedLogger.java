package com.heimdall.core.log;

import java.util.function.Supplier;

/**
 * Tags every line with a prefix and passes it on.
 *
 * <p>What each module is handed, so a line in a server log says which module produced it. That is
 * not cosmetic: a server runs four or five Heimdall modules plus the tunnel, and an operator reading
 * "could not reach the bot" needs to know whether that came from the whitelist gate (players are
 * being kept out right now) or from the offense reporter (a punishment will be retried later).
 *
 * <p>Delegation rather than subclassing {@link AbstractHeimdallLogger}: the platform logger already
 * is one, and the debug flag has to stay <em>shared</em> with it. A subclass would get its own copy,
 * so {@code /hd debug} would toggle the plugin's logging and leave every module's silent.
 *
 * <p>Thread-safe if the delegate is, which the interface requires.
 */
public final class PrefixedLogger implements HeimdallLogger {

    private final HeimdallLogger delegate;
    private final String prefix;

    /**
     * @param prefix rendered as {@code [prefix] message}; blank means no prefix at all rather than
     *     an empty pair of brackets on every line
     */
    public PrefixedLogger(HeimdallLogger delegate, String prefix) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate is required");
        }
        this.delegate = delegate;
        this.prefix = prefix == null || prefix.trim().isEmpty() ? "" : "[" + prefix.trim() + "] ";
    }

    /** The delegate this wraps, so a nested prefix does not stack brackets forever. */
    public HeimdallLogger delegate() {
        return delegate;
    }

    @Override
    public void info(String message) {
        delegate.info(prefix + message);
    }

    @Override
    public void warn(String message) {
        delegate.warn(prefix + message);
    }

    @Override
    public void severe(String message) {
        delegate.severe(prefix + message);
    }

    @Override
    public void error(String message, Throwable throwable) {
        delegate.error(prefix + message, throwable);
    }

    @Override
    public void debug(String message) {
        delegate.debug(prefix + message);
    }

    @Override
    public void debug(final Supplier<String> message) {
        // The supplier is re-wrapped rather than invoked: the whole point of the overload is that an
        // unread message is never built, and calling get() here to prepend a prefix would build
        // every one of them whether or not debug is on.
        delegate.debug(new Supplier<String>() {
            @Override
            public String get() {
                return prefix + (message == null ? "null" : message.get());
            }
        });
    }

    @Override
    public boolean isDebugEnabled() {
        return delegate.isDebugEnabled();
    }

    @Override
    public void setDebugEnabled(boolean debugEnabled) {
        delegate.setDebugEnabled(debugEnabled);
    }
}
