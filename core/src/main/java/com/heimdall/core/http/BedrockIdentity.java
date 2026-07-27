package com.heimdall.core.http;

/**
 * A Bedrock player's real identity, as opposed to the synthetic one the server sees.
 *
 * <p>Floodgate rewrites Bedrock usernames with a configurable prefix (default {@code .}) and hands
 * the server a synthetic UUID. The bot can infer "this is Bedrock" from the UUID shape and strip
 * its own configured prefix — but a server running a non-default Floodgate prefix that the
 * dashboard does not mirror breaks that inference silently. Sending the prefix-free gamertag and
 * the XUID explicitly makes the match work regardless of prefix config.
 */
public final class BedrockIdentity {

    private final String gamertag;
    private final String xuid;

    /**
     * @param gamertag the Java-safe gamertag <strong>without</strong> the Floodgate prefix
     * @param xuid the Xbox user id, or {@code null} if it could not be read
     */
    public BedrockIdentity(String gamertag, String xuid) {
        this.gamertag = gamertag;
        this.xuid = xuid;
    }

    /** The prefix-free gamertag. */
    public String gamertag() {
        return gamertag;
    }

    /** The XUID, or {@code null}. The gamertag alone is enough to match on. */
    public String xuid() {
        return xuid;
    }

    @Override
    public String toString() {
        return "BedrockIdentity{gamertag='" + gamertag + "', xuid='" + xuid + "'}";
    }
}
