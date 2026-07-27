package com.heimdall.module.whitelist;

import com.heimdall.core.json.Payload;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * The module's dashboard settings, with v2's defaults behind every one of them.
 *
 * <h2>A view, not a snapshot</h2>
 *
 * <p>Constructed around whatever {@code ModuleContext.settings()} currently answers, and constructed
 * <em>at the point of use</em> rather than held in a field. A settings change does not re-enable a
 * module, so a value read in {@code enable()} is permanently stale after the first dashboard edit —
 * which is the failure the {@code ModuleContext} javadoc warns about, and the one an operator
 * reports as "I saved it and nothing happened".
 *
 * <p>Every default here is v2's shipped {@code config.yml} value, not v2's code default, where the
 * two differ. {@link #apiFallbackMode()} is the one place they do, and it is called out below.
 *
 * <p>Immutable and cheap: reading a {@link Payload} key is a map lookup.
 */
final class WhitelistSettings {

    /** v2: {@code cache.cacheWindow: 60}. */
    static final long DEFAULT_CACHE_WINDOW_MINUTES = 60L;

    /** v2: {@code cache.extendOnJoin: 120}. */
    static final long DEFAULT_EXTEND_ON_JOIN_MINUTES = 120L;

    /** v2: {@code cache.extendOnLeave: 180}. */
    static final long DEFAULT_EXTEND_ON_LEAVE_MINUTES = 180L;

    /** v2: {@code cache.maxExtensionHours: 24}. The #771 ceiling; 0 disables it. */
    static final long DEFAULT_MAX_EXTENSION_HOURS = 24L;

    /** v2: {@code cache.prewarm.intervalMinutes: 5}, clamped to a floor of 1. */
    static final long DEFAULT_PREWARM_INTERVAL_MINUTES = 5L;

    /** v2: {@code cache.cleanupInterval: 30}. */
    static final long DEFAULT_CLEANUP_INTERVAL_MINUTES = 30L;

    private final Payload settings;

    WhitelistSettings(Payload settings) {
        this.settings = settings == null ? Payload.empty() : settings;
    }

    /**
     * How long a verified decision is trusted before the bot is asked again.
     *
     * <p>Baked into the {@code MirrorPolicy} when the mirror is opened, so a change to this one does
     * not take effect until the module is next enabled — see the note on
     * {@link HeimdallWhitelistModule}.
     */
    long cacheWindowMs() {
        return TimeUnit.MINUTES.toMillis(
                positive(settings.longValue("cacheWindow", DEFAULT_CACHE_WINDOW_MINUTES),
                        DEFAULT_CACHE_WINDOW_MINUTES));
    }

    /** How far a join slides a mirror entry's expiry. Read live, per event. */
    long extendOnJoinMs() {
        return TimeUnit.MINUTES.toMillis(
                positive(settings.longValue("extendOnJoin", DEFAULT_EXTEND_ON_JOIN_MINUTES),
                        DEFAULT_EXTEND_ON_JOIN_MINUTES));
    }

    /** How far a quit slides it. Read live, per event. */
    long extendOnLeaveMs() {
        return TimeUnit.MINUTES.toMillis(
                positive(settings.longValue("extendOnLeave", DEFAULT_EXTEND_ON_LEAVE_MINUTES),
                        DEFAULT_EXTEND_ON_LEAVE_MINUTES));
    }

    /**
     * The hard ceiling on extension past the last real verification, in hours. {@code 0} disables it.
     *
     * <p>Issue #771: without it, a player removed from the Discord whitelist keeps access
     * indefinitely by rejoining once per extension window. Zero is a supported answer and v2 said so
     * too, loudly, as "NOT recommended".
     */
    long maxExtensionHours() {
        return Math.max(0L, settings.longValue("maxExtensionHours", DEFAULT_MAX_EXTENSION_HOURS));
    }

    /** Whether the full-whitelist pre-warm poll runs at all. v2: {@code cache.prewarm.enabled}. */
    boolean prewarmEnabled() {
        return settings.bool("prewarmEnabled", true);
    }

    /**
     * How often the pre-warm poll runs, in minutes, with a floor of 1.
     *
     * <p>The floor is v2's and it is not cosmetic: this is a full-whitelist dump, and a
     * dashboard-supplied zero would turn it into a hot loop against the bot.
     */
    long prewarmIntervalMinutes() {
        return Math.max(1L, settings.longValue(
                "prewarmIntervalMinutes", DEFAULT_PREWARM_INTERVAL_MINUTES));
    }

    /** How often expired entries are swept out of the mirror, in minutes. Floor of 1. */
    long cleanupIntervalMinutes() {
        return Math.max(1L, settings.longValue(
                "cleanupIntervalMinutes", DEFAULT_CLEANUP_INTERVAL_MINUTES));
    }

    /**
     * What to do when the bot cannot be reached.
     *
     * <p>Defaults to {@link FallbackMode#WHITELIST_ONLY}, which is what v2's shipped
     * {@code config.yml} carried. v2's <em>code</em> defaulted to {@code deny} — the two disagreed,
     * and every real installation ran on the file's value, so the file's value is the parity one.
     * Defaulting to deny would also mean a fresh install fails closed the first time the bot
     * hiccups, which is a locked server caused by a default nobody chose.
     */
    FallbackMode apiFallbackMode() {
        return FallbackMode.parse(settings.string("apiFallbackMode", ""));
    }

    /**
     * UUIDs that skip the whitelist check entirely.
     *
     * <p>A UUID list rather than a permission, because permissions are not attached during
     * {@code AsyncPlayerPreLoginEvent} — the player object does not exist yet — so
     * {@code heimdall.bypass} cannot gate a login however much an operator expects it to. Issue
     * #796 / MC-2. Matching is case-insensitive and trimmed; see {@code BypassList}.
     */
    List<String> bypassUuids() {
        return settings.strings("bypassUuids");
    }

    /**
     * Whether a backend behind a proxy enforces the whitelist itself.
     *
     * <p>Defaults to {@code true}, which is v2's behaviour: v2 had no role concept and every server
     * it was installed on ran the check. Leaving it on also covers the deployment that actually
     * breaks — a backend whose proxy does not have the plugin, where turning enforcement off would
     * open the server to anyone who can reach its port directly.
     *
     * <p>Only consulted on an {@code ENFORCER}. A gatekeeper and a standalone server always enforce.
     */
    boolean enforceOnBackend() {
        return settings.bool("enforceOnBackend", true);
    }

    /** Shown when the bot cannot be reached and the player is refused. */
    String apiUnavailableMessage() {
        return settings.string("apiUnavailableMessage",
                "&cWhitelist system is temporarily unavailable. Please try again later.");
    }

    /** Shown after joining when the bot could not be reached and the player was let in anyway. */
    String apiUnavailableAllowedMessage() {
        return settings.string("apiUnavailableAllowedMessage",
                "&eWhitelist API is temporarily unavailable. You have been allowed in from cache.");
    }

    private static long positive(long value, long fallback) {
        return value > 0 ? value : fallback;
    }

    /** What happens to a login when the bot is unreachable. */
    enum FallbackMode {

        /** Fail open. Everyone gets in. */
        ALLOW,

        /**
         * Serve the mirror: a player it holds gets in, anybody else is refused.
         *
         * <p>The default, and the reason the pre-warm poll exists at all — with a mirror of the
         * whole whitelist, a bot redeploy is invisible to every whitelisted player rather than to
         * the handful who happened to connect recently.
         */
        WHITELIST_ONLY,

        /** Fail closed. Nobody gets in. */
        DENY;

        static FallbackMode parse(String raw) {
            if (raw == null) {
                return WHITELIST_ONLY;
            }
            String normalised = raw.trim().toLowerCase(Locale.ROOT);
            if ("allow".equals(normalised)) {
                return ALLOW;
            }
            if ("deny".equals(normalised)) {
                return DENY;
            }
            // Everything else, including "whitelist-only", the empty string and a typo. Falling
            // back to the middle option on a value nobody recognises is the right failure: fail-open
            // would silently disable the whitelist and fail-closed would silently lock the server.
            return WHITELIST_ONLY;
        }
    }
}
