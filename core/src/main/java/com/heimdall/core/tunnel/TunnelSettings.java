package com.heimdall.core.tunnel;

import com.heimdall.core.util.Strings;

/**
 * Where the tunnel connects, who it says it is, and every timing constant it obeys.
 *
 * <p>Immutable, and swapped wholesale rather than mutated. {@link TunnelClient} holds one in a
 * volatile field, so a reload can never be observed half-applied — v2 kept eight separate mutable
 * fields refreshed by an {@code updateConfig()} the socket thread could read mid-write, which meant
 * a reconnect could sign with the new secret against the old base URL.
 *
 * <p>The defaults are v2's shipped values, deliberately: they are what the fleet has been running
 * against the real bot. The two that are new in v3 are called out below.
 */
public final class TunnelSettings {

    /** v2's {@code websocket.reconnect-delay}: the first backoff step. */
    public static final long DEFAULT_RECONNECT_DELAY_MS = 5_000L;

    /** v2's {@code websocket.max-reconnect-delay}: the backoff ceiling. */
    public static final long DEFAULT_MAX_RECONNECT_DELAY_MS = 30_000L;

    /** v2's {@code websocket.heartbeat-interval}. Also the health-snapshot cadence. */
    public static final long DEFAULT_HEARTBEAT_INTERVAL_MS = 30_000L;

    /** v2's {@code websocket.heartbeat-timeout}: the slack added on top of the interval. */
    public static final long DEFAULT_HEARTBEAT_TIMEOUT_MS = 10_000L;

    /**
     * How long to wait for {@code identify_ack} before concluding the bot does not speak v3.
     *
     * <p><strong>New in v3.</strong> Ten seconds is generous on purpose: the cost of waiting too
     * long is a few seconds of running on cached config at startup, and the cost of waiting too
     * little is a v3 bot being treated as v2 for the whole life of the connection because its reply
     * was behind one slow event-loop tick.
     */
    public static final long DEFAULT_NEGOTIATION_TIMEOUT_MS = 10_000L;

    /** Default deadline for a correlated request that does not name its own. */
    public static final long DEFAULT_REQUEST_TIMEOUT_MS = 10_000L;

    /** Default TCP connect deadline. */
    public static final int DEFAULT_CONNECT_TIMEOUT_MS = 10_000;

    /** The protocol version a v3 client declares in {@code identify}. */
    public static final int PROTOCOL_VERSION = 3;

    private final String endpoint;
    private final String guildId;
    private final String serverId;
    private final String apiKey;
    private final long reconnectDelayMs;
    private final long maxReconnectDelayMs;
    private final long heartbeatIntervalMs;
    private final long heartbeatTimeoutMs;
    private final long negotiationTimeoutMs;
    private final long requestTimeoutMs;
    private final int connectTimeoutMs;

