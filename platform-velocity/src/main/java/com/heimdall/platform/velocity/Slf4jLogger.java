package com.heimdall.platform.velocity;

import com.heimdall.core.log.AbstractHeimdallLogger;
import com.heimdall.core.log.LogLevel;
import org.slf4j.Logger;

/**
 * A {@link com.heimdall.core.log.HeimdallLogger} over the SLF4J logger Velocity injects.
 *
 * <p>Wrapping the proxy's own logger rather than using {@code JulLogger} is what puts
 * {@code [heimdall]} in front of every line, which is how an operator tells a Heimdall message from
 * the fifty other plugins' in a proxy console.
 *
 * <p>{@link LogLevel#DEBUG} maps to {@code info} with a marker rather than to SLF4J's {@code debug}:
 * a proxy ships with debug logging off, so {@code logger.debug} would be swallowed and Heimdall's
 * own debug toggle would silently do nothing. Gating is Heimdall's job — by the time a record
 * reaches here, somebody has asked for it.
 *
 * <p>{@code org.slf4j} is safe to name directly here, unlike on the Bukkit side: Velocity provides
 * it, {@code :app} does not shade it, and {@code verifyShadowJar} deliberately exempts this package
 * from the banned-logging-facade scan for exactly that reason.
 */
final class Slf4jLogger extends AbstractHeimdallLogger {

    private final Logger delegate;

    Slf4jLogger(Logger delegate) {
        super(false);
        if (delegate == null) {
            throw new IllegalArgumentException("delegate logger is required");
        }
        this.delegate = delegate;
    }

    @Override
    protected void write(LogLevel level, String message, Throwable throwable) {
        switch (level) {
            case DEBUG:
                // Two overloads, chosen by hand. With a null throwable the varargs form is selected
                // and slf4j counts it as a second argument for a one-placeholder pattern, so every
                // debug line the plugin writes is preceded by "found 1 argument placeholders, but
                // provided 2" from slf4j itself — which on a debug-enabled proxy doubles the log.
                if (throwable == null) {
                    delegate.info("[DEBUG] {}", message);
                } else {
                    delegate.info("[DEBUG] " + message, throwable);
                }
                break;
            case WARN:
                delegate.warn(message, throwable);
                break;
            case SEVERE:
                delegate.error(message, throwable);
                break;
            case INFO:
            default:
                delegate.info(message, throwable);
                break;
        }
    }
}
