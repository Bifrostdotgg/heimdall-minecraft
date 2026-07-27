package com.heimdall.core.http;

/**
 * Resolves a joining player's Bedrock identity, if they have one.
 *
 * <p>An interface in core with the reflective Floodgate implementation living in the platform
 * modules, for two reasons. The plugin must have no compile- or run-time dependency on Floodgate —
 * most servers do not have it installed — and reflection is invisible to the conformance rules, so
 * confining it to a platform module is the only way the "core is platform-free" claim stays
 * checkable.
 *
 * <p>Implementations must be safe to call from the IO threads and must never throw: a player whose
 * identity cannot be resolved is a Java player as far as the bot is concerned, which is the correct
 * fallback.
 */
public interface BedrockIdentityProvider {

    /** Resolves nothing. The default for a server with no Floodgate. */
    BedrockIdentityProvider NONE = new BedrockIdentityProvider() {
        @Override
        public BedrockIdentity resolve(String uuid) {
            return null;
        }
    };

    /**
     * @param uuid the (possibly synthetic) UUID the platform reported
     * @return the Bedrock identity, or {@code null} for a Java player or when Floodgate is absent
     */
    BedrockIdentity resolve(String uuid);
}
