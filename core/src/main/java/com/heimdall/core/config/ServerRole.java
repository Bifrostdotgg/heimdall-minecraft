package com.heimdall.core.config;

import java.util.Locale;

/**
 * What this server instance does in a network.
 *
 * <p>A proxied network runs the same jar in more than one place, and "who enforces the whitelist"
 * has to be answered once per install or two components fight over the same login:
 *
 * <ul>
 *   <li>{@link #AUTO} — decide from the platform and what else is connected. The default, and the
 *       right answer for the overwhelming majority of installs.
 *   <li>{@link #STANDALONE} — a single server with no proxy in front of it: it both decides and
 *       enforces.
 *   <li>{@link #GATEKEEPER} — the proxy. It owns the login decision for everything behind it.
 *   <li>{@link #ENFORCER} — a backend server behind a gatekeeper. It applies role sync, runs
 *       commands and reports offenses, but does not re-run the login decision.
 * </ul>
 *
 * <p>The wire and config spelling is the lower-case name.
 */
public enum ServerRole {

    /** Work it out at runtime. */
    AUTO,

    /** No proxy: this server both decides and enforces. */
    STANDALONE,

    /** Behind a gatekeeper: applies decisions, does not make them. */
    ENFORCER,

    /** The proxy: owns the login decision for the network behind it. */
    GATEKEEPER;

    /** The lower-case spelling used in {@code bootstrap.yml} and on the wire. */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Parses a config spelling, tolerating case and surrounding whitespace.
     *
     * <p>Returns {@code fallback} rather than throwing for an unrecognised value: a typo in
     * {@code bootstrap.yml} must not stop the plugin from booting far enough to say so.
     *
     * <p>Hyphens and underscores are stripped, so {@code gate-keeper} and {@code stand_alone} both
     * land where their author meant them to. Every role name is a single word, so nothing is lost
     * by doing that — if a two-word role is ever added, this needs revisiting rather than extending.
     *
     * @param raw the configured value; may be {@code null} or blank
     * @param fallback what to return when {@code raw} is missing or unrecognised
     */
    public static ServerRole parse(String raw, ServerRole fallback) {
        if (raw == null) {
            return fallback;
        }
        String normalised = raw.trim().toUpperCase(Locale.ROOT).replace("-", "").replace("_", "");
        for (ServerRole role : values()) {
            if (role.name().equals(normalised)) {
                return role;
            }
        }
        return fallback;
    }
}
