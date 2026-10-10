package com.heimdall.shell.hotswap;

/**
 * The shell's logging seam: four levels, plain strings, nothing else.
 *
 * <p>The shell cannot use core's {@code HeimdallLogger}, which lives in the swappable half, and it
 * should not care which logging library its platform uses. Each platform shell adapts its own
 * logger (JUL on the Bukkit family and BungeeCord, slf4j on Velocity) to this.
 *
 * <p>As everywhere in Heimdall, no line written through here ever contains chat text.
 */
public interface ShellLog {

    void info(String message);

    void warn(String message);

    void error(String message, Throwable cause);

    void debug(String message);
}
