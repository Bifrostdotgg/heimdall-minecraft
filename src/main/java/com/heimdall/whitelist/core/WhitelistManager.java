package com.heimdall.whitelist.core;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Platform-agnostic whitelist manager.
 * Handles whitelist checking logic and caching.
 */
public class WhitelistManager {

  private final PluginLogger logger;
  private final ConfigProvider config;
  private final ApiClient apiClient;
  private final ConcurrentHashMap<String, CachedResponse> responseCache;
  private final SimpleDateFormat dateFormat;
  private volatile String lastCheckTime;

  public WhitelistManager(PluginLogger logger, ConfigProvider config, ApiClient apiClient) {
    this.logger = logger;
    this.config = config;
    this.apiClient = apiClient;
    this.responseCache = new ConcurrentHashMap<>();
    this.dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    this.lastCheckTime = "Never";
  }

  public WhitelistResponse checkPlayerWhitelist(String username, String uuid, String ip) throws Exception {
    return checkPlayerWhitelist(username, uuid, ip, null, null, false);
  }

  public WhitelistResponse checkPlayerWhitelist(String username, String uuid, String ip,
      List<String> currentGroups, String serverIp, boolean currentlyWhitelisted) throws Exception {
    // Validate input parameters
    if (username == null || username.trim().isEmpty()) {
      throw new IllegalArgumentException("Username cannot be null or empty");
    }

    // Use UUID as primary cache key, fallback to username if UUID is null
    String cacheKey = uuid != null ? uuid : username.toLowerCase();

    // Check response cache first
    CachedResponse cached = responseCache.get(cacheKey);
    if (cached != null && !cached.isExpired()) {
      if (config.getBoolean("logging.debug", false)) {
        logger.debug("Using cached whitelist result for " + username + " (" + uuid + ")");
      }
      return cached.getResponse();
    }

    // Make API request
    try {
      WhitelistResponse response = apiClient.checkWhitelist(username, uuid, ip, currentGroups, serverIp,
          currentlyWhitelisted).get(
              apiClient.getOverallTimeoutMs() + 1000, // full retry budget + 1s buffer
              TimeUnit.MILLISECONDS);

      // Cache successful responses (when player is allowed)
      if (response.shouldBeWhitelisted()) {
        long cacheTimeout = config.getInt("performance.cacheTimeout", 30) * 1000L;
        responseCache.put(cacheKey, new CachedResponse(response, System.currentTimeMillis() + cacheTimeout));

        if (config.getBoolean("logging.debug", false)) {
          logger.debug("Cached successful whitelist result for " + username + " (" + uuid + ")");
        }
      } else {
        if (config.getBoolean("logging.debug", false)) {
          logger.debug("Not caching failed whitelist result for " + username + " (" + uuid + ")");
        }
      }

      // Update last check time
      lastCheckTime = dateFormat.format(new Date());

      return response;
    } catch (Exception e) {
      logger.warning("API request failed for " + username + ": " + e.getMessage());
      throw e;
    }
  }

  public String requestLinkCode(String username, String uuid) throws Exception {
    // Validate input parameters
    if (username == null || username.trim().isEmpty()) {
      throw new IllegalArgumentException("Username cannot be null or empty");
    }
    if (uuid == null || uuid.trim().isEmpty()) {
      throw new IllegalArgumentException("UUID cannot be null or empty");
    }

    // Make API request for link code
    try {
      WhitelistResponse response = apiClient.requestLinkCode(username, uuid).get(
          apiClient.getOverallTimeoutMs() + 1000, // full retry budget + 1s buffer
          TimeUnit.MILLISECONDS);

      // Update last check time
      lastCheckTime = dateFormat.format(new Date());

      // Extract auth code from response
      if (response.getAuthCode() != null && !response.getAuthCode().isEmpty()) {
        return response.getAuthCode();
      } else {
        throw new Exception("No auth code received from API");
      }

    } catch (ExecutionException e) {
      Throwable cause = e.getCause() != null ? e.getCause() : e;
      String errorMessage = extractThrowableMessage(cause);
      logger.severe("Failed to request link code for " + username + ": " + errorMessage);
      lastCheckTime = dateFormat.format(new Date()) + " (ERROR)";

      if (cause instanceof Exception causeException) {
        throw causeException;
      }

      throw new Exception(errorMessage, cause);
    } catch (Exception e) {
      logger.severe("Failed to request link code for " + username + ": " + extractThrowableMessage(e));
      lastCheckTime = dateFormat.format(new Date()) + " (ERROR)";
      throw e;
    }
  }

  private String extractThrowableMessage(Throwable throwable) {
    Throwable current = throwable;
    String message = null;

    while (current != null) {
      if (current.getMessage() != null && !current.getMessage().isBlank()) {
        message = current.getMessage();
      }
      current = current.getCause();
    }

    return message != null ? message : "Unknown error";
  }

  public String getLastCheckTime() {
    return lastCheckTime;
  }

  public void clearCache() {
    responseCache.clear();
    logger.info("Whitelist response cache cleared");
  }

  public void clearCacheForPlayer(String username, String uuid) {
    // Validate input parameters
    if (username == null || username.trim().isEmpty()) {
      logger.warning("clearCacheForPlayer called with null or empty username");
      return;
    }

    // Clear cache for both username and UUID if available
    if (uuid != null) {
      responseCache.remove(uuid);
    }
    responseCache.remove(username.toLowerCase());
    if (config.getBoolean("logging.debug", false)) {
      logger.debug("Cleared cache for player: " + username + " (" + uuid + ")");
    }
  }

  /**
   * Internal class for caching API responses
   */
  public static class CachedResponse {
    private final WhitelistResponse response;
    private final long expiryTime;

    public CachedResponse(WhitelistResponse response, long expiryTime) {
      this.response = response;
      this.expiryTime = expiryTime;
    }

    public WhitelistResponse getResponse() {
      return response;
    }

    public boolean isExpired() {
      return System.currentTimeMillis() > expiryTime;
    }
  }
}
