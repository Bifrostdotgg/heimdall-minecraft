package com.heimdall.core.tunnel;

import com.heimdall.core.config.ServerRole;
import com.heimdall.core.json.Payload;
import com.heimdall.core.util.Strings;

/**
 * What this server tells the bot about itself on every connect.
 *
 * <p>The dashboard renders these fields directly — "Survival, Paper 1.20.4, plugin 3.0.0, up since
 * Tuesday" is this object — so they are the difference between a support conversation that starts
 * with a fact and one that starts with "what version are you on?".
 *
 * <p>Immutable. Built once by the platform layer at boot and re-sent on every reconnect; nothing in
 * it changes while the server is running except, in principle, the resolved role, which is why the
 * client asks its {@link IdentitySource} again on each connect rather than caching the payload.
 *
 * <p>{@code serverId}, {@code pluginVersion}, {@code protocolVersion} and {@code capabilities} are
 * deliberately <em>not</em> here: the first comes from {@link TunnelSettings}, the second from the
 * build, and the last two are the client's own protocol business. A platform module cannot get any
 * of them wrong because it is never asked for them.
 */
public final class ServerIdentity {

    private final String serverName;
    private final String platform;
    private final String serverSoftware;
    private final String mcVersion;
    private final long startedAtMs;
    private final ServerRole role;
    private final Payload extra;

    private ServerIdentity(Builder builder) {
        this.serverName = Strings.trimToEmpty(builder.serverName);
        this.platform = Strings.trimToEmpty(builder.platform);
        this.serverSoftware = Strings.trimToEmpty(builder.serverSoftware);
        this.mcVersion = Strings.trimToEmpty(builder.mcVersion);
        this.startedAtMs = builder.startedAtMs;
        this.role = builder.role == null ? ServerRole.AUTO : builder.role;
        this.extra = builder.extra == null ? Payload.empty() : builder.extra;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The human-readable name shown on the dashboard, e.g. {@code Survival}. */
    public String serverName() {
        return serverName;
    }

    /** {@code bukkit}, {@code velocity}, … — which platform module is driving. */
    public String platform() {
        return platform;
    }

    /** The server implementation, e.g. {@code Paper}, {@code Spigot}, {@code Velocity}. */
    public String serverSoftware() {
        return serverSoftware;
    }

    /** The Minecraft version, e.g. {@code 1.8.8}. */
    public String mcVersion() {
        return mcVersion;
    }

    /** When this server process started, in epoch millis. Renders as an uptime. */
    public long startedAtMs() {
        return startedAtMs;
    }

    /**
     * The <em>resolved</em> role — never {@link ServerRole#AUTO} by the time the bot sees it if the
     * platform layer did its job, because "auto" is a question and the bot needs the answer.
     */
    public ServerRole role() {
        return role;
    }

    /**
     * Anything else the platform wants to declare.
     *
     * <p>v2 merged an arbitrary metadata object into the identify payload and several platform
     * details arrived that way. Keeping an explicit escape hatch means adding one is a platform
     * change rather than a core one — but the named fields above stay named, so the common case is
     * still typed.
     */
    public Payload extra() {
        return extra;
    }

    @Override
    public String toString() {
        return "ServerIdentity{serverName='" + serverName
                + "', platform='" + platform
                + "', serverSoftware='" + serverSoftware
                + "', mcVersion='" + mcVersion
                + "', role=" + role.wireName()
                + ", startedAtMs=" + startedAtMs
                + "}";
    }

    /** The mutable writer. */
    public static final class Builder {

        private String serverName = "";
        private String platform = "";
        private String serverSoftware = "";
        private String mcVersion = "";
        private long startedAtMs = System.currentTimeMillis();
        private ServerRole role = ServerRole.AUTO;
        private Payload extra = Payload.empty();

        private Builder() {
        }

        public Builder serverName(String value) {
            this.serverName = value;
            return this;
        }

        public Builder platform(String value) {
            this.platform = value;
            return this;
        }

        public Builder serverSoftware(String value) {
            this.serverSoftware = value;
            return this;
        }

        public Builder mcVersion(String value) {
            this.mcVersion = value;
            return this;
        }

        public Builder startedAtMs(long value) {
            this.startedAtMs = value;
            return this;
        }

        public Builder role(ServerRole value) {
            this.role = value;
            return this;
        }

        public Builder extra(Payload value) {
            this.extra = value;
            return this;
        }

        public ServerIdentity build() {
            return new ServerIdentity(this);
        }
    }
}
