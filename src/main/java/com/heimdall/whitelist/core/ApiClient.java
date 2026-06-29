package com.heimdall.whitelist.core;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.heimdall.whitelist.BuildConstants;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Platform-agnostic API client for communicating with the Heimdall bot API.
 */
public class ApiClient {

  private static final int DEFAULT_MAX_CONCURRENT_REQUESTS = 128;
  private static final int DEFAULT_TIMEOUT_MS = 1500;
  private static final int DEFAULT_TOTAL_ATTEMPTS = 1;

  private final PluginLogger logger;
  private final ConfigProvider config;
  private final Gson gson;
  // These are written by updateConfig() (main/command thread) and read from the
  // request executor threads, so they must be volatile or a /hwl reload may
  // never become visible to in-flight workers (issue #797 / MC-13).
  private volatile ExecutorService executor;
  private volatile String baseUrl;
  private volatile String guildId;
  private volatile String hmacSecret;
  private volatile int timeout;
  private volatile int retries;
  private volatile int retryDelay;
  private volatile int maxConcurrentRequests;

  public ApiClient(PluginLogger logger, ConfigProvider config) {
    this.logger = logger;
    this.config = config;
    this.gson = new Gson();
    updateConfig();
  }

  private void reconfigureExecutor(int newMaxConcurrentRequests) {
    ExecutorService previousExecutor = this.executor;
    this.executor = Executors.newFixedThreadPool(newMaxConcurrentRequests);
    this.maxConcurrentRequests = newMaxConcurrentRequests;

    if (previousExecutor != null) {
      previousExecutor.shutdown();
    }
  }

  public void updateConfig() {
    this.baseUrl = config.getString("api.baseUrl", "http://localhost:3001");
    this.hmacSecret = config.getString("api.apiKey", "");
    this.guildId = config.getString("api.guildId", "");

    // ── Deprecation tripwire ──────────────────────────────────────────
    // Catch configs that still use the old api.hmacSecret field.
    if (this.hmacSecret.isEmpty()) {
      String legacyHmacSecret = config.getString("api.hmacSecret", "");
      if (!legacyHmacSecret.isEmpty()) {
        this.hmacSecret = legacyHmacSecret;
        logger.warning("========================================================");
        logger.warning("  DEPRECATED: 'api.hmacSecret' has been renamed to 'api.apiKey'.");
        logger.warning("  Using the old value for now — please update your config.");
        logger.warning("========================================================");
      }
    }

    int configuredMaxConcurrentRequests = Math.max(1,
        config.getInt("performance.maxConcurrentRequests", DEFAULT_MAX_CONCURRENT_REQUESTS));
    if (this.executor == null || configuredMaxConcurrentRequests != this.maxConcurrentRequests) {
      reconfigureExecutor(configuredMaxConcurrentRequests);
    }

    this.timeout = Math.max(250, config.getInt("api.timeout", DEFAULT_TIMEOUT_MS));
    this.retries = Math.max(1, config.getInt("api.retries", DEFAULT_TOTAL_ATTEMPTS));
    this.retryDelay = config.getInt("api.retryDelay", 1000);

    // Ensure baseUrl doesn't end with slash
    if (baseUrl.endsWith("/")) {
      baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
    }
  }

  /**
   * Get the guild ID from config. May be null/empty if not configured.
   */
  public String getGuildId() {
    return guildId;
  }

  /**
   * Worst-case wall-clock for a single logical request including the full retry
   * sequence: {@code retries} attempts each up to {@code timeout} ms, plus
   * {@code retryDelay} between them. Callers that block on the returned future
   * (e.g. WhitelistManager.get) must bound on this — not on a single timeout —
   * or they abandon a request the retry loop is still legitimately working
   * (issue #797 / MC-6).
   */
  public long getOverallTimeoutMs() {
    return (long) retries * timeout + (long) Math.max(0, retries - 1) * retryDelay;
  }

  public CompletableFuture<WhitelistResponse> checkWhitelist(String username, String uuid, String ip) {
    return checkWhitelist(username, uuid, ip, null, null, false);
  }

