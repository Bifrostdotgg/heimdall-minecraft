package com.heimdall.whitelist.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whitelist revocation bound — issue #771.
 *
 * Pins the max-extension ceiling: join/leave extensions may slide a positive
 * cache entry forward, but never past {@code lastVerified + maxExtensionHours}.
 * Without the bound a removed-from-Discord player kept server access forever by
 * rejoining once per extension window.
 */
class WhitelistCacheTest {

  private static final String UUID = "11111111-2222-3333-4444-555555555555";
  private static final String USER = "Steve";

  private static final PluginLogger NOOP_LOGGER = new PluginLogger() {
    @Override
    public void info(String message) {
    }

    @Override
    public void warning(String message) {
    }

    @Override
    public void severe(String message) {
    }

    @Override
    public void debug(String message) {
    }
  };

  /** cacheWindow=60m, extendOnJoin=120m, extendOnLeave=180m, maxExtension=Nh. */
  private WhitelistCache newCache(File dir, long maxExtensionHours) {
    return new WhitelistCache(NOOP_LOGGER, dir, 60, 120, 180, maxExtensionHours);
  }

  private File cacheFile(Path dir) {
    return new File(dir.toFile(), "whitelist-cache.json");
  }

  private Map<String, WhitelistCache.CacheEntry> readRaw(Path dir) throws Exception {
    Type type = new TypeToken<Map<String, WhitelistCache.CacheEntry>>() {
    }.getType();
    try (FileReader reader = new FileReader(cacheFile(dir))) {
      return new Gson().fromJson(reader, type);
    }
  }

  @Test
  void freshVerificationIsServedWithinWindow(@TempDir Path dir) {
    WhitelistCache cache = newCache(dir.toFile(), 24);
    cache.addWhitelistedPlayer(UUID, USER);
    assertEquals(Boolean.TRUE, cache.isCachedWhitelisted(UUID, USER));
  }

  @Test
  void extensionsNeverSlideBeyondTheCeiling(@TempDir Path dir) throws Exception {
    // Tiny ceiling: 1 hour past verification. extendOnLeave (180m) would push
    // 3h out, but the bound must clamp it to lastVerified + 1h.
    WhitelistCache cache = newCache(dir.toFile(), 1);
    long beforeAdd = System.currentTimeMillis();
    cache.addWhitelistedPlayer(UUID, USER);

    cache.extendCacheOnJoin(UUID, USER);
    cache.extendCacheOnLeave(UUID, USER);

    WhitelistCache.CacheEntry entry = readRaw(dir).get(UUID);
    assertNotNull(entry);
    long ceiling = entry.lastVerified + 60L * 60L * 1000L; // +1h
    assertTrue(entry.lastVerified >= beforeAdd, "lastVerified should be set at add time");
    assertTrue(entry.cacheExpiry <= ceiling,
        "extended expiry " + entry.cacheExpiry + " must not exceed ceiling " + ceiling);
  }

  @Test
  void zeroDisablesTheBound_legacyUnboundedSlide(@TempDir Path dir) throws Exception {
    WhitelistCache cache = newCache(dir.toFile(), 0);
    cache.addWhitelistedPlayer(UUID, USER);
    cache.extendCacheOnLeave(UUID, USER); // 180m slide, unbounded

    WhitelistCache.CacheEntry entry = readRaw(dir).get(UUID);
    long expectedAtLeast = System.currentTimeMillis() + 170L * 60L * 1000L; // ~180m, allow slack
    assertTrue(entry.cacheExpiry >= expectedAtLeast,
        "with bound disabled the leave extension should slide the full 180m");
  }

  /**
   * The core revocation scenario: a player was verified a long time ago, then
   * keeps rejoining. A persisted entry whose cacheExpiry is far in the future but
   * whose lastVerified is old must be treated as expired (ceiling exceeded) and
   * forced to re-verify — even though cacheExpiry alone says "still valid".
   */
  @Test
  void staleVerificationPastCeilingForcesReverify(@TempDir Path dir) throws Exception {
    long now = System.currentTimeMillis();
    long longAgo = now - 48L * 60L * 60L * 1000L; // verified 48h ago
    WhitelistCache.CacheEntry stale = new WhitelistCache.CacheEntry(
        USER, UUID, now, now + 24L * 60L * 60L * 1000L /* expiry 24h in the FUTURE */, true, longAgo);
    writeRaw(dir, Map.of(UUID, stale));

    // maxExtension = 6h; 48h since verification >> 6h ceiling.
    WhitelistCache cache = newCache(dir.toFile(), 6);
    assertNull(cache.isCachedWhitelisted(UUID, USER),
        "entry past the max-extension ceiling must not be served, despite a future cacheExpiry");
  }

  /**
   * Pre-upgrade entries deserialize with lastVerified == 0. Their ceiling
   * (0 + maxExtension) is far in the past, so they must force a re-verification
   * rather than be served on a stale persisted expiry across a restart.
   */
  @Test
  void legacyEntryWithoutLastVerifiedForcesReverify(@TempDir Path dir) throws Exception {
    long now = System.currentTimeMillis();
    // Simulate an old on-disk entry: no lastVerified field (defaults to 0),
    // cacheExpiry still in the future.
    String json = "{\"" + UUID + "\":{\"username\":\"" + USER + "\",\"uuid\":\"" + UUID
        + "\",\"lastConnection\":" + now + ",\"cacheExpiry\":" + (now + 3600_000) + ",\"whitelisted\":true}}";
    try (FileWriter w = new FileWriter(cacheFile(dir))) {
      w.write(json);
    }

    WhitelistCache cache = newCache(dir.toFile(), 24);
    assertNull(cache.isCachedWhitelisted(UUID, USER),
        "legacy entry with lastVerified=0 must force re-verification");
  }

  @Test
  void reVerificationAdvancesTheCeiling(@TempDir Path dir) throws Exception {
    WhitelistCache cache = newCache(dir.toFile(), 1);
    cache.addWhitelistedPlayer(UUID, USER);
    long firstVerified = readRaw(dir).get(UUID).lastVerified;

    // A fresh API verification (cache miss path calls addWhitelistedPlayer again)
    // resets lastVerified, granting a new full window.
    cache.addWhitelistedPlayer(UUID, USER);
    long secondVerified = readRaw(dir).get(UUID).lastVerified;

    assertTrue(secondVerified >= firstVerified, "re-verification must not move lastVerified backward");
    assertEquals(Boolean.TRUE, cache.isCachedWhitelisted(UUID, USER));
  }

  private void writeRaw(Path dir, Map<String, WhitelistCache.CacheEntry> entries) throws Exception {
    try (FileWriter w = new FileWriter(cacheFile(dir))) {
      new Gson().toJson(entries, w);
    }
  }
}