    private TunnelSettings(Builder builder) {
        this.endpoint = stripTrailingSlash(Strings.trimToEmpty(builder.endpoint));
        this.guildId = Strings.trimToEmpty(builder.guildId);
        this.serverId = Strings.isBlank(builder.serverId) ? "default" : builder.serverId.trim();
        this.apiKey = Strings.trimToEmpty(builder.apiKey);
        // Every bound is clamped to at least 1ms rather than validated-and-rejected. A nonsensical
        // value in remote config should degrade to "very eager" and be visible in the logs, not
        // stop the plugin from connecting at all.
        this.reconnectDelayMs = Math.max(1L, builder.reconnectDelayMs);
        this.maxReconnectDelayMs = Math.max(this.reconnectDelayMs, builder.maxReconnectDelayMs);
        this.heartbeatIntervalMs = Math.max(1L, builder.heartbeatIntervalMs);
        this.heartbeatTimeoutMs = Math.max(0L, builder.heartbeatTimeoutMs);
        this.negotiationTimeoutMs = Math.max(1L, builder.negotiationTimeoutMs);
        this.requestTimeoutMs = Math.max(1L, builder.requestTimeoutMs);
        this.connectTimeoutMs = Math.max(1, builder.connectTimeoutMs);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** A writer pre-populated with these values. */
    public Builder toBuilder() {
        return new Builder()
                .endpoint(endpoint)
                .guildId(guildId)
                .serverId(serverId)
                .apiKey(apiKey)
                .reconnectDelayMs(reconnectDelayMs)
                .maxReconnectDelayMs(maxReconnectDelayMs)
                .heartbeatIntervalMs(heartbeatIntervalMs)
                .heartbeatTimeoutMs(heartbeatTimeoutMs)
                .negotiationTimeoutMs(negotiationTimeoutMs)
                .requestTimeoutMs(requestTimeoutMs)
                .connectTimeoutMs(connectTimeoutMs);
    }

    /** The bot's <em>HTTP</em> base URL, without a trailing slash. The ws URL is derived from it. */
    public String endpoint() {
        return endpoint;
    }

    /** The Discord guild this server belongs to; part of the WebSocket path. */
    public String guildId() {
        return guildId;
    }

    /** This server's id within the guild. Never blank — an absent one means {@code default}. */
    public String serverId() {
        return serverId;
    }

    /** The HMAC secret the upgrade is signed with. Never log this. */
    public String apiKey() {
        return apiKey;
    }

    /** The first backoff step, and the value backoff resets to after a successful open. */
    public long reconnectDelayMs() {
        return reconnectDelayMs;
    }

    /** The backoff ceiling. Never below {@link #reconnectDelayMs()}. */
    public long maxReconnectDelayMs() {
        return maxReconnectDelayMs;
    }

    /** How often the heartbeat tick runs. */
    public long heartbeatIntervalMs() {
        return heartbeatIntervalMs;
    }

    /**
     * Slack on top of {@link #heartbeatIntervalMs()} before silence counts as a dead link.
     *
     * <p>The check is {@code now - lastPong > interval + timeout}, v2's formula unchanged. Adding
     * the interval is what stops a tick that fires slightly early from declaring a link dead purely
     * because the bot's ping is due at the same moment.
     */
    public long heartbeatTimeoutMs() {
        return heartbeatTimeoutMs;
    }

    /** How long {@code identify_ack} has to arrive before the client falls back to V2 compat. */
    public long negotiationTimeoutMs() {
        return negotiationTimeoutMs;
    }

    /** Default deadline for {@code sendAndWait} when the caller does not give one. */
    public long requestTimeoutMs() {
        return requestTimeoutMs;
    }

    /** TCP connect deadline for one attempt. */
    public int connectTimeoutMs() {
        return connectTimeoutMs;
    }

    /** Whether there is enough here to attempt a connection at all. */
    public boolean isConfigured() {
        return Strings.isNotBlank(endpoint) && Strings.isNotBlank(guildId) && Strings.isNotBlank(apiKey);
    }

    /** Renders every field except {@link #apiKey()}, which is replaced with a presence marker. */
    @Override
    public String toString() {
        return "TunnelSettings{endpoint='" + endpoint
                + "', guildId='" + guildId
                + "', serverId='" + serverId
                + "', apiKey=" + (apiKey.isEmpty() ? "<unset>" : "<redacted>")
                + ", reconnectDelayMs=" + reconnectDelayMs
                + ", maxReconnectDelayMs=" + maxReconnectDelayMs
                + ", heartbeatIntervalMs=" + heartbeatIntervalMs
                + ", heartbeatTimeoutMs=" + heartbeatTimeoutMs
                + ", negotiationTimeoutMs=" + negotiationTimeoutMs
                + ", requestTimeoutMs=" + requestTimeoutMs
                + ", connectTimeoutMs=" + connectTimeoutMs
                + "}";
    }

    private static String stripTrailingSlash(String value) {
        String result = value;
        while (result.length() > 1 && result.charAt(result.length() - 1) == '/') {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    /** The mutable writer. */
    public static final class Builder {

        private String endpoint = "";
        private String guildId = "";
        private String serverId = "";
        private String apiKey = "";
        private long reconnectDelayMs = DEFAULT_RECONNECT_DELAY_MS;
        private long maxReconnectDelayMs = DEFAULT_MAX_RECONNECT_DELAY_MS;
        private long heartbeatIntervalMs = DEFAULT_HEARTBEAT_INTERVAL_MS;
        private long heartbeatTimeoutMs = DEFAULT_HEARTBEAT_TIMEOUT_MS;
        private long negotiationTimeoutMs = DEFAULT_NEGOTIATION_TIMEOUT_MS;
        private long requestTimeoutMs = DEFAULT_REQUEST_TIMEOUT_MS;
        private int connectTimeoutMs = DEFAULT_CONNECT_TIMEOUT_MS;

        private Builder() {
        }

        public Builder endpoint(String value) {
            this.endpoint = value;
            return this;
        }

        public Builder guildId(String value) {
            this.guildId = value;
            return this;
        }

        public Builder serverId(String value) {
            this.serverId = value;
            return this;
        }

        public Builder apiKey(String value) {
            this.apiKey = value;
            return this;
        }

        public Builder reconnectDelayMs(long value) {
            this.reconnectDelayMs = value;
            return this;
        }

        public Builder maxReconnectDelayMs(long value) {
            this.maxReconnectDelayMs = value;
            return this;
        }

        public Builder heartbeatIntervalMs(long value) {
            this.heartbeatIntervalMs = value;
            return this;
        }

        public Builder heartbeatTimeoutMs(long value) {
            this.heartbeatTimeoutMs = value;
            return this;
        }

        public Builder negotiationTimeoutMs(long value) {
            this.negotiationTimeoutMs = value;
            return this;
        }

        public Builder requestTimeoutMs(long value) {
            this.requestTimeoutMs = value;
            return this;
        }

        public Builder connectTimeoutMs(int value) {
            this.connectTimeoutMs = value;
            return this;
        }

        public TunnelSettings build() {
            return new TunnelSettings(this);
        }
    }
}
