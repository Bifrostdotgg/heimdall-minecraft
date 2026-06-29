package com.heimdall.whitelist.core;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Platform-agnostic whitelist cache.
 * Caches whitelist decisions to reduce API calls.
 */
public class WhitelistCache {
  private final PluginLogger logger;
  private final File cacheFile;
  private final Map<String, CacheEntry> cache;
  private final Gson gson;
  private final long cacheWindowMs;
  private final long extendOnJoinMs;
  private final long extendOnLeaveMs;
  /**
   * Hard ceiling on how far past the last successful API verification the cache
   * may serve a positive result. Join/leave extensions slide the expiry forward,
   * but never beyond {@code lastVerified + maxExtensionMs}. Without this bound a
   * player removed from the Discord whitelist keeps access indefinitely as long
   * as they rejoin once per extension window (issue #771). 0 or negative disables
   * the bound (legacy unbounded behaviour).
   */
  private final long maxExtensionMs;
  private final boolean extensionBounded;

  public WhitelistCache(PluginLogger logger, File dataFolder, long cacheWindowMinutes, long extendOnJoinMinutes,
      long extendOnLeaveMinutes, long maxExtensionHours) {
    this.logger = logger;
    this.cacheFile = new File(dataFolder, "whitelist-cache.json");
    this.cache = new ConcurrentHashMap<>();
    this.gson = new Gson();
    this.cacheWindowMs = cacheWindowMinutes * 60 * 1000;
    this.extendOnJoinMs = extendOnJoinMinutes * 60 * 1000;
    this.extendOnLeaveMs = extendOnLeaveMinutes * 60 * 1000;
    this.extensionBounded = maxExtensionHours > 0;
    this.maxExtensionMs = this.extensionBounded ? maxExtensionHours * 60 * 60 * 1000 : 0;

    loadCache();
  }

  public static class CacheEntry {
    public String username;
    public String uuid;
    public long lastConnection;
    public long cacheExpiry;
    public boolean whitelisted;
    /**
     * Timestamp (epoch ms) of the last successful API verification for this
     * player. Only set when the API confirmed the player should be whitelisted —
     * never advanced by a cache-hit join/leave. Caps how far the expiry may
     * slide (see {@link WhitelistCache#maxExtensionMs}). Entries persisted before
     * this field existed deserialize to 0, which forces a re-verification on the
     * next join.
     */
    public long lastVerified;

    public CacheEntry() {
    }

    public CacheEntry(String username, String uuid, long lastConnection, long cacheExpiry, boolean whitelisted,
        long lastVerified) {
      this.username = username;
      this.uuid = uuid;
      this.lastConnection = lastConnection;
      this.cacheExpiry = cacheExpiry;
      this.whitelisted = whitelisted;
      this.lastVerified = lastVerified;
    }
  }

  /**
   * The effective expiry for an entry: the persisted {@code cacheExpiry}, clamped
   * so it can never exceed {@code lastVerified + maxExtensionMs} when the bound is
   * enabled. This is the authoritative read-side enforcement — even if some path
   * wrote a too-far expiry, or the entry predates the bound, a positive result is
   * never served past the ceiling.
   */
  private long effectiveExpiry(CacheEntry entry) {
    if (!extensionBounded) {
      return entry.cacheExpiry;
    }
    long ceiling = entry.lastVerified + maxExtensionMs;
    return Math.min(entry.cacheExpiry, ceiling);
  }

  /**
   * Clamp a proposed new expiry to the max-extension ceiling for an entry. Used at
   * write time (join/leave extension) so the persisted value never claims a longer
   * lifetime than the bound allows.
   */
  private long capExpiry(CacheEntry entry, long proposedExpiry) {
    if (!extensionBounded) {
      return proposedExpiry;
    }
    return Math.min(proposedExpiry, entry.lastVerified + maxExtensionMs);
  }

  /**
   * Check if a player is cached and whitelisted
   * Note: We only cache positive results, so null means either not cached or not
   * whitelisted
   *
   * @param uuid     Player's UUID
   * @param username Player's username
   * @return true if cached and whitelisted, null if not cached or cache expired
   */
  public Boolean isCachedWhitelisted(String uuid, String username) {
    // If UUID is null, we can't check the cache (which is keyed by UUID)
    if (uuid == null) {
      return null;
    }

    CacheEntry entry = cache.get(uuid);
    if (entry == null) {
      return null; // Not cached
    }

    long now = System.currentTimeMillis();
    if (now > effectiveExpiry(entry)) {
      // Cache expired (or past the max-extension ceiling) — remove and force a
      // fresh API verification on the next attempt.
      cache.remove(uuid);
      saveCache();
      return null;
    }

    // Update username if it changed
    if (!entry.username.equals(username)) {
      entry.username = username;
      saveCache();
    }

    // We only cache positive results, so if it's in cache, it's whitelisted
    return entry.whitelisted ? true : null;
  }

