package com.heimdall.core.http;

/**
 * The bot could not be asked, and this says which of the two reasons applies.
 *
 * <p>Raised by {@link HeimdallApi} <em>before</em> a request is built, so it is not a failure of the
 * bot: nothing was sent. The two reasons need telling apart because they are different
 * conversations with an operator, and running them together is how somebody ends up checking their
 * firewall over a server that was never set up:
 *
 * <ul>
 *   <li>{@link HeimdallApi.Availability#NOT_CONFIGURED} — there is no {@code bootstrap.yml}, or it
 *       carries no endpoint or no token. The answer is {@code /hd setup <code>}.
 *   <li>{@link HeimdallApi.Availability#DISCOVERING} — there are credentials, but the guild they
 *       belong to has not come back from {@code identify} yet. Nobody has to do anything; it
 *       resolves on its own, and until it does every guild-scoped path would be
 *       {@code /api/guilds//minecraft/…} — a signed, malformed, 404'd request (departure D54).
 * </ul>
 *
 * <p>A {@link RuntimeException} rather than a checked one because every call site reaches it through
 * a {@link java.util.concurrent.CompletableFuture} that has already completed exceptionally, and a
 * checked exception cannot travel that way without being wrapped anyway.
 */
public final class ApiUnavailableException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    private final HeimdallApi.Availability reason;

    ApiUnavailableException(HeimdallApi.Availability reason, String message) {
        super(message);
        this.reason = reason;
    }

    /** Which state the gateway was in. Never {@link HeimdallApi.Availability#READY}. */
    public HeimdallApi.Availability reason() {
        return reason;
    }
}
