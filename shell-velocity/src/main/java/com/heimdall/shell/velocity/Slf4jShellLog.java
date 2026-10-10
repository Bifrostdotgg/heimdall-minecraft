package com.heimdall.shell.velocity;

import com.heimdall.shell.hotswap.ShellLog;
import org.slf4j.Logger;

/** {@link ShellLog} over the slf4j logger Velocity injects, which prefixes {@code [heimdall]}. */
final class Slf4jShellLog implements ShellLog {

    private final Logger logger;

    Slf4jShellLog(Logger logger) {
        this.logger = logger;
    }

    @Override
    public void info(String message) {
        logger.info(message);
    }

    @Override
    public void warn(String message) {
        logger.warn(message);
    }

    @Override
    public void error(String message, Throwable cause) {
        if (cause == null) {
            logger.error(message);
        } else {
            logger.error(message, cause);
        }
    }

    @Override
    public void debug(String message) {
        logger.debug(message);
    }
}
