package com.heimdall.stubbot;

import java.util.Locale;

/**
 * The six shapes {@code POST /connection-attempt} can answer with.
 *
 * <p>These are not invented categories — each one is a distinct branch in the bot's
 * {@code api/connection.ts}, and each puts a different set of keys in the {@code data} object. A
 * plugin that only ever sees {@link #ALLOW} and {@link #DENY} in testing will mis-handle the other
 * four in production, which is the entire reason the stub makes them selectable per-UUID.
 */
public enum Outcome {

    /** Whitelisted. {@code {whitelisted: true, message, roleSync}}. */
    ALLOW,

    /** Known but not whitelisted, or not linked at all. {@code {whitelisted: false, message}}. */
    DENY,

    /** Awaiting code confirmation. Adds {@code pendingAuth: true} and {@code authCode}. */
    PENDING_AUTH,

    /** Whitelist revoked. Adds {@code revoked: true}. */
    REVOKED,

    /** Linked, awaiting staff approval. Adds {@code pendingApproval: true} and {@code queuePosition}. */
    PENDING_APPROVAL,

    /**
     * Already on the server whitelist but not linked to Discord — the bot lets them in AND offers a
     * link code. Adds {@code existingPlayerLink: true} and {@code authCode}, with
     * {@code whitelisted: true}.
     */
    EXISTING_LINK;

    /** Parses the wire/config spelling ({@code pending_auth}, {@code PENDING-AUTH}, …). */
    public static Outcome parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("outcome is required");
        }
        String normalised = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (Outcome value : values()) {
            if (value.name().equals(normalised)) {
                return value;
            }
        }
        throw new IllegalArgumentException("unknown outcome '" + raw + "'");
    }

    /** Whether this outcome means the player is currently on the whitelist. */
    public boolean isWhitelisted() {
        return this == ALLOW || this == EXISTING_LINK;
    }
}
