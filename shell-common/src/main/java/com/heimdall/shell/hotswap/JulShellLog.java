package com.heimdall.shell.hotswap;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@link ShellLog} over a {@code java.util.logging.Logger}: the plugin logger on the Bukkit family
 * and on BungeeCord, both of which already prefix every line with the plugin's name.
 */
public final class JulShellLog implements ShellLog {

    private final Logger logger;

    public JulShellLog(Logger logger) {
        if (logger == null) {
            throw new IllegalArgumentException("a logger is required");
        }
        this.logger = logger;
    }

    @Override
    public void info(String message) {
        logger.info(message);
    }

    @Override
    public void warn(String message) {
        logger.warning(message);
    }

    @Override
    public void error(String message, Throwable cause) {
        if (cause == null) {
            logger.severe(message);
        } else {
            logger.log(Level.SEVERE, message, cause);
        }
    }

    @Override
    public void debug(String message) {
        logger.fine(message);
    }
}
