package com.heimdall.whitelist.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Manages cached offense types and provides tab-completion data.
 * Refreshes from the bot API periodically.
 */
public class OffenseManager {

  private final PluginLogger logger;
  private final ApiClient apiClient;
  private final List<OffenseTypeInfo> cachedTypes = new CopyOnWriteArrayList<>();
  private volatile long lastFetchTime = 0;

  public OffenseManager(PluginLogger logger, ApiClient apiClient) {
    this.logger = logger;
    this.apiClient = apiClient;
  }

  /**
   * Refresh the cached offense types from the API.
   * Call this on plugin enable, on reload, and periodically.
   */
  public void refresh() {
    try {
      List<OffenseTypeInfo> types = apiClient.getOffenseTypes().join();
      cachedTypes.clear();
      cachedTypes.addAll(types);
      lastFetchTime = System.currentTimeMillis();

      int slugCount = types.stream().mapToInt(t -> t.getOffenses().size()).sum();
      logger.info("Loaded " + types.size() + " offense types (" + slugCount + " offense slugs)");
    } catch (Exception e) {
      logger.warning("Failed to refresh offense types: " + e.getMessage());
    }
  }

  /**
   * Get all cached offense types.
   */
  public List<OffenseTypeInfo> getTypes() {
    return Collections.unmodifiableList(cachedTypes);
  }

  /**
   * Get all offense slugs from all enabled types (for tab-completion).
   */
  public List<String> getAllOffenseSlugs() {
    List<String> slugs = new ArrayList<>();
    for (OffenseTypeInfo type : cachedTypes) {
      if (type.isEnabled()) {
        slugs.addAll(type.getOffenses());
      }
    }
    return slugs;
  }

  /**
   * Find the offense type that contains the given slug.
   */
  public OffenseTypeInfo findTypeBySlug(String slug) {
    String lower = slug.toLowerCase();
    for (OffenseTypeInfo type : cachedTypes) {
      if (type.isEnabled() && type.getOffenses().contains(lower)) {
        return type;
      }
    }
    return null;
  }

  /**
   * Get slugs that start with the given prefix (for tab-completion).
   */
  public List<String> getMatchingSlugs(String prefix) {
    String lower = prefix.toLowerCase();
    return getAllOffenseSlugs().stream()
        .filter(s -> s.startsWith(lower))
        .sorted()
        .collect(Collectors.toList());
  }

  public long getLastFetchTime() {
    return lastFetchTime;
  }

  public boolean hasCachedData() {
    return !cachedTypes.isEmpty();
  }
}
