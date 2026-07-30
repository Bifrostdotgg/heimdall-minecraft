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

    /**
     * The Discord chat bridge: batched {@code bridge.chat} and {@code bridge.event} out, rendered
     * {@code bridge.discord} in.
     *
     * <p>Relay only. Nothing behind this capability stores a message, on either side of the wire —
     * see {@link com.heimdall.core.pipeline.ChatPipeline} and departure D79.
     */
    public static final String BRIDGE = "bridge@1";

    /** The module framework itself: this client can enable and disable modules at runtime. */
    public static final String MODULES = "modules@1";

    /** Remote configuration: this client accepts {@code config.push} and acknowledges it. */
    public static final String CONFIG = "config@1";

    /** Separates a capability's name from its version. */
    private static final char VERSION_SEPARATOR = '@';

    private Capabilities() {
    }

    /**
     * The base name of a capability — {@code whitelist@1} → {@code whitelist}.
     *
     * <p><strong>This exists because of an unresolved question about the bot's side.</strong> The
     * bot narrows its {@code config.push} to "the modules the client declared a capability for", and
     * {@code stub-bot} implements that as exact string equality against the module id. Module ids
     * are unversioned ({@code whitelist}) and capability ids are not ({@code whitelist@1}), so an
     * exact match finds nothing and the client receives config for no modules at all — silently,
     * because an empty push is a valid push.
     *
     * <p>Either the bot matches on this base name, or capability ids and module ids are the same
     * string and the version lives elsewhere. That is a bot-side protocol decision for phase 1f, not
     * something to settle unilaterally here. The client declares versioned ids as designed, this
     * helper names the relationship, and {@code TunnelStubIntegrationTest} pins the current
     * behaviour both ways so the decision is made against an executable fact.
     */
    public static String moduleId(String capability) {
        if (capability == null) {
            return "";
        }
        int at = capability.indexOf(VERSION_SEPARATOR);
        return at < 0 ? capability : capability.substring(0, at);
    }

    /**
     * The version of a capability — {@code whitelist@1} → {@code 1}; {@code 0} if it carries none.
     *
     * <p>Versions are per capability rather than global on purpose: bumping one protocol should not
     * force every module in the jar to move with it, which is exactly the coupling the capability
     * handshake exists to avoid.
     */
    public static int version(String capability) {
        if (capability == null) {
            return 0;
        }
        int at = capability.indexOf(VERSION_SEPARATOR);
        if (at < 0 || at == capability.length() - 1) {
            return 0;
        }
        try {
            return Integer.parseInt(capability.substring(at + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
