/**
 * Heimdall's logging facade.
 *
 * <p>Deliberately not slf4j, Log4j or any other facade: legacy Spigot (1.8.8–1.16) ships none of
 * them, so a shipped class that so much as references one is a {@code NoClassDefFoundError} on a
 * customer's server. Platform modules wrap their native logger in a {@link
 * com.heimdall.core.log.HeimdallLogger}; everything else in the build only ever sees this
 * interface.
 */
package com.heimdall.core.log;
