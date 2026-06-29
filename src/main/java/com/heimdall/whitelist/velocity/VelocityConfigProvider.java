package com.heimdall.whitelist.velocity;

import com.heimdall.whitelist.core.ConfigProvider;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Velocity implementation of ConfigProvider using JSON config
 */
public class VelocityConfigProvider implements ConfigProvider {

  private final Path configFile;
  private final Gson gson;
  private JsonObject config;

  public VelocityConfigProvider(Path dataDirectory) {
    this.configFile = dataDirectory.resolve("config.json");
    this.gson = new GsonBuilder().setPrettyPrinting().create();
    loadOrCreateConfig();
  }

  private void loadOrCreateConfig() {
    if (Files.exists(configFile)) {
      try (Reader reader = Files.newBufferedReader(configFile)) {
        config = gson.fromJson(reader, JsonObject.class);
        if (config == null) {
          config = createDefaultConfig();
        }
      } catch (IOException e) {
        config = createDefaultConfig();
      }
    } else {
      config = createDefaultConfig();
      save();
    }
  }

  private JsonObject createDefaultConfig() {
    JsonObject cfg = new JsonObject();

    // Global plugin control
    cfg.addProperty("enabled", false);

    // API configuration
    JsonObject api = new JsonObject();
    api.addProperty("baseUrl", "https://api.bifrost.gg");
    api.addProperty("apiKey", "your-api-key-here");
    api.addProperty("guildId", "");
    api.addProperty("timeout", 1500);
    api.addProperty("retries", 1);
    api.addProperty("retryDelay", 1000);
    cfg.add("api", api);

    // Server identification
    JsonObject server = new JsonObject();
    server.addProperty("serverId", "");
    server.addProperty("displayName", "My Minecraft Network");
    server.addProperty("publicIp", "localhost");
    cfg.add("server", server);

    // Messages
    // NOTE: Player-facing messages (not whitelisted, auth code, whitelist success,
    // etc.)
    // are configured in the Heimdall dashboard and served by the bot API — not
    // here.
    JsonObject messages = new JsonObject();
    messages.addProperty("apiUnavailable",
        "§cWhitelist system is temporarily unavailable. Please try again later.");
    messages.addProperty("apiUnavailableAllowed",
        "§eWhitelist API is temporarily unavailable. You have been allowed in from cache.");
    messages.addProperty("reloaded", "§aHeimdall Whitelist plugin reloaded successfully!");
    messages.addProperty("status",
        "§7Heimdall Whitelist Status:\n§7API URL: §f{url}\n§7Server ID: §f{serverId}\n§7Last Check: §f{lastCheck}");
    cfg.add("messages", messages);

    // Logging
    JsonObject logging = new JsonObject();
    logging.addProperty("debug", false);
    logging.addProperty("logDecisions", true);
    cfg.add("logging", logging);

    // Performance
    JsonObject performance = new JsonObject();
    performance.addProperty("cacheTimeout", 30);
    performance.addProperty("maxConcurrentRequests", 128);
    cfg.add("performance", performance);

    // Cache
    JsonObject cache = new JsonObject();
    cache.addProperty("enabled", true);
    cache.addProperty("cacheWindow", 60);
    cache.addProperty("extendOnJoin", 120);
    cache.addProperty("extendOnLeave", 180);
    // Hard ceiling (hours) on how long a positive entry can be extended past the
    // last successful API verification — stops revoked players keeping access by
    // rejoining (issue #771). 0 disables (not recommended).
    cache.addProperty("maxExtensionHours", 24);
    cache.addProperty("cleanupInterval", 30);
    cfg.add("cache", cache);

    // Advanced
    JsonObject advanced = new JsonObject();
    advanced.addProperty("apiFallbackMode", "whitelist-only");
    cfg.add("advanced", advanced);

    // Bypass: UUIDs here skip every whitelist check (issue #796 / MC-2). The
    // heimdall.bypass permission can't gate login because permissions aren't
    // attached at that point — list the player's UUID here instead.
    JsonObject bypass = new JsonObject();
    bypass.add("uuids", new com.google.gson.JsonArray());
    cfg.add("bypass", bypass);

    // WebSocket tunnel
    JsonObject websocket = new JsonObject();
    websocket.addProperty("enabled", false);
    websocket.addProperty("reconnect-delay", 5000);
    websocket.addProperty("max-reconnect-delay", 30000);
    websocket.addProperty("heartbeat-interval", 30000);
    websocket.addProperty("heartbeat-timeout", 10000);
    cfg.add("websocket", websocket);

    return cfg;
  }

  @Override
  public String getString(String path, String def) {
    JsonElement element = getElement(path);
    if (element != null && element.isJsonPrimitive()) {
      return element.getAsString();
    }
    return def;
  }

  @Override
  public int getInt(String path, int def) {
    JsonElement element = getElement(path);
    if (element != null && element.isJsonPrimitive()) {
      try {
        return element.getAsInt();
      } catch (NumberFormatException e) {
        return def;
      }
    }
    return def;
  }

  @Override
  public long getLong(String path, long def) {
    JsonElement element = getElement(path);
    if (element != null && element.isJsonPrimitive()) {
      try {
        return element.getAsLong();
      } catch (NumberFormatException e) {
        return def;
      }
    }
    return def;
  }

  @Override
  public boolean getBoolean(String path, boolean def) {
    JsonElement element = getElement(path);
    if (element != null && element.isJsonPrimitive()) {
      return element.getAsBoolean();
    }
    return def;
  }

  @Override
  public List<String> getStringList(String path) {
    JsonElement element = getElement(path);
    if (element != null && element.isJsonArray()) {
      List<String> result = new ArrayList<>();
      for (JsonElement e : element.getAsJsonArray()) {
        if (e.isJsonPrimitive()) {
          result.add(e.getAsString());
        }
      }
      return result;
    }
    return Collections.emptyList();
  }

  @Override
  public void set(String path, Object value) {
    String[] parts = path.split("\\.");
    JsonObject current = config;

    for (int i = 0; i < parts.length - 1; i++) {
      if (!current.has(parts[i]) || !current.get(parts[i]).isJsonObject()) {
        current.add(parts[i], new JsonObject());
      }
      current = current.getAsJsonObject(parts[i]);
    }

    String key = parts[parts.length - 1];
    if (value instanceof String) {
      current.addProperty(key, (String) value);
    } else if (value instanceof Number) {
      current.addProperty(key, (Number) value);
    } else if (value instanceof Boolean) {
      current.addProperty(key, (Boolean) value);
    } else if (value instanceof List) {
      JsonArray array = new JsonArray();
      for (Object item : (List<?>) value) {
        if (item instanceof String) {
          array.add((String) item);
        }
      }
      current.add(key, array);
    }
  }

  @Override
  public void save() {
    try {
      Files.createDirectories(configFile.getParent());
      try (Writer writer = Files.newBufferedWriter(configFile)) {
        gson.toJson(config, writer);
      }
    } catch (IOException e) {
      // Log error
    }
  }

  @Override
  public void reload() {
    loadOrCreateConfig();
  }

  private JsonElement getElement(String path) {
    String[] parts = path.split("\\.");
    JsonElement current = config;

    for (String part : parts) {
      if (current == null || !current.isJsonObject()) {
        return null;
      }
      current = current.getAsJsonObject().get(part);
    }

    return current;
  }

  public JsonObject getConfig() {
    return config;
  }
}
