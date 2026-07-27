package com.heimdall.core.http.model;

import com.heimdall.core.util.Lists;
import com.heimdall.core.util.Strings;
import java.util.List;
import java.util.Objects;

/**
 * What the plugin tells the bot about a player trying to connect.
 *
 * <p><strong>{@code username} is sent exactly as the platform reported it.</strong> v2 lower-cased
 * it, and the bot does match case-insensitively, so on this endpoint the difference is invisible —
 * {@code connection.ts} stores whatever it is handed. But v2 applied the <em>same</em>
 * normalisation to the link-code request, and {@code link.ts} writes {@code minecraftUsername}
 * straight from the request body, so every link silently rewrote "Steve" to "steve" in the bot's
 * database and the dashboard showed it that way from then on. Normalising in one place and not the
 * other is how that went unnoticed, so neither is normalised now. See {@code docs/v2-departures.md}.
 *
 * <p>Bedrock identity is <em>not</em> a field: it is resolved by the {@code
 * com.heimdall.core.http.BedrockIdentityProvider} the client was given and merged in at send time,
 * so a caller on the login path never has to know Floodgate exists.
 */
public final class ConnectionAttempt {

    private final String username;
    private final String uuid;
    private final String ip;
    private final String serverIp;
    private final boolean currentlyWhitelisted;
    private final List<String> currentGroups;

    private ConnectionAttempt(Builder builder) {
        if (Strings.isBlank(builder.username)) {
            throw new IllegalArgumentException("username is required");
        }
        if (Strings.isBlank(builder.uuid)) {
            throw new IllegalArgumentException("uuid is required");
        }
        this.username = builder.username.trim();
        this.uuid = builder.uuid.trim();
        this.ip = Strings.trimToEmpty(builder.ip);
        this.serverIp = Strings.isBlank(builder.serverIp) ? "localhost" : builder.serverIp.trim();
        this.currentlyWhitelisted = builder.currentlyWhitelisted;
        this.currentGroups = Lists.copyOf(builder.currentGroups);
    }

    public static Builder builder(String username, String uuid) {
        return new Builder().username(username).uuid(uuid);
    }

    /** The player's name, exactly as the platform reported it. */
    public String username() {
        return username;
    }

    /** The player's UUID as the platform reported it, which for Bedrock players is synthetic. */
    public String uuid() {
        return uuid;
    }

    /** The player's address. */
    public String ip() {
        return ip;
    }

    /** The address the player connected to, so the bot can tell multi-domain setups apart. */
    public String serverIp() {
        return serverIp;
    }

    /** Whether this server's own whitelist already lists them. */
    public boolean currentlyWhitelisted() {
        return currentlyWhitelisted;
    }

    /** The permission groups the player currently holds, for role-sync diffing. */
    public List<String> currentGroups() {
        return currentGroups;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ConnectionAttempt)) {
            return false;
        }
        ConnectionAttempt that = (ConnectionAttempt) other;
        return currentlyWhitelisted == that.currentlyWhitelisted
                && username.equals(that.username)
                && uuid.equals(that.uuid)
                && ip.equals(that.ip)
                && serverIp.equals(that.serverIp)
                && currentGroups.equals(that.currentGroups);
    }

    @Override
    public int hashCode() {
        return Objects.hash(username, uuid, ip, serverIp, Boolean.valueOf(currentlyWhitelisted),
                currentGroups);
    }

    @Override
    public String toString() {
        return "ConnectionAttempt{username='" + username + "', uuid='" + uuid + "'}";
    }

    /** Mutable writer. {@code username} and {@code uuid} are mandatory; everything else has a default. */
    public static final class Builder {

        private String username;
        private String uuid;
        private String ip = "";
        private String serverIp;
        private boolean currentlyWhitelisted;
        private List<String> currentGroups;

        private Builder() {
        }

        public Builder username(String value) {
            this.username = value;
            return this;
        }

        public Builder uuid(String value) {
            this.uuid = value;
            return this;
        }

        public Builder ip(String value) {
            this.ip = value;
            return this;
        }

        /** Defaults to {@code "localhost"} when blank, matching v2. */
        public Builder serverIp(String value) {
            this.serverIp = value;
            return this;
        }

        public Builder currentlyWhitelisted(boolean value) {
            this.currentlyWhitelisted = value;
            return this;
        }

        public Builder currentGroups(List<String> value) {
            this.currentGroups = value;
            return this;
        }

        public ConnectionAttempt build() {
            return new ConnectionAttempt(this);
        }
    }
}