  public CompletableFuture<WhitelistResponse> checkWhitelist(String username, String uuid, String ip,
      List<String> currentGroups, String serverIp, boolean currentlyWhitelisted) {
    return CompletableFuture.supplyAsync(() -> {
      // Validate input parameters
      if (username == null || username.trim().isEmpty()) {
        throw new IllegalArgumentException("Username cannot be null or empty");
      }
      if (uuid == null || uuid.trim().isEmpty()) {
        throw new IllegalArgumentException("UUID cannot be null or empty");
      }
      if (ip == null || ip.trim().isEmpty()) {
        throw new IllegalArgumentException("IP cannot be null or empty");
      }

      // Normalize username to lowercase for consistent matching
      String normalizedUsername = username.toLowerCase();

      JsonObject requestBody = new JsonObject();
      requestBody.addProperty("username", normalizedUsername);
      requestBody.addProperty("uuid", uuid);
      requestBody.addProperty("ip", ip);
      requestBody.addProperty("serverIp", serverIp != null ? serverIp : "localhost");
      // WS serverId so the bot can attribute join-feed events to this server.
      requestBody.addProperty("serverId", config.getString("server.serverId", ""));
      requestBody.addProperty("currentlyWhitelisted", currentlyWhitelisted);

      // Add current groups for role sync
      JsonArray groupsArray = new JsonArray();
      if (currentGroups != null) {
        for (String group : currentGroups) {
          groupsArray.add(group);
        }
      }
      requestBody.add("currentGroups", groupsArray);

      if (config.getBoolean("logging.debug", false)) {
        logger.debug("API request body: " + requestBody.toString());
      }

      try {
        return makeRequest("/api/guilds/" + guildId + "/minecraft/connection-attempt", requestBody);
      } catch (Exception e) {
        logger.severe("Failed to check whitelist for " + username + ": " + e.getMessage());
        throw new RuntimeException("API request failed: " + e.getMessage(), e);
      }
    }, executor);
  }

  public CompletableFuture<WhitelistResponse> requestLinkCode(String username, String uuid) {
    return CompletableFuture.supplyAsync(() -> {
      // Validate input parameters
      if (username == null || username.trim().isEmpty()) {
        throw new IllegalArgumentException("Username cannot be null or empty");
      }
      if (uuid == null || uuid.trim().isEmpty()) {
        throw new IllegalArgumentException("UUID cannot be null or empty");
      }

      // Normalize username to lowercase for consistent matching
      String normalizedUsername = username.toLowerCase();

      JsonObject requestBody = new JsonObject();
      requestBody.addProperty("username", normalizedUsername);
      requestBody.addProperty("uuid", uuid);

      try {
        return makeRequestForLinkCode("/api/guilds/" + guildId + "/minecraft/request-link-code", requestBody);
      } catch (Exception e) {
        logger.severe("Failed to request link code for " + username + ": " + e.getMessage());
        throw new RuntimeException("API request failed: " + e.getMessage(), e);
      }
    }, executor);
  }

