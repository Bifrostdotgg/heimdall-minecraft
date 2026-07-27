package com.heimdall.core.config;

import com.heimdall.core.util.Strings;

/**
 * The contents of {@code bootstrap.yml}: how to reach the bot, and how to prove who we are.
 *
 * <p><strong>Nothing else belongs in this file, ever.</strong> Every other setting — messages,
 * cache windows, which modules are on, role-sync groups, offense templates — is owned by the
 * dashboard and arrives over the tunnel as remote config (phase 1b). That is the whole point of
 * the split: v2 had a 200-line {@code config.yml} per server, so a fleet operator changing one
 * message edited it on every box, and support could never be sure what a given server was actually
 * running. If you are about to add a field here, the question to answer first is why the dashboard
 * cannot own it.
 *
 * <p>Instances are immutable. {@link #toBuilder()} produces the mutable writer the setup flow uses;
 * {@link BootstrapStore} persists it.
 *
 * <p>{@link #toString()} redacts {@link #token()}. The token is a bearer credential for this
 * guild's Minecraft API, and a config object that renders itself into a log line is how those
 * escape.
 */
public final class BootstrapConfig {

    private final String endpoint;
    private final String tokenId;
    private final String token;
    private final String serverId;
    private final ServerRole role;
    private final boolean debug;

    private BootstrapConfig(Builder builder) {
        this.endpoint = stripTrailingSlash(Strings.trimToEmpty(builder.endpoint));
        this.tokenId = Strings.trimToEmpty(builder.tokenId);
        this.token = Strings.trimToEmpty(builder.token);
        this.serverId = Strings.trimToEmpty(builder.serverId);
        this.role = builder.role == null ? ServerRole.AUTO : builder.role;
        this.debug = builder.debug;
    }

    /** An empty, not-configured bootstrap: what a server with no {@code bootstrap.yml} has. */
    public static BootstrapConfig defaults() {
        return builder().build();
    }

    /** A fresh writer with the defaults applied. */
    public static Builder builder() {
        return new Builder();
    }

    /** A writer pre-populated with this config's values. */
    public Builder toBuilder() {
        return new Builder()
                .endpoint(endpoint)
                .tokenId(tokenId)
                .token(token)
                .serverId(serverId)
                .role(role)
                .debug(debug);
    }

    /**
     * The bot's base URL, with any trailing slash removed, e.g. {@code https://api.bifrost.gg}.
     *
     * <p>Normalised on construction so callers can concatenate a path onto it without each of them
     * having to think about the slash — which is exactly the kind of detail that ends up handled
     * three ways in four call sites.
     */
    public String endpoint() {
        return endpoint;
    }

    /** The guild API token's public identifier, sent so the bot can look the secret up. */
    public String tokenId() {
        return tokenId;
    }

    /** The shared secret this server signs its requests with. Never log this. */
    public String token() {
        return token;
    }

    /** This server's own identifier within the guild, so the bot can attribute events to it. */
    public String serverId() {
        return serverId;
    }

    /** What this instance does in the network. Never {@code null}; defaults to {@link ServerRole#AUTO}. */
    public ServerRole role() {
        return role;
    }

    /** Whether debug logging starts on. The one diagnostic knob that has to be local. */
    public boolean debug() {
        return debug;
    }

    /**
     * Whether this config carries enough to talk to the bot at all.
     *
     * <p>{@code false} means the setup flow has not run: no endpoint, or no credentials. It is not
     * a claim that the credentials are <em>valid</em> — only the bot can say that.
     */
    public boolean isConfigured() {
        return Strings.isNotBlank(endpoint) && Strings.isNotBlank(tokenId) && Strings.isNotBlank(token);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof BootstrapConfig)) {
            return false;
        }
        BootstrapConfig that = (BootstrapConfig) other;
        return debug == that.debug
                && role == that.role
                && endpoint.equals(that.endpoint)
                && tokenId.equals(that.tokenId)
                && token.equals(that.token)
                && serverId.equals(that.serverId);
    }

    @Override
    public int hashCode() {
        int result = endpoint.hashCode();
        result = 31 * result + tokenId.hashCode();
        result = 31 * result + token.hashCode();
        result = 31 * result + serverId.hashCode();
        result = 31 * result + role.hashCode();
        result = 31 * result + (debug ? 1 : 0);
        return result;
    }

    /** Renders every field except {@link #token()}, which is replaced with a presence marker. */
    @Override
    public String toString() {
        return "BootstrapConfig{endpoint='" + endpoint
                + "', tokenId='" + tokenId
                + "', token=" + (token.isEmpty() ? "<unset>" : "<redacted>")
                + ", serverId='" + serverId
                + "', role=" + role.wireName()
                + ", debug=" + debug
                + "}";
    }

    private static String stripTrailingSlash(String value) {
        String result = value;
        while (result.length() > 1 && result.charAt(result.length() - 1) == '/') {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    /** The mutable writer: what the setup flow fills in before handing it to {@link BootstrapStore}. */
    public static final class Builder {

        private String endpoint = "";
        private String tokenId = "";
        private String token = "";
        private String serverId = "";
        private ServerRole role = ServerRole.AUTO;
        private boolean debug;

        private Builder() {
        }

        public Builder endpoint(String value) {
            this.endpoint = value;
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

        /** {@code null} is accepted and means {@link ServerRole#AUTO}. */
        public Builder role(ServerRole value) {
            this.role = value;
            return this;
        }

        public Builder debug(boolean value) {
            this.debug = value;
            return this;
        }

        public BootstrapConfig build() {
            return new BootstrapConfig(this);
        }
    }
}
