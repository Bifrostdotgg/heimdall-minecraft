package com.heimdall.core.pipeline;

import com.heimdall.core.util.Strings;
import java.util.UUID;

/**
 * One player trying to join, as the login checks see them.
 *
 * <p>Immutable, and deliberately small: the platform-specific event object stays in the platform
 * module. An interceptor written against a Bukkit {@code AsyncPlayerPreLoginEvent} could not run on
 * Velocity, and the whole point of the single-jar design is that the checks do not care.
 *
 * <p>{@code isBedrock} is here rather than derived because deriving it needs Floodgate, and
 * Floodgate access is reflective — invisible to the conformance rules, and therefore confined to a
 * platform module by construction (departure D9).
 */
public final class LoginAttempt {

    private final UUID uuid;
    private final String username;
    private final String ipAddress;
    private final boolean bedrock;

    private LoginAttempt(Builder builder) {
        if (builder.uuid == null) {
            throw new IllegalArgumentException("uuid is required");
        }
        this.uuid = builder.uuid;
        this.username = Strings.trimToEmpty(builder.username);
        this.ipAddress = Strings.trimToEmpty(builder.ipAddress);
        this.bedrock = builder.bedrock;
    }

    public static Builder builder(UUID uuid) {
        return new Builder(uuid);
    }

    /** The player's Minecraft UUID. */
    public UUID uuid() {
        return uuid;
    }

    /**
     * The username exactly as the platform reported it, trimmed.
     *
     * <p>Not lower-cased. v2 normalised it and quietly rewrote {@code Steve} to {@code steve} in the
     * bot's database on every link (departure D8).
     */
    public String username() {
        return username;
    }

    /** The connecting address, or {@code ""} if the platform did not supply one. */
    public String ipAddress() {
        return ipAddress;
    }

    /** Whether this is a Bedrock player arriving through Floodgate. */
    public boolean isBedrock() {
        return bedrock;
    }

    @Override
    public String toString() {
        // The IP is left out on purpose: this renders into debug logs, and a login line carrying a
        // player's address is personal data in a file operators paste into support tickets.
        return "LoginAttempt{username='" + username + "', uuid=" + uuid + ", bedrock=" + bedrock + "}";
    }

    /** The mutable writer. */
    public static final class Builder {

        private final UUID uuid;
        private String username = "";
        private String ipAddress = "";
        private boolean bedrock;

        private Builder(UUID uuid) {
            this.uuid = uuid;
        }

        public Builder username(String value) {
            this.username = value;
            return this;
        }

        public Builder ipAddress(String value) {
            this.ipAddress = value;
            return this;
        }

        public Builder bedrock(boolean value) {
            this.bedrock = value;
            return this;
        }

        public LoginAttempt build() {
            return new LoginAttempt(this);
        }
    }
}
