package com.heimdall.core.config;

import java.util.Locale;

/**
 * How hard the plugin argues when the recorded instance fingerprint does not match this machine.
 *
 * <p>The comparison exists because {@code serverId} is minted once and then lives in a file: copy
 * the server directory and two processes claim the same id, take it in turns to evict each other
 * from the tunnel, and the bot spends the afternoon reconnecting them. See
 * {@code com.heimdall.core.identity.InstanceFingerprint}.
 *
 * <ul>
 *   <li>{@link #AUTO} - refuse to dial only when the fingerprint is panel-tier, which is the case
 *       where a change really does mean "different server". Otherwise warn and connect anyway. The
 *       default.
 *   <li>{@link #STRICT} - refuse to dial on any mismatch, including host-tier. For operators who
 *       would rather a copied directory sat offline than joined the fight.
 *   <li>{@link #OFF} - do not compare at all. The escape hatch for a setup that legitimately moves
 *       between hosts and cannot produce a stable fingerprint.
 * </ul>
 *
 * <p>The config spelling is the lower-case name.
 */
public enum IdentityCheckPolicy {

    /** Block on a panel-tier mismatch, warn on a host-tier one. */
    AUTO,

    /** Block on any mismatch. */
    STRICT,

    /** Never compare. */
    OFF;

    /** The lower-case spelling used in {@code bootstrap.yml}. */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Parses a config spelling, tolerating case and surrounding whitespace.
     *
     * <p>Returns {@code fallback} rather than throwing for an unrecognised value, for the same
     * reason {@link ServerRole#parse(String, ServerRole)} does: a typo must not stop the plugin from
     * booting far enough to complain about it.
     *
     * @param raw the configured value; may be {@code null} or blank
     * @param fallback what to return when {@code raw} is missing or unrecognised
     */
    public static IdentityCheckPolicy parse(String raw, IdentityCheckPolicy fallback) {
        if (raw == null) {
            return fallback;
        }
        String normalised = raw.trim().toUpperCase(Locale.ROOT).replace("-", "").replace("_", "");
        for (IdentityCheckPolicy policy : values()) {
            if (policy.name().equals(normalised)) {
                return policy;
            }
        }
        return fallback;
    }
}
