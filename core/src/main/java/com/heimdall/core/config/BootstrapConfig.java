package com.heimdall.core.config;

import com.heimdall.core.util.Strings;

/**
 * The contents of {@code bootstrap.yml}: how to reach the bot, how to prove who we are, and the few
 * operational knobs that have to work when the dashboard cannot be reached.
 *
 * <p><strong>Almost nothing else belongs in this file.</strong> Every setting the dashboard can own —
 * messages, cache windows, which modules are on, role-sync groups, offense templates — arrives over
 * the tunnel as remote config (departure D17). The exceptions are the handful of fields below the
 * credentials, and they earn their place by a single test: <em>can the dashboard actually deliver
 * this, and would a server in trouble still be able to read it?</em>
 *
 * <ul>
 *   <li>{@link #debug()} — the diagnostic an operator most needs when the tunnel is down.
 *   <li>{@link #timeoutMs()}, {@link #retries()}, {@link #retryDelayMs()} — how patient the login
 *       path is with the bot. The dashboard cannot own these: they shape the request that would
 *       <em>fetch</em> the dashboard's config, so a server that got them wrong could never load the
 *       settings that would fix them. A migrated v2 server also needs its own tuning preserved here,
 *       or its login budget balloons to v3's more generous defaults (departure D62).
 *   <li>{@link #updatesCheckEnabled()}, {@link #updatesNotifyAdmins()},
 *       {@link #updatesCheckIntervalHours()} — the self-updater's knobs. v2 kept these in
 *       {@code config.yml} and an operator could turn the check off; v3 has no {@code updates}
 *       capability, so the bot's {@code config.push} narrowing would drop an {@code updates} section
 *       before it ever reached the plugin. Local is the only place they can actually be controlled.
 * </ul>
 *
 * <p>If you are about to add a field here, the question to answer first is why the dashboard cannot
 * own it. "It cannot be delivered by the dashboard, and a broken server still has to read it" is the
 * only answer that passes.
 *
 * <p>Instances are immutable. {@link #toBuilder()} produces the mutable writer the setup flow uses;
 * {@link BootstrapStore} persists it.
 *
 * <p>{@link #toString()} redacts {@link #token()}. The token is a bearer credential for this
 * guild's Minecraft API, and a config object that renders itself into a log line is how those
 * escape.
 */
public final class BootstrapConfig {

    /** Default per-attempt login timeout, matching {@code ApiSettings.DEFAULT_TIMEOUT_MS}. */
    public static final int DEFAULT_TIMEOUT_MS = 5000;

    /**
     * Default total login attempts.
     *
     * <p><strong>Three, not one, and deliberately not v2's.</strong> v2 shipped {@code retries: 1}.
     * v3 chooses 3 for resilience — a single dropped packet should not refuse a player — and a
     * migration preserves whatever the operator actually had (departure D62), so a v2 server tuned
     * for a flaky link keeps its own value rather than inheriting this one.
     */
    public static final int DEFAULT_RETRIES = 3;

    /** Default pause between login attempts, matching {@code ApiSettings.DEFAULT_RETRY_DELAY_MS}. */
    public static final int DEFAULT_RETRY_DELAY_MS = 1000;

    /** Default self-update cadence, matching {@code UpdateSettings.DEFAULT_CHECK_INTERVAL_HOURS}. */
    public static final long DEFAULT_UPDATE_INTERVAL_HOURS = 12L;

    private final String endpoint;
    private final String tokenId;
    private final String token;
    private final String serverId;
    private final ServerRole role;
    private final boolean debug;

    private final int timeoutMs;
    private final int retries;
    private final int retryDelayMs;

    private final boolean updatesCheckEnabled;
    private final boolean updatesNotifyAdmins;
    private final long updatesCheckIntervalHours;

    /** Module ids an operator has switched off LOCALLY, space-separated. The offline escape hatch. */
    private final String disabledModules;

