package com.heimdall.core.log;

/**
 * The four severities Heimdall logs at.
 *
 * <p>Kept to four on purpose. Every platform this plugin runs on has an {@code INFO / WARNING /
 * SEVERE} triple; {@link #DEBUG} is Heimdall's own, gated by {@link
 * HeimdallLogger#isDebugEnabled()} rather than by the server's log configuration, so a support
 * request can be answered with "set {@code debug: true}" and nothing else.
 */
public enum LogLevel {

    /** Ordinary operational messages. */
    INFO,

    /** Something is wrong but the plugin carried on. */
    WARN,

    /** Something failed and a feature is degraded or dead. */
    SEVERE,

    /** Diagnostic detail, emitted only when debug logging is on. */
    DEBUG
}
