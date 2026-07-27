package com.heimdall.core.tunnel;

/**
 * The capability identifiers a module may claim, in one place.
 *
 * <p><strong>These strings are a wire contract with the bot</strong>, not internal names. The bot
 * narrows its {@code config.push} to the modules the client declared a capability for, so a typo
 * here does not fail — it produces a module that runs with no configuration and no error anywhere,
 * which is the hardest possible shape of bug to find. Constants, referenced by name, are the
 * cheapest defence.
 *
 * <p><strong>The {@code @1} suffix is a version, and it is per capability.</strong> When the
 * whitelist protocol gains a field the plugin must understand to behave correctly, the new
 * identifier is {@code whitelist@2} and a bot that only knows {@code whitelist@1} keeps getting the
 * old shape. Bumping a single global protocol version instead would force every module in the jar
 * to move together, which is exactly the coupling the capability handshake exists to avoid.
 */
public final class Capabilities {

    /** Login gating: the connection-attempt call, the mirror, kick messages. */
    public static final String WHITELIST = "whitelist@1";

    /** Applying the bot's group snapshots to a player's permissions. */
    public static final String ROLE_SYNC = "rolesync@1";

    /** Streaming console output to the dashboard and running commands from it. */
    public static final String CONSOLE = "console@1";

    /** Periodic TPS/memory/player-count snapshots on the heartbeat. */
    public static final String HEALTH = "health@1";

    /** The module framework itself: this client can enable and disable modules at runtime. */
    public static final String MODULES = "modules@1";

    /** Remote configuration: this client accepts {@code config.push} and acknowledges it. */
    public static final String CONFIG = "config@1";

    private Capabilities() {
    }
}
