package com.heimdall.core.http;

import com.heimdall.core.config.BootstrapConfig;
import com.heimdall.core.util.Strings;

/**
 * Where the bot is, who we are to it, and how patient to be.
 *
 * <p>Immutable. {@link ApiClient} holds one in a volatile field and swaps the whole object on
 * reconfiguration, so a reload can never be observed half-applied — v2 had seven separate volatile
 * fields written one at a time, which meant an in-flight worker could sign with the new secret
 * against the old base URL.
 */
public final class ApiSettings {

    /** Anything below this is a misconfiguration, not a tuning choice. */
    public static final int MIN_TIMEOUT_MS = 250;

    /** Default per-attempt timeout. The login path is latency-sensitive. */
    public static final int DEFAULT_TIMEOUT_MS = 5000;

    /** Default total attempts, matching v2's shipped config. */
    public static final int DEFAULT_RETRIES = 3;

    /** Default pause between attempts. */
    public static final int DEFAULT_RETRY_DELAY_MS = 1000;

    /** Floor for the whitelist dump, which can be large and is never on the login path. */
    public static final int WHITELIST_SYNC_TIMEOUT_MS = 15_000;

    /** Floor for the update check, which nobody is waiting on. */
    public static final int UPDATE_CHECK_TIMEOUT_MS = 8000;

    private final String baseUrl;
    private final String guildId;
    private final String apiKey;
    private final String serverId;
    private final int timeoutMs;
    private final int retries;
    private final int retryDelayMs;

    private ApiSettings(Builder builder) {
        this.baseUrl = stripTrailingSlash(Strings.trimToEmpty(builder.baseUrl));
        this.guildId = Strings.trimToEmpty(builder.guildId);
        this.apiKey = Strings.trimToEmpty(builder.apiKey);
        this.serverId = Strings.trimToEmpty(builder.serverId);
        this.timeoutMs = Math.max(MIN_TIMEOUT_MS, builder.timeoutMs);
        this.retries = Math.max(1, builder.retries);
        this.retryDelayMs = Math.max(0, builder.retryDelayMs);
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Settings derived from {@code bootstrap.yml}, with the timing defaults.
     *
     * <p>The guild id is separate because the plugin can be configured with a token alone and
     * resolve its guild from the bot; only once that has happened is there a guild id to put here.
     */
    public static Builder from(BootstrapConfig bootstrap, String guildId) {
        return builder()
                .baseUrl(bootstrap.endpoint())
                .apiKey(bootstrap.token())
                .serverId(bootstrap.serverId())
                .guildId(guildId);
    }

    /** The bot's base URL, without a trailing slash. */
    public String baseUrl() {
        return baseUrl;
    }

    /** The Discord guild whose Minecraft config this server belongs to. */
    public String guildId() {
        return guildId;
    }

    /** The HMAC secret. Never log this. */
    public String apiKey() {
        return apiKey;
    }

    /** This server's identifier within the guild, sent so events can be attributed to it. */
    public String serverId() {
        return serverId;
    }

    /** Connect and read timeout for a single attempt. */
    public int timeoutMs() {
        return timeoutMs;
    }

    /** Total attempts, including the first. Never below 1. */
    public int retries() {
        return retries;
    }

    /** Pause between attempts. */
    public int retryDelayMs() {
        return retryDelayMs;
    }

    /**
     * Worst-case wall clock for one logical request including its whole retry sequence.
     *
     * <p>{@code retries} attempts of {@code timeoutMs} each, plus {@code retries - 1} delays
     * between them. A caller that blocks on the returned future <strong>must</strong> bound on this
     * and not on {@link #timeoutMs()}: v2's login path waited {@code timeout + 1000}ms, which with
     * three retries abandoned the request four seconds before the retry loop had finished
     * legitimately working on it (issue #797 / MC-6).
     */
    public long overallTimeoutMs() {
        return (long) retries * timeoutMs + (long) Math.max(0, retries - 1) * retryDelayMs;
    }

    /** Per-attempt timeout for the whitelist dump: never shorter than {@link #WHITELIST_SYNC_TIMEOUT_MS}. */
    public int whitelistSyncTimeoutMs() {
        return Math.max(timeoutMs, WHITELIST_SYNC_TIMEOUT_MS);
    }

    /** Per-attempt timeout for the update check: never shorter than {@link #UPDATE_CHECK_TIMEOUT_MS}. */
    public int updateCheckTimeoutMs() {
        return Math.max(timeoutMs, UPDATE_CHECK_TIMEOUT_MS);
    }

    /** Whether there is enough here to sign and address a request. */
    public boolean isUsable() {
        return Strings.isNotBlank(baseUrl) && Strings.isNotBlank(guildId) && Strings.isNotBlank(apiKey);
    }

    /** A writer pre-populated with these values. */
    public Builder toBuilder() {
        return builder()
                .baseUrl(baseUrl)
                .guildId(guildId)
                .apiKey(apiKey)
                .serverId(serverId)
                .timeoutMs(timeoutMs)
                .retries(retries)
                .retryDelayMs(retryDelayMs);
    }

    /** Renders everything except {@link #apiKey()}. */
    @Override
    public String toString() {
        return "ApiSettings{baseUrl='" + baseUrl + "', guildId='" + guildId
                + "', serverId='" + serverId
                + "', apiKey=" + (apiKey.isEmpty() ? "<unset>" : "<redacted>")
                + ", timeoutMs=" + timeoutMs + ", retries=" + retries
                + ", retryDelayMs=" + retryDelayMs + "}";
    }

    private static String stripTrailingSlash(String value) {
        String result = value;
        while (result.length() > 1 && result.charAt(result.length() - 1) == '/') {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    /** Mutable writer. Timing knobs are clamped on build, not here. */
    public static final class Builder {

        private String baseUrl = "";
        private String guildId = "";
        private String apiKey = "";
        private String serverId = "";
        private int timeoutMs = DEFAULT_TIMEOUT_MS;
        private int retries = DEFAULT_RETRIES;
        private int retryDelayMs = DEFAULT_RETRY_DELAY_MS;

        private Builder() {
        }

        public Builder baseUrl(String value) {
            this.baseUrl = value;
            return this;
        }

        public Builder guildId(String value) {
            this.guildId = value;
            return this;
        }

        public Builder apiKey(String value) {
            this.apiKey = value;
            return this;
        }

        public Builder serverId(String value) {
            this.serverId = value;
            return this;
        }

        /** Clamped to at least {@link #MIN_TIMEOUT_MS}. */
        public Builder timeoutMs(int value) {
            this.timeoutMs = value;
            return this;
        }

        /** Total attempts including the first; clamped to at least 1. */
        public Builder retries(int value) {
            this.retries = value;
            return this;
        }

        /** Clamped to at least 0. */
        public Builder retryDelayMs(int value) {
            this.retryDelayMs = value;
            return this;
        }

        public ApiSettings build() {
            return new ApiSettings(this);
        }
    }
}