    /**
     * The guild this token resolved to, remembered from the last successful {@code identify}.
     *
     * <p><strong>A cache, not a setting.</strong> There is deliberately no guild id for an operator
     * to configure: the token already knows which guild it belongs to, and a hand-typed snowflake
     * that disagrees with it produces a server which signs perfectly and reads somebody else's
     * configuration. This field exists so a restart while the bot is unreachable can still dial the
     * tunnel it dialled yesterday, rather than sitting in the discovering state until the bot comes
     * back. It is overwritten by whatever {@code identify} next answers.
     */
    private final String guildId;

    private BootstrapConfig(Builder builder) {
        this.endpoint = stripTrailingSlash(Strings.trimToEmpty(builder.endpoint));
        this.tokenId = Strings.trimToEmpty(builder.tokenId);
        this.token = Strings.trimToEmpty(builder.token);
        this.serverId = Strings.trimToEmpty(builder.serverId);
        this.role = builder.role == null ? ServerRole.AUTO : builder.role;
        this.debug = builder.debug;
        this.timeoutMs = builder.timeoutMs;
        this.retries = builder.retries;
        this.retryDelayMs = builder.retryDelayMs;
        this.updatesCheckEnabled = builder.updatesCheckEnabled;
        this.updatesNotifyAdmins = builder.updatesNotifyAdmins;
        this.updatesCheckIntervalHours = builder.updatesCheckIntervalHours;
        this.disabledModules = Strings.trimToEmpty(builder.disabledModules);
        this.guildId = Strings.trimToEmpty(builder.guildId);
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
                .debug(debug)
                .timeoutMs(timeoutMs)
                .retries(retries)
                .retryDelayMs(retryDelayMs)
                .updatesCheckEnabled(updatesCheckEnabled)
                .updatesNotifyAdmins(updatesNotifyAdmins)
                .updatesCheckIntervalHours(updatesCheckIntervalHours)
                .disabledModules(disabledModules)
                .guildId(guildId);
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

    /** Per-attempt login timeout in ms. Preserved from v2 on migration; {@link #DEFAULT_TIMEOUT_MS} otherwise. */
    public int timeoutMs() {
        return timeoutMs;
    }

    /** Total login attempts including the first. Preserved from v2 on migration. */
    public int retries() {
        return retries;
    }

    /** Pause between login attempts, in ms. Preserved from v2 on migration. */
    public int retryDelayMs() {
        return retryDelayMs;
    }

    /** Whether the self-update check runs. The one place this can be turned off — see the class note. */
    public boolean updatesCheckEnabled() {
        return updatesCheckEnabled;
    }

    /** Whether an admin joining is told about a pending update. */
    public boolean updatesNotifyAdmins() {
        return updatesNotifyAdmins;
    }

    /** How often the self-update check runs, in hours. */
    public long updatesCheckIntervalHours() {
        return updatesCheckIntervalHours;
    }

    /**
     * Module ids switched off locally, space-separated, or {@code ""}.
     *
     * <p>The offline escape hatch (departure D66): a module named here stays off whatever the
     * dashboard's {@code config.push} says, and it stays off across a restart because it is on disk.
     * This is what {@code /hd disable} writes, and the one lever an operator has over a module when
     * the tunnel is dead — v2's global {@code /hwl disable}, made per-module and made local.
     */
    public String disabledModules() {
        return disabledModules;
    }

    /**
     * The last guild this server's token resolved to, or {@code ""} if it never has.
     *
     * <p>A cache of the answer to {@code POST /api/minecraft/identify} — see the field comment. Not
     * something an operator sets, and not part of {@link #isConfigured()}: a server with a token and
     * no cached guild is perfectly well configured, it just has one round trip to make first.
     */
    public String guildId() {
        return guildId;
    }

    /**
     * Whether this config carries enough to talk to the bot at all.
     *
     * <p>{@code false} means the setup flow has not run: no endpoint, or no token. It is not
     * a claim that the credentials are <em>valid</em> — only the bot can say that.
     *
     * <p><strong>{@link #tokenId()} is deliberately not required</strong>, and getting that wrong
     * cost a phase. The signature is what authenticates: the token id is a hint that rides along on
     * {@code identify} so the bot can look the secret up faster, and it is optional on the wire by
     * design — a token issued before the field existed still has to work. Requiring it here made
     * every server migrated from v2 permanently "not set up": v2 had one key and no id for it, so
     * the HTTP client worked, the guild resolved, and the tunnel never dialled, with the plugin
     * cheerfully advising the operator to run a setup command they had already completed.
     *
     * <p>Caught by the migration row of {@code smoke/connected.sh} on its first run, which is
     * exactly the class of thing a unit test could not have noticed — every unit test built a
     * config with all three fields, because that is what a claim returns.
     */
    public boolean isConfigured() {
        return Strings.isNotBlank(endpoint) && Strings.isNotBlank(token);
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
                && disabledModules.equals(that.disabledModules)
                && timeoutMs == that.timeoutMs
                && retries == that.retries
                && retryDelayMs == that.retryDelayMs
                && updatesCheckEnabled == that.updatesCheckEnabled
                && updatesNotifyAdmins == that.updatesNotifyAdmins
                && updatesCheckIntervalHours == that.updatesCheckIntervalHours
                && role == that.role
                && endpoint.equals(that.endpoint)
                && tokenId.equals(that.tokenId)
                && token.equals(that.token)
                && serverId.equals(that.serverId)
                && guildId.equals(that.guildId);
    }

    @Override
    public int hashCode() {
        int result = endpoint.hashCode();
        result = 31 * result + tokenId.hashCode();
        result = 31 * result + token.hashCode();
        result = 31 * result + serverId.hashCode();
        result = 31 * result + role.hashCode();
        result = 31 * result + (debug ? 1 : 0);
        result = 31 * result + timeoutMs;
        result = 31 * result + retries;
        result = 31 * result + retryDelayMs;
        result = 31 * result + (updatesCheckEnabled ? 1 : 0);
        result = 31 * result + (updatesNotifyAdmins ? 1 : 0);
        result = 31 * result + (int) (updatesCheckIntervalHours ^ (updatesCheckIntervalHours >>> 32));
        result = 31 * result + disabledModules.hashCode();
        result = 31 * result + guildId.hashCode();
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
                + ", guildId='" + guildId
                + "', debug=" + debug
                + ", timeoutMs=" + timeoutMs
                + ", retries=" + retries
                + ", retryDelayMs=" + retryDelayMs
                + ", updatesCheckEnabled=" + updatesCheckEnabled
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
        private int timeoutMs = DEFAULT_TIMEOUT_MS;
        private int retries = DEFAULT_RETRIES;
        private int retryDelayMs = DEFAULT_RETRY_DELAY_MS;
        private boolean updatesCheckEnabled = true;
        private boolean updatesNotifyAdmins = true;
        private long updatesCheckIntervalHours = DEFAULT_UPDATE_INTERVAL_HOURS;
        private String disabledModules = "";
        private String guildId = "";

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

        public Builder timeoutMs(int value) {
            this.timeoutMs = value;
            return this;
        }

        public Builder retries(int value) {
            this.retries = value;
            return this;
        }

        public Builder retryDelayMs(int value) {
            this.retryDelayMs = value;
            return this;
        }

        public Builder updatesCheckEnabled(boolean value) {
            this.updatesCheckEnabled = value;
            return this;
        }

        public Builder updatesNotifyAdmins(boolean value) {
            this.updatesNotifyAdmins = value;
            return this;
        }

        public Builder updatesCheckIntervalHours(long value) {
            this.updatesCheckIntervalHours = value;
            return this;
        }

        /** Space-separated module ids to keep off locally. */
        public Builder disabledModules(String value) {
            this.disabledModules = value;
            return this;
        }

        /** The resolved guild, cached from {@code identify}. Not an operator-facing setting. */
        public Builder guildId(String value) {
            this.guildId = value;
            return this;
        }

        public BootstrapConfig build() {
            return new BootstrapConfig(this);
        }
    }
}
