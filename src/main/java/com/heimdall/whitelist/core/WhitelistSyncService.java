package com.heimdall.whitelist.core;

import java.util.List;

/**
 * Platform-agnostic whitelist pre-warm sync.
 *
 * <p>Periodically pulls the FULL set of currently-whitelisted players from the
 * bot and reconciles it into the {@link WhitelistCache}. This is what makes a
 * brief bot outage (a Coolify redeploy, a crash, a network blip) invisible to
 * EVERY whitelisted player: with {@code apiFallbackMode: whitelist-only}, the
 * login path serves cached players when the bot is unreachable, and pre-warming
 * keeps the cache a complete mirror of the whitelist — not just the handful of
 * players who happened to connect recently.
 *
 * <p>All network work is blocking — the platform layer must run {@link #syncNow}
 * off the main server/proxy thread. Errors are swallowed and logged: a failed
 * sync simply leaves the existing cache untouched (never wiped), so resilience
 * degrades gracefully rather than failing closed.
 */
public class WhitelistSyncService {

  private final PluginLogger logger;
  private final ConfigProvider config;
  private final ApiClient apiClient;
  private final WhitelistCache cache;

  public WhitelistSyncService(PluginLogger logger, ConfigProvider config, ApiClient apiClient,
      WhitelistCache cache) {
    this.logger = logger;
    this.config = config;
    this.apiClient = apiClient;
    this.cache = cache;
  }

  /**
   * Whether pre-warming should run. Requires both the cache itself and the
   * pre-warm feature to be enabled — there is nothing to warm if the cache is off.
   */
  public boolean isEnabled() {
    return config.getBoolean("cache.enabled", true)
        && config.getBoolean("cache.prewarm.enabled", true);
  }

  /** Configured sync interval in minutes (clamped to a sane floor of 1). */
  public long getIntervalMinutes() {
    return Math.max(1, config.getLong("cache.prewarm.intervalMinutes", 5));
  }

  /**
   * Fetch the full whitelist and reconcile it into the cache. Blocking — run
   * off-thread. Never throws: on any failure the cache is left exactly as it was
   * (so a transient bot outage does not erase the very entries that protect
   * against it).
   */
  public void syncNow() {
    if (!isEnabled()) {
      return;
    }

    String guildId = apiClient.getGuildId();
    if (guildId == null || guildId.isBlank()) {
      logger.debug("[WhitelistSync] Skipped — guild ID not resolved yet.");
      return;
    }

    try {
      List<WhitelistSyncEntry> entries = apiClient.fetchWhitelistSync().join();
      cache.reconcileFromSync(entries);
    } catch (Exception e) {
      logger.warning("[WhitelistSync] Pre-warm sync failed (cache left unchanged): " + rootMessage(e));
    }
  }

  private static String rootMessage(Throwable t) {
    Throwable current = t;
    String message = t.getMessage();
    while (current.getCause() != null) {
      current = current.getCause();
      if (current.getMessage() != null) {
        message = current.getMessage();
      }
    }
    return message != null ? message : t.getClass().getSimpleName();
  }
}
