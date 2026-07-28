package com.heimdall.core.http;

import com.heimdall.core.util.Strings;

/**
 * Where the bot is, who we are to it, and how patient to be.
 *
 * <p>Immutable. {@link ApiClient} holds one in a volatile field and swaps the whole object on
 * reconfiguration, so a reload can never be observed half-applied — v2 had seven separate volatile
 * fields written one at a time, which meant an in-flight worker could sign with the new secret
 * against the old base URL.
 *
 * <p>Building one of these from {@code bootstrap.yml} is
 * {@code com.heimdall.core.wiring.ApiSettingsFactory}'s job, not this class's — {@code http} has no
 * business knowing the on-disk config format, and 1b adds a second source for the same settings.
 */
public final class ApiSettings {

    /** Anything below this is a misconfiguration, not a tuning choice. */
    public static final int MIN_TIMEOUT_MS = 250;

    /** Default per-attempt timeout. The login path is latency-sensitive. */
    public static final int DEFAULT_TIMEOUT_MS = 5000;

    /**
     * Default total attempts.
     *
     * <p><strong>Not v2's shipped value.</strong> v2 shipped {@code retries: 1}; v3 chooses 3 for
     * resilience, so a single dropped packet does not refuse a player. A server migrated from v2
     * keeps its own value — {@code ApiSettingsFactory.fromBootstrap} reads it from the bootstrap,
     * which the migration populated (departure D62) — so this default applies only to a freshly
     * claimed server that never expressed a preference.
     */
    public static final int DEFAULT_RETRIES = 3;

    /** Default pause between attempts. */
    public static final int DEFAULT_RETRY_DELAY_MS = 1000;

    /** Floor for the whitelist dump, which can be large and is never on the login path. */
    public static final int WHITELIST_SYNC_TIMEOUT_MS = 15_000;

    /** Floor for the update check, which nobody is waiting on. */
    public static final int UPDATE_CHECK_TIMEOUT_MS = 8000;

    /**
     * Margin added on top of a budget before actually blocking on a future.
     *
     * <p>v2's {@code WhitelistManager} waited {@code getOverallTimeoutMs() + 1000}, and the
     * constant was dropped when that call site was left behind in the v3 rewrite. Restored here
     * with a name, because it is doing real work: the budget counts one {@code timeoutMs} per
     * attempt, but {@code HttpURLConnection} applies the value <em>twice</em> — once as the connect
     * timeout and once as the read timeout — so a single attempt that stalls at both ends can cost
     * nearly double what the budget assumes. A second is not a proof against that; it is enough
     * margin for the ordinary case (scheduling jitter, one slow DNS lookup) while still failing
     * fast enough that a login does not hang. A caller that genuinely cannot tolerate an early
     * abandon should compute its own bound from {@link #overallTimeoutMsFor(int)} and double the
     * per-attempt term.
     */
    public static final long JOIN_SLACK_MS = 1000L;

    private final String baseUrl;
    private final String guildId;
    private final String apiKey;
    private final String tokenId;
    private final String serverId;
    private final int timeoutMs;
    private final int retries;
    private final int retryDelayMs;

    private ApiSettings(Builder builder) {
        this.baseUrl = stripTrailingSlash(Strings.trimToEmpty(builder.baseUrl));
        this.guildId = Strings.trimToEmpty(builder.guildId);
        this.apiKey = Strings.trimToEmpty(builder.apiKey);
        this.tokenId = Strings.trimToEmpty(builder.tokenId);
        this.serverId = Strings.trimToEmpty(builder.serverId);
        this.timeoutMs = Math.max(MIN_TIMEOUT_MS, builder.timeoutMs);
        this.retries = Math.max(1, builder.retries);
        this.retryDelayMs = Math.max(0, builder.retryDelayMs);
    }

    public static Builder builder() {
        return new Builder();
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

    /**
     * Which of the guild's Minecraft tokens this one is, or {@code ""} when there is no id for it.
     *
     * <p>Sent as {@code X-Token-Id} on {@code POST /api/minecraft/identify} and on nothing else: a
     * guild-scoped route already names the guild, so the header would be redundant there. It is
     * optional by design — a token issued before the field existed still has to be able to resolve
     * its guild, and the signature alone is what actually authenticates the call.
     */
    public String tokenId() {
        return tokenId;
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
     * Worst-case wall clock for one logical request on the <strong>login path</strong>, including
     * its whole retry sequence.
     *
     * <p>{@code retries} attempts of {@link #timeoutMs()} each, plus {@code retries - 1} delays
     * between them. v2's formula, unchanged: its login path waited {@code timeout + 1000}ms
     * instead, which with three retries abandoned the request four seconds before the retry loop
     * had finished legitimately working on it (issue #797 / MC-6).
     *
     * <p><strong>This is not the budget for every endpoint.</strong> {@link #whitelistSyncTimeoutMs()}
     * and {@link #updateCheckTimeoutMs()} raise the per-attempt timeout well above
     * {@link #timeoutMs()}, so bounding a whitelist-sync wait on this number abandons the request
     * around thirty seconds early at the defaults — the same bug as #797, on a different endpoint.
     * Use {@link #whitelistSyncBudgetMs()}, {@link #updateCheckBudgetMs()}, or
     * {@link #overallTimeoutMsFor(int)}.
     */
    public long overallTimeoutMs() {
        return overallTimeoutMsFor(timeoutMs);
    }

    /**
     * The same budget for an endpoint whose per-attempt timeout is not {@link #timeoutMs()}.
     *
     * @param perAttemptTimeoutMs the connect/read timeout each attempt is given
     */
    public long overallTimeoutMsFor(int perAttemptTimeoutMs) {
        return (long) retries * Math.max(0, perAttemptTimeoutMs)
                + (long) Math.max(0, retries - 1) * retryDelayMs;
    }

    /** Retry-inclusive budget for {@code GET whitelist/sync}. */
    public long whitelistSyncBudgetMs() {
        return overallTimeoutMsFor(whitelistSyncTimeoutMs());
    }

    /** Retry-inclusive budget for {@code GET plugin/latest}. */
    public long updateCheckBudgetMs() {
        return overallTimeoutMsFor(updateCheckTimeoutMs());
    }

    /**
     * How long to actually wait on a login-path future: the budget plus {@link #JOIN_SLACK_MS}.
     *
     * <p>Prefer a {@code joinTimeout} over a bare budget whenever blocking on a future.
     */
    public long joinTimeoutMs() {
        return overallTimeoutMs() + JOIN_SLACK_MS;
    }

    /** How long to actually wait on a {@code whitelist/sync} future. */
    public long whitelistSyncJoinTimeoutMs() {
        return whitelistSyncBudgetMs() + JOIN_SLACK_MS;
    }

    /** How long to actually wait on a {@code plugin/latest} future. */
    public long updateCheckJoinTimeoutMs() {
        return updateCheckBudgetMs() + JOIN_SLACK_MS;
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
                .tokenId(tokenId)
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
        private String tokenId = "";
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

        public Builder tokenId(String value) {
            this.tokenId = value;
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