  /**
   * Add a whitelisted player to the cache
   * Note: We only cache positive results to allow newly whitelisted players to
   * join immediately
   *
   * @param uuid     Player's UUID
   * @param username Player's username
   */
  public void addWhitelistedPlayer(String uuid, String username) {
    // If UUID is null, we can't cache (cache is keyed by UUID)
    if (uuid == null) {
      logger.warning("Cannot cache whitelisted player with null UUID: " + username);
      return;
    }

    long now = System.currentTimeMillis();
    // This is a fresh, successful API verification — record it as lastVerified so
    // the max-extension ceiling is measured from now.
    CacheEntry entry = new CacheEntry(
        username,
        uuid,
        now,
        now + cacheWindowMs,
        true,
        now);
    cache.put(uuid, entry);
    saveCache();

    logger.info("Added whitelisted player to cache: " + username + " (" + uuid + ")" +
        ", expires in " + (cacheWindowMs / 60000) + " minutes");
  }

  /**
   * Extend cache for a player who joined (they were allowed, so extend their
   * cache)
   *
   * @param uuid     Player's UUID
   * @param username Player's username
   */
  public void extendCacheOnJoin(String uuid, String username) {
    // If UUID is null, we can't extend cache (cache is keyed by UUID)
    if (uuid == null) {
      return;
    }

    CacheEntry entry = cache.get(uuid);
    if (entry != null && entry.whitelisted) {
      long now = System.currentTimeMillis();
      entry.lastConnection = now;
      // Slide forward, but never past the max-extension ceiling measured from the
      // last real API verification. Bounding here keeps the persisted value honest;
      // effectiveExpiry() enforces it authoritatively on read.
      entry.cacheExpiry = capExpiry(entry, now + extendOnJoinMs);
      entry.username = username; // Update username in case it changed
      saveCache();

      logger.info("Extended cache on join for " + username + " (" + uuid + "), expires in " +
          ((entry.cacheExpiry - now) / 60000) + " minutes");
    }
  }

  /**
   * Extend cache for a player who left (they were clearly allowed, so extend
   * their cache)
   *
   * @param uuid     Player's UUID
   * @param username Player's username
   */
  public void extendCacheOnLeave(String uuid, String username) {
    // If UUID is null, we can't extend cache (cache is keyed by UUID)
    if (uuid == null) {
      return;
    }

    CacheEntry entry = cache.get(uuid);
    if (entry != null && entry.whitelisted) {
      long now = System.currentTimeMillis();
      // Slide forward, but never past the max-extension ceiling (see extendCacheOnJoin).
      entry.cacheExpiry = capExpiry(entry, now + extendOnLeaveMs);
      entry.username = username; // Update username in case it changed
      saveCache();

      logger.info("Extended cache on leave for " + username + " (" + uuid + "), expires in " +
          ((entry.cacheExpiry - now) / 60000) + " minutes");
    }
  }

  /**
   * Clean up expired cache entries
   */
  public void cleanupExpiredEntries() {
    long now = System.currentTimeMillis();
    int removedCount = 0;

    for (Map.Entry<String, CacheEntry> entry : cache.entrySet()) {
      if (now > effectiveExpiry(entry.getValue())) {
        cache.remove(entry.getKey());
        removedCount++;
      }
    }

    if (removedCount > 0) {
      saveCache();
      logger.info("Cleaned up " + removedCount + " expired cache entries");
    }
  }

  /**
   * Get cache statistics
   *
   * @return Cache stats string
   */
  public String getCacheStats() {
    int totalEntries = cache.size();
    int whitelistedEntries = 0;
    long now = System.currentTimeMillis();
    int expiredEntries = 0;

    for (CacheEntry entry : cache.values()) {
      if (entry.whitelisted) {
        whitelistedEntries++;
      }
      if (now > effectiveExpiry(entry)) {
        expiredEntries++;
      }
    }

    return "Total: " + totalEntries + ", Whitelisted: " + whitelistedEntries + ", Expired: " + expiredEntries;
  }

  /**
   * Clear the cache
   */
  public void clear() {
    cache.clear();
    saveCache();
    logger.info("Whitelist cache cleared");
  }

  /**
   * Load cache from file
   */
  private void loadCache() {
    if (!cacheFile.exists()) {
      return;
    }

    try (FileReader reader = new FileReader(cacheFile)) {
      Type type = new TypeToken<Map<String, CacheEntry>>() {
      }.getType();
      Map<String, CacheEntry> loadedCache = gson.fromJson(reader, type);
      if (loadedCache != null) {
        cache.putAll(loadedCache);
        logger.info("Loaded " + cache.size() + " cache entries from file");
      }
    } catch (IOException e) {
      logger.warning("Failed to load cache from file: " + e.getMessage());
    }
  }

  /**
   * Save cache to file
   */
  private void saveCache() {
    try {
      // Ensure parent directory exists
      if (!cacheFile.getParentFile().exists()) {
        cacheFile.getParentFile().mkdirs();
      }

      try (FileWriter writer = new FileWriter(cacheFile)) {
        gson.toJson(cache, writer);
      }
    } catch (IOException e) {
      logger.warning("Failed to save cache to file: " + e.getMessage());
    }
  }

  /**
   * Shutdown the cache (save to file)
   */
  public void shutdown() {
    saveCache();
  }
}