  private WhitelistResponse makeRequest(String endpoint, JsonObject requestBody) throws IOException {
    IOException lastException = null;

    for (int attempt = 1; attempt <= retries; attempt++) {
      try {
        if (config.getBoolean("logging.debug", false)) {
          logger.info("API Request (attempt " + attempt + "): " + endpoint);
          logger.debug("Request body: " + requestBody.toString());
        }

        URL url = new URL(baseUrl + endpoint);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();

        // Configure connection
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("User-Agent", "HeimdallWhitelist/" + BuildConstants.VERSION);

        String bodyString = requestBody.toString();

        // HMAC-SHA256 request signing
        if (hmacSecret != null && !hmacSecret.isEmpty()) {
          String[] sig = HmacSigner.sign(hmacSecret, "POST", endpoint, bodyString);
          connection.setRequestProperty("X-Signature", sig[0]);
          connection.setRequestProperty("X-Timestamp", sig[1]);
        } else {
          logger.warning("HMAC secret not configured! Set api.apiKey in config.yml");
        }

        connection.setConnectTimeout(timeout);
        connection.setReadTimeout(timeout);
        connection.setDoOutput(true);

        // Send request body
        try (OutputStream os = connection.getOutputStream()) {
          byte[] input = bodyString.getBytes(StandardCharsets.UTF_8);
          os.write(input, 0, input.length);
        }

        // Get response
        int responseCode = connection.getResponseCode();

        if (responseCode == 200) {
          // Success - read response
          try (BufferedReader br = new BufferedReader(
              new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {

            StringBuilder response = new StringBuilder();
            String responseLine;
            while ((responseLine = br.readLine()) != null) {
              response.append(responseLine.trim());
            }

            String responseString = response.toString();

            if (config.getBoolean("logging.debug", false)) {
              logger.debug("API Response: " + responseString);
            }

            JsonObject responseJson = gson.fromJson(responseString, JsonObject.class);
            return parseWhitelistResponse(responseJson);
          }
        } else {
          // Error response - read error message
          String errorMessage = "HTTP " + responseCode;
          try (BufferedReader br = new BufferedReader(
              new InputStreamReader(connection.getErrorStream(), StandardCharsets.UTF_8))) {

            StringBuilder errorResponse = new StringBuilder();
            String responseLine;
            while ((responseLine = br.readLine()) != null) {
              errorResponse.append(responseLine.trim());
            }

            if (errorResponse.length() > 0) {
              JsonObject errorJson = gson.fromJson(errorResponse.toString(), JsonObject.class);
              if (errorJson.has("error")) {
                // v1 API returns structured error: { error: { code, message } }
                if (errorJson.get("error").isJsonObject()) {
                  JsonObject errorObj = errorJson.getAsJsonObject("error");
                  String code = errorObj.has("code") ? errorObj.get("code").getAsString() : "UNKNOWN";
                  String msg = errorObj.has("message") ? errorObj.get("message").getAsString() : "Unknown error";
                  errorMessage = code + ": " + msg;
                } else {
                  errorMessage = errorJson.get("error").getAsString();
                }
              }
            }
          } catch (Exception e) {
            // Ignore error reading error response
          }

          throw new IOException("API request failed: " + errorMessage);
        }

      } catch (IOException e) {
        lastException = e;

        if (attempt < retries) {
          logger.warning("API request failed (attempt " + attempt + "/" + retries + "): " + e.getMessage());
          logger.info("Retrying in " + retryDelay + "ms...");

          try {
            Thread.sleep(retryDelay);
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("Request interrupted", ie);
          }
        } else {
          logger.severe("All API request attempts failed for " + endpoint);
        }
      }
    }

    throw lastException != null ? lastException : new IOException("All API requests failed");
  }

  private WhitelistResponse parseWhitelistResponse(JsonObject json) {
    // Check if response is wrapped in standard API format
    JsonObject data = json;
    if (json.has("data") && json.get("data").isJsonObject()) {
      data = json.getAsJsonObject("data");
    }

    // v1 API returns: whitelisted, message, and optional flags
    boolean whitelisted = data.has("whitelisted") && data.get("whitelisted").getAsBoolean();
    String message = data.has("message") ? data.get("message").getAsString() : "";
    boolean pendingAuth = data.has("pendingAuth") && data.get("pendingAuth").getAsBoolean();
    boolean pendingApproval = data.has("pendingApproval") && data.get("pendingApproval").getAsBoolean();
    boolean existingPlayerLink = data.has("existingPlayerLink") && data.get("existingPlayerLink").getAsBoolean();
    String authCode = data.has("authCode") ? data.get("authCode").getAsString() : null;

    // Derive internal fields from v1 response
    boolean shouldBeWhitelisted = whitelisted;
    boolean hasAuth;
    String action;

    if (whitelisted && !existingPlayerLink) {
      // Fully whitelisted player — clear message since it's not a kick
      hasAuth = true;
      action = "allow";
      message = null;
    } else if (existingPlayerLink) {
      // Whitelisted but not linked — offer linking
      hasAuth = false;
      action = "show_auth_code";
    } else if (pendingAuth) {
      // Has a pending auth code to display
      hasAuth = true;
      action = "show_auth_code";
    } else if (pendingApproval) {
      // Linked but awaiting staff approval
      hasAuth = true;
      action = "pending_approval";
    } else {
      // Not linked, not whitelisted
      hasAuth = false;
      action = "deny";
    }

    // Parse role sync data if present
    boolean roleSyncEnabled = false;
    List<String> targetGroups = null;
    List<String> managedGroups = null;

    if (data.has("roleSync") && data.get("roleSync").isJsonObject()) {
      JsonObject roleSync = data.getAsJsonObject("roleSync");
      roleSyncEnabled = roleSync.has("enabled") && roleSync.get("enabled").getAsBoolean();

      if (roleSync.has("targetGroups") && roleSync.get("targetGroups").isJsonArray()) {
        targetGroups = new ArrayList<>();
        JsonArray groupsArray = roleSync.getAsJsonArray("targetGroups");
        for (int i = 0; i < groupsArray.size(); i++) {
          targetGroups.add(groupsArray.get(i).getAsString());
        }
      }

      if (roleSync.has("managedGroups") && roleSync.get("managedGroups").isJsonArray()) {
        managedGroups = new ArrayList<>();
        JsonArray managedArray = roleSync.getAsJsonArray("managedGroups");
        for (int i = 0; i < managedArray.size(); i++) {
          managedGroups.add(managedArray.get(i).getAsString());
        }
      }
    }

    // Parse queue position if present
    int queuePosition = data.has("queuePosition") ? data.get("queuePosition").getAsInt() : 0;

    return new WhitelistResponse(shouldBeWhitelisted, hasAuth, message, action, authCode, roleSyncEnabled,
        targetGroups, managedGroups, queuePosition);
  }

  private WhitelistResponse makeRequestForLinkCode(String endpoint, JsonObject requestBody) throws IOException {
    IOException lastException = null;

    for (int attempt = 1; attempt <= retries; attempt++) {
      try {
        if (config.getBoolean("logging.debug", false)) {
          logger.info("API Request (attempt " + attempt + "): " + endpoint);
          logger.debug("Request body: " + requestBody.toString());
        }

        URL url = new URL(baseUrl + endpoint);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();

        // Configure connection
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("User-Agent", "HeimdallWhitelist/" + BuildConstants.VERSION);

        String bodyString = requestBody.toString();

        // HMAC-SHA256 request signing
        if (hmacSecret != null && !hmacSecret.isEmpty()) {
          String[] sig = HmacSigner.sign(hmacSecret, "POST", endpoint, bodyString);
          connection.setRequestProperty("X-Signature", sig[0]);
          connection.setRequestProperty("X-Timestamp", sig[1]);
        } else {
          logger.warning("HMAC secret not configured! Set api.apiKey in config.yml");
        }

        connection.setConnectTimeout(timeout);
        connection.setReadTimeout(timeout);
        connection.setDoOutput(true);

        // Send request body
        try (OutputStream os = connection.getOutputStream()) {
          byte[] input = bodyString.getBytes(StandardCharsets.UTF_8);
          os.write(input, 0, input.length);
        }

        // Get response
        int responseCode = connection.getResponseCode();

        if (responseCode == 200) {
          // Success - read response
          try (BufferedReader br = new BufferedReader(
              new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {

            StringBuilder response = new StringBuilder();
            String responseLine;
            while ((responseLine = br.readLine()) != null) {
              response.append(responseLine.trim());
            }

            String responseString = response.toString();

            if (config.getBoolean("logging.debug", false)) {
              logger.debug("API Response: " + responseString);
            }

            JsonObject responseJson = gson.fromJson(responseString, JsonObject.class);
            return parseLinkCodeResponse(responseJson);
          }
        } else {
          // Error response - read error message
          String errorMessage = "HTTP " + responseCode;
          try (BufferedReader br = new BufferedReader(
              new InputStreamReader(connection.getErrorStream(), StandardCharsets.UTF_8))) {

            StringBuilder errorResponse = new StringBuilder();
            String responseLine;
            while ((responseLine = br.readLine()) != null) {
              errorResponse.append(responseLine.trim());
            }

            if (errorResponse.length() > 0) {
              JsonObject errorJson = gson.fromJson(errorResponse.toString(), JsonObject.class);
              if (errorJson.has("error")) {
                // v1 API returns structured error: { error: { code, message } }
                if (errorJson.get("error").isJsonObject()) {
                  JsonObject errorObj = errorJson.getAsJsonObject("error");
                  String code = errorObj.has("code") ? errorObj.get("code").getAsString() : "UNKNOWN";
                  String msg = errorObj.has("message") ? errorObj.get("message").getAsString() : "Unknown error";
                  errorMessage = code + ": " + msg;
                } else {
                  errorMessage = errorJson.get("error").getAsString();
                }
              }
            }
          } catch (Exception e) {
            // Ignore error reading error response
          }

          throw new IOException("API request failed: " + errorMessage);
        }

      } catch (IOException e) {
        lastException = e;

        if (attempt < retries) {
          logger.warning("API request failed (attempt " + attempt + "/" + retries + "): " + e.getMessage());
          logger.info("Retrying in " + retryDelay + "ms...");

          try {
            Thread.sleep(retryDelay);
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("Request interrupted", ie);
          }
        } else {
          logger.severe("All API request attempts failed for " + endpoint);
        }
      }
    }

    throw lastException != null ? lastException : new IOException("All API requests failed");
  }

  private WhitelistResponse parseLinkCodeResponse(JsonObject json) {
    // Check if response is wrapped in standard API format
    JsonObject data = json;
    if (json.has("data") && json.get("data").isJsonObject()) {
      data = json.getAsJsonObject("data");
    }

    // v1 API returns: alreadyLinked (boolean) and code (string) or message (string)
    boolean alreadyLinked = data.has("alreadyLinked") && data.get("alreadyLinked").getAsBoolean();

    if (alreadyLinked) {
      String error = data.has("message") ? data.get("message").getAsString() : "Account already linked";
      throw new RuntimeException(error);
    }

    String code = data.has("code") ? data.get("code").getAsString() : null;
    if (code == null || code.isEmpty()) {
      throw new RuntimeException("No auth code received from API");
    }

    return new WhitelistResponse(false, false, "", "link_code", code);
  }

  /* ═══════════════════════ Update Check ═════════════════════════ */

  /** Longer read timeout for the update check than the login-path default. */
  private static final int UPDATE_CHECK_TIMEOUT_MS = 8000;

  /**
   * Fetch the latest published plugin release from the bot API. The bot sources
   * this from GitHub Releases and caches it.
   */
  public CompletableFuture<PluginReleaseInfo> getLatestRelease() {
    return CompletableFuture.supplyAsync(() -> {
      try {
        JsonObject response = makeGetRequest(
            "/api/guilds/" + guildId + "/minecraft/plugin/latest",
            Math.max(timeout, UPDATE_CHECK_TIMEOUT_MS));

        JsonObject data = response;
        if (response.has("data") && response.get("data").isJsonObject()) {
          data = response.getAsJsonObject("data");
        }

        return new PluginReleaseInfo(
            getStringOrNull(data, "version"),
            getStringOrNull(data, "downloadUrl"),
            data.has("releaseNotes") && !data.get("releaseNotes").isJsonNull()
                ? data.get("releaseNotes").getAsString()
                : "",
            getStringOrNull(data, "htmlUrl"),
            getStringOrNull(data, "publishedAt"));
      } catch (Exception e) {
        logger.debug("Failed to fetch latest release: " + e.getMessage());
        throw new RuntimeException("Failed to fetch latest release: " + e.getMessage(), e);
      }
    }, executor);
  }

  private static String getStringOrNull(JsonObject obj, String key) {
    return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : null;
  }

  /* ═══════════════════════ Whitelist Pre-warm Sync ═══════════════════════ */

  /** Read timeout for the (potentially large) full-whitelist dump. */
  private static final int WHITELIST_SYNC_TIMEOUT_MS = 15_000;

  /**
   * Fetch the FULL set of currently-whitelisted players for this guild, so the
   * cache can be pre-warmed (restart resilience). Blocking work runs on the
   * request executor. The list can be large, so a longer read timeout than the
   * latency-sensitive login path is used.
   */
  public CompletableFuture<List<WhitelistSyncEntry>> fetchWhitelistSync() {
    return CompletableFuture.supplyAsync(() -> {
      try {
        JsonObject response = makeGetRequest(
            "/api/guilds/" + guildId + "/minecraft/whitelist/sync",
            Math.max(timeout, WHITELIST_SYNC_TIMEOUT_MS));

        JsonObject data = response;
        if (response.has("data") && response.get("data").isJsonObject()) {
          data = response.getAsJsonObject("data");
        }

        List<WhitelistSyncEntry> entries = new ArrayList<>();
        if (data.has("players") && data.get("players").isJsonArray()) {
          JsonArray players = data.getAsJsonArray("players");
          for (int i = 0; i < players.size(); i++) {
            if (!players.get(i).isJsonObject()) {
              continue;
            }
            JsonObject p = players.get(i).getAsJsonObject();
            String uuid = getStringOrNull(p, "uuid");
            if (uuid == null || uuid.isBlank()) {
              continue;
            }
            entries.add(new WhitelistSyncEntry(uuid, getStringOrNull(p, "username")));
          }
        }
        return entries;
      } catch (Exception e) {
        logger.debug("[WhitelistSync] Failed to fetch whitelist: " + e.getMessage());
        throw new RuntimeException("Failed to fetch whitelist sync: " + e.getMessage(), e);
      }
    }, executor);
  }

  /* ═══════════════════════ Offense System ═══════════════════════ */

  /**
   * Fetch all offense types for the guild. Used for caching and tab-completion.
   */
  public CompletableFuture<List<OffenseTypeInfo>> getOffenseTypes() {
    return CompletableFuture.supplyAsync(() -> {
      try {
        JsonObject response = makeGetRequest("/api/guilds/" + guildId + "/minecraft/offense-types");

        JsonObject data = response;
        if (response.has("data") && response.get("data").isJsonArray()) {
          JsonArray types = response.getAsJsonArray("data");
          List<OffenseTypeInfo> result = new ArrayList<>();

          for (int i = 0; i < types.size(); i++) {
            JsonObject typeObj = types.get(i).getAsJsonObject();
            String typeId = typeObj.get("typeId").getAsString();
            String displayName = typeObj.get("displayName").getAsString();
            String description = typeObj.has("description") && !typeObj.get("description").isJsonNull()
                ? typeObj.get("description").getAsString()
                : "";
            boolean enabled = typeObj.has("enabled") && typeObj.get("enabled").getAsBoolean();

            List<String> offenses = new ArrayList<>();
            if (typeObj.has("offenses") && typeObj.get("offenses").isJsonArray()) {
              JsonArray offensesArray = typeObj.getAsJsonArray("offenses");
              for (int j = 0; j < offensesArray.size(); j++) {
                offenses.add(offensesArray.get(j).getAsString());
              }
            }

            result.add(new OffenseTypeInfo(typeId, displayName, description, offenses, enabled));
          }

          return result;
        }

        return new ArrayList<>();
      } catch (Exception e) {
        logger.severe("Failed to fetch offense types: " + e.getMessage());
        throw new RuntimeException("Failed to fetch offense types: " + e.getMessage(), e);
      }
    }, executor);
  }

  /**
   * Record an offense against a player. Returns escalation info and command to
   * dispatch.
   */
  public CompletableFuture<OffenseResponse> offend(String targetUuid, String targetUsername,
      String offenseSlug, String issuedByUuid, String issuedByUsername, String notes) {
    return CompletableFuture.supplyAsync(() -> {
      JsonObject requestBody = new JsonObject();
      requestBody.addProperty("targetUuid", targetUuid);
      requestBody.addProperty("targetUsername", targetUsername);
      requestBody.addProperty("offenseSlug", offenseSlug.toLowerCase());
      if (issuedByUuid != null)
        requestBody.addProperty("issuedByUuid", issuedByUuid);
      if (issuedByUsername != null)
        requestBody.addProperty("issuedByUsername", issuedByUsername);
      if (notes != null && !notes.isEmpty())
        requestBody.addProperty("notes", notes);

      try {
        JsonObject json = makeRequestGeneric("/api/guilds/" + guildId + "/minecraft/offend",
            "POST", requestBody);

        JsonObject data = json;
        if (json.has("data") && json.get("data").isJsonObject()) {
          data = json.getAsJsonObject("data");
        }

        String infractionId = "";
        if (data.has("infraction") && data.get("infraction").isJsonObject()) {
          JsonObject inf = data.getAsJsonObject("infraction");
          if (inf.has("_id"))
            infractionId = inf.get("_id").getAsString();
        }

        String command = data.has("command") ? data.get("command").getAsString() : "";
        String action = data.has("action") ? data.get("action").getAsString() : "unknown";
        Integer duration = data.has("duration") && !data.get("duration").isJsonNull()
            ? data.get("duration").getAsInt()
            : null;
        int totalPoints = data.has("totalPoints") ? data.get("totalPoints").getAsInt() : 0;
        int tierApplied = data.has("tierApplied") ? data.get("tierApplied").getAsInt() : 0;
        String tierDescription = data.has("tierDescription") ? data.get("tierDescription").getAsString() : "";
        String offenseType = data.has("offenseType") ? data.get("offenseType").getAsString() : "";

        return new OffenseResponse(infractionId, command, action, duration,
            totalPoints, tierApplied, tierDescription, offenseType);
      } catch (Exception e) {
        logger.severe("Failed to record offense: " + e.getMessage());
        throw new RuntimeException("Failed to record offense: " + e.getMessage(), e);
      }
    }, executor);
  }

  /**
   * Generic GET request that returns the parsed JSON response, using the
   * configured per-request {@link #timeout}.
   */
  private JsonObject makeGetRequest(String endpoint) throws IOException {
    return makeGetRequest(endpoint, timeout);
  }

  /**
   * Generic GET request with an explicit timeout. Used for non-login-path calls
   * (e.g. the update check) that can tolerate a longer wait than the latency-
   * sensitive whitelist check.
   */
  private JsonObject makeGetRequest(String endpoint, int requestTimeoutMs) throws IOException {
    IOException lastException = null;

    for (int attempt = 1; attempt <= retries; attempt++) {
      try {
        if (config.getBoolean("logging.debug", false)) {
          logger.info("API GET Request (attempt " + attempt + "): " + endpoint);
        }

        URL url = new URL(baseUrl + endpoint);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();

        connection.setRequestMethod("GET");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("User-Agent", "HeimdallWhitelist/" + BuildConstants.VERSION);

        // HMAC-SHA256 request signing (GET has no body)
        if (hmacSecret != null && !hmacSecret.isEmpty()) {
          String[] sig = HmacSigner.sign(hmacSecret, "GET", endpoint, "");
          connection.setRequestProperty("X-Signature", sig[0]);
          connection.setRequestProperty("X-Timestamp", sig[1]);
        }

        connection.setConnectTimeout(requestTimeoutMs);
        connection.setReadTimeout(requestTimeoutMs);

        int responseCode = connection.getResponseCode();

        if (responseCode == 200) {
          try (BufferedReader br = new BufferedReader(
              new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder response = new StringBuilder();
            String responseLine;
            while ((responseLine = br.readLine()) != null) {
              response.append(responseLine.trim());
            }
            return gson.fromJson(response.toString(), JsonObject.class);
          }
        } else {
          String errorMessage = readErrorResponse(connection);
          throw new IOException("API request failed: " + errorMessage);
        }
      } catch (IOException e) {
        lastException = e;
        if (attempt < retries) {
          logger.warning("API GET request failed (attempt " + attempt + "/" + retries + "): " + e.getMessage());
          try {
            Thread.sleep(retryDelay);
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("Request interrupted", ie);
          }
        }
      }
    }

    throw lastException != null ? lastException : new IOException("All API requests failed");
  }

  /**
   * Generic request that accepts a method and body and returns the parsed JSON
   * response.
   */
  private JsonObject makeRequestGeneric(String endpoint, String method, JsonObject requestBody) throws IOException {
    IOException lastException = null;

    for (int attempt = 1; attempt <= retries; attempt++) {
      try {
        if (config.getBoolean("logging.debug", false)) {
          logger.info("API " + method + " Request (attempt " + attempt + "): " + endpoint);
        }

        URL url = new URL(baseUrl + endpoint);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();

        connection.setRequestMethod(method);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("User-Agent", "HeimdallWhitelist/" + BuildConstants.VERSION);

        String bodyString = requestBody != null ? requestBody.toString() : "";

        if (hmacSecret != null && !hmacSecret.isEmpty()) {
          String[] sig = HmacSigner.sign(hmacSecret, method, endpoint, bodyString);
          connection.setRequestProperty("X-Signature", sig[0]);
          connection.setRequestProperty("X-Timestamp", sig[1]);
        }

        connection.setConnectTimeout(timeout);
        connection.setReadTimeout(timeout);

        if (requestBody != null) {
          connection.setDoOutput(true);
          try (OutputStream os = connection.getOutputStream()) {
            byte[] input = bodyString.getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
          }
        }

        int responseCode = connection.getResponseCode();

        if (responseCode >= 200 && responseCode < 300) {
          try (BufferedReader br = new BufferedReader(
              new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder response = new StringBuilder();
            String responseLine;
            while ((responseLine = br.readLine()) != null) {
              response.append(responseLine.trim());
            }
            return gson.fromJson(response.toString(), JsonObject.class);
          }
        } else {
          String errorMessage = readErrorResponse(connection);
          throw new IOException("API request failed: " + errorMessage);
        }
      } catch (IOException e) {
        lastException = e;
        if (attempt < retries) {
          logger.warning(
              "API " + method + " request failed (attempt " + attempt + "/" + retries + "): " + e.getMessage());
          try {
            Thread.sleep(retryDelay);
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("Request interrupted", ie);
          }
        }
      }
    }

    throw lastException != null ? lastException : new IOException("All API requests failed");
  }

  /**
   * Read error response body from a failed HTTP connection.
   */
  private String readErrorResponse(HttpURLConnection connection) {
    String errorMessage = "HTTP " + getResponseCodeSafe(connection);
    try (BufferedReader br = new BufferedReader(
        new InputStreamReader(connection.getErrorStream(), StandardCharsets.UTF_8))) {
      StringBuilder errorResponse = new StringBuilder();
      String responseLine;
      while ((responseLine = br.readLine()) != null) {
        errorResponse.append(responseLine.trim());
      }
      if (errorResponse.length() > 0) {
        JsonObject errorJson = gson.fromJson(errorResponse.toString(), JsonObject.class);
        if (errorJson.has("error")) {
          if (errorJson.get("error").isJsonObject()) {
            JsonObject errorObj = errorJson.getAsJsonObject("error");
            String code = errorObj.has("code") ? errorObj.get("code").getAsString() : "UNKNOWN";
            String msg = errorObj.has("message") ? errorObj.get("message").getAsString() : "Unknown error";
            errorMessage = code + ": " + msg;
          } else {
            errorMessage = errorJson.get("error").getAsString();
          }
        }
      }
    } catch (Exception e) {
      // Ignore error reading error response
    }
    return errorMessage;
  }

  private int getResponseCodeSafe(HttpURLConnection connection) {
    try {
      return connection.getResponseCode();
    } catch (IOException e) {
      return -1;
    }
  }

  public void shutdown() {
    if (executor != null && !executor.isShutdown()) {
      executor.shutdown();
      try {
        if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
          executor.shutdownNow();
        }
      } catch (InterruptedException e) {
        executor.shutdownNow();
        Thread.currentThread().interrupt();
      }
    }
  }
}
