package com.heimdall.core.http;

import com.heimdall.core.config.ServerRole;
import com.heimdall.core.util.Strings;

/**
 * What a server tells the bot when it exchanges a setup code for credentials.
 *
 * <p>Only {@link #code()} is required. The other three are metadata the dashboard shows against the
 * newly registered server, and getting one of them wrong is not an error the bot reports: it
 * validates {@code role} against its own three values and <strong>silently stores null</strong> for
 * anything else, so a typo produces a successful claim and a server with no role rather than a 400.
 * That is why the role is a {@link ServerRole} here rather than a String — the one spelling that can
 * reach the wire is the one {@link ServerRole#wireName()} produces.
 *
 * <p>A builder rather than four Strings in a row, for the reason departure D21 gives: three of the
 * four are optional and nothing at the call site would say which is the version and which is the
 * platform.
 *
 * <p>Immutable.
 */
public final class ClaimRequest {

    private final String code;
    private final String platform;
    private final String mcVersion;
    private final ServerRole role;

    private ClaimRequest(Builder builder) {
        if (Strings.isBlank(builder.code)) {
            throw new IllegalArgumentException("a setup code is required");
        }
        this.code = builder.code.trim();
        this.platform = Strings.trimToEmpty(builder.platform);
        this.mcVersion = Strings.trimToEmpty(builder.mcVersion);
        this.role = builder.role;
    }

    public static Builder forCode(String code) {
        return new Builder().code(code);
    }

    /**
     * The setup code, as the operator typed it.
     *
     * <p>Deliberately <em>not</em> normalised here. The bot upper-cases it and strips every
     * non-alphanumeric character, so {@code abcd-2345} and {@code ABCD 2345} both reach
     * {@code ABCD2345} — and normalising client-side as well would mean two implementations of one
     * rule, only one of which is authoritative. Sending it verbatim also keeps the plugin correct if
     * the bot's alphabet ever widens.
     */
    public String code() {
        return code;
    }

    /** The platform family, e.g. {@code bukkit} or {@code velocity}. May be empty. */
    public String platform() {
        return platform;
    }

    /** The Minecraft or proxy version, for the dashboard's server list. May be empty. */
    public String mcVersion() {
        return mcVersion;
    }

    /** The resolved role, or {@code null} to send none. */
    public ServerRole role() {
        return role;
    }

    @Override
    public String toString() {
        // The code is a bearer credential for exactly one claim. It is single-use and it is already
        // spent by the time anything would log this, but printing it would still put it in a
        // transcript somebody pastes into a support channel.
        return "ClaimRequest{code=<redacted>, platform='" + platform + "', mcVersion='" + mcVersion
                + "', role=" + (role == null ? "none" : role.wireName()) + "}";
    }

    /** The mutable writer. Only the code is required. */
    public static final class Builder {

        private String code;
        private String platform;
        private String mcVersion;
        private ServerRole role;

        private Builder() {
        }

        public Builder code(String value) {
            this.code = value;
            return this;
        }

        public Builder platform(String value) {
            this.platform = value;
            return this;
        }

        public Builder mcVersion(String value) {
            this.mcVersion = value;
            return this;
        }

        /** {@code null}, or {@link ServerRole#AUTO}, sends no role at all. */
        public Builder role(ServerRole value) {
            this.role = value == ServerRole.AUTO ? null : value;
            return this;
        }

        public ClaimRequest build() {
            return new ClaimRequest(this);
        }
    }
}
