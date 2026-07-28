package com.heimdall.core.http.model;

import com.heimdall.core.util.Strings;

/**
 * What the bot hands back when a setup code is exchanged for credentials.
 *
 * <p>{@code POST /api/minecraft/claim} is the one endpoint that carries no signature — a server
 * claiming a code has nothing to sign with yet, which is the entire point of it — and the one that
 * returns a plaintext token. The token is returned <strong>exactly once</strong>; there is no way to
 * ask for it again, so a claim whose result is dropped costs the operator a new code.
 *
 * <p>Immutable, and {@link #toString()} redacts {@link #token()} for the same reason
 * {@code BootstrapConfig}'s does: this object exists for about a millisecond between the HTTP
 * response and the file write, and the one way it could escape is a log line built by reflex.
 *
 * <p>{@link #serverName()} is the name the operator typed into the dashboard when they minted the
 * code. It is not persisted anywhere by the plugin — the tunnel derives its server name from the
 * platform — and exists only so the success message can say which server was just claimed.
 */
public final class ClaimResult {

    private final String guildId;
    private final String tokenId;
    private final String token;
    private final String serverId;
    private final String serverName;

    private ClaimResult(Builder builder) {
        this.guildId = Strings.trimToEmpty(builder.guildId);
        this.tokenId = Strings.trimToEmpty(builder.tokenId);
        this.token = Strings.trimToEmpty(builder.token);
        this.serverId = Strings.trimToEmpty(builder.serverId);
        this.serverName = Strings.trimToEmpty(builder.serverName);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The guild the claimed token belongs to. */
    public String guildId() {
        return guildId;
    }

    /** The token's public identifier, sent as {@code X-Token-Id} on {@code identify}. */
    public String tokenId() {
        return tokenId;
    }

    /** The plaintext HMAC secret. Never log this, and never echo it to a command sender. */
    public String token() {
        return token;
    }

    /** The server id the bot registered for this claim, and the id the tunnel connects with. */
    public String serverId() {
        return serverId;
    }

    /** The display name the operator gave this server in the dashboard. */
    public String serverName() {
        return serverName;
    }

    /**
     * Whether the answer carries everything a {@code bootstrap.yml} needs.
     *
     * <p>A claim that succeeded but named no guild or no token is not something to write to disk:
     * the resulting file would look configured and fail every request afterwards, which is a much
     * harder thing to diagnose than a setup command that says the bot's answer was incomplete.
     */
    public boolean isComplete() {
        return Strings.isNotBlank(guildId) && Strings.isNotBlank(token);
    }

    @Override
    public String toString() {
        return "ClaimResult{guildId='" + guildId
                + "', tokenId='" + tokenId
                + "', token=" + (token.isEmpty() ? "<unset>" : "<redacted>")
                + ", serverId='" + serverId
                + "', serverName='" + serverName
                + "'}";
    }

    /** The mutable writer. */
    public static final class Builder {

        private String guildId;
        private String tokenId;
        private String token;
        private String serverId;
        private String serverName;

        private Builder() {
        }

        public Builder guildId(String value) {
            this.guildId = value;
            return this;
        }

        public Builder tokenId(String value) {
            this.tokenId = value;
            return this;
        }

        public Builder token(String value) {
            this.token = value;
            return this;
        }

        public Builder serverId(String value) {
            this.serverId = value;
            return this;
        }

        public Builder serverName(String value) {
            this.serverName = value;
            return this;
        }

        public ClaimResult build() {
            return new ClaimResult(this);
        }
    }
}
