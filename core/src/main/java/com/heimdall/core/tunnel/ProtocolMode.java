package com.heimdall.core.tunnel;

/**
 * Which protocol the bot on the other end of this connection turned out to speak.
 *
 * <p>Decided per connection, by negotiation, and reset on every reconnect. That last part matters:
 * a fleet is upgraded one bot at a time, and a plugin that cached "this bot is v2" from a
 * connection made before the deploy would keep running on stale config until someone restarted the
 * server.
 *
 * <p>Modules query this rather than assuming. A module whose behaviour depends on pushed config
 * needs to know whether config is arriving at all.
 */
public enum ProtocolMode {

    /**
     * No connection, or the handshake is still in flight.
     *
     * <p>Not an error state — it is where every connection starts, and where it returns on close.
     * A module asked to act while in this state should use whatever it already has (cached config,
     * the local mirror) rather than wait.
     */
    UNKNOWN,

    /**
     * The bot answered {@code identify_ack} and speaks the v3 capability protocol.
     *
     * <p>Config is pushed, module toggles take effect live, and capabilities were understood.
     */
    V3,

    /**
     * The bot never answered, or refused the protocol version.
     *
     * <p><strong>Silence is the v2 contract, not a failure.</strong> The deployed bot answers
     * {@code identify} with nothing at all, so a client that treated no-reply as an error would
     * reconnect-loop against every bot that has not been upgraded yet. In this mode the tunnel
     * still carries everything v2 carried — pings, role sync, commands, health — and remote config
     * falls back to the disk cache or the built-in defaults.
     */
    V2_COMPAT
}
