package com.heimdall.whitelist.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Retry-aware request budget — issue #797 / MC-6 + MC-13.
 *
 * {@link ApiClient#getOverallTimeoutMs()} must cover the whole retry sequence
 * (so a blocking {@code .get()} doesn't abandon a request the retry loop is
 * still working), and must reflect a later {@code updateConfig()} so a
 * {@code /hwl reload} actually changes the bound.
 */
class ApiClientTimeoutTest {

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

  /** Minimal in-memory ConfigProvider backed by a flat map of dotted keys. */
  private static final class MapConfig implements ConfigProvider {
    final Map<String, Object> values = new HashMap<>();

    @Override
    public String getString(String path, String def) {
      Object v = values.get(path);
      return v != null ? v.toString() : def;
    }

    @Override
    public int getInt(String path, int def) {
      Object v = values.get(path);
      return v instanceof Number ? ((Number) v).intValue() : def;
    }

    @Override
    public long getLong(String path, long def) {
      Object v = values.get(path);
      return v instanceof Number ? ((Number) v).longValue() : def;
    }

    @Override
    public boolean getBoolean(String path, boolean def) {
      Object v = values.get(path);
      return v instanceof Boolean ? (Boolean) v : def;
    }

    @Override
    public List<String> getStringList(String path) {
      return Collections.emptyList();
    }

    @Override
    public void set(String path, Object value) {
      values.put(path, value);
    }

    @Override
    public void save() {
    }

    @Override
    public void reload() {
    }
  }

  private ApiClient client;

  @AfterEach
  void tearDown() {
    if (client != null) {
      client.shutdown();
    }
  }

  @Test
  void singleAttemptBudgetIsJustTheTimeout() {
    MapConfig cfg = new MapConfig();
    cfg.values.put("api.timeout", 1500);
    cfg.values.put("api.retries", 1);
    cfg.values.put("api.retryDelay", 1000);
    client = new ApiClient(NOOP_LOGGER, cfg);

    // retries=1 → no retry delays counted.
    assertEquals(1500L, client.getOverallTimeoutMs());
  }

  @Test
  void multiAttemptBudgetCoversEveryAttemptAndDelay() {
    MapConfig cfg = new MapConfig();
    cfg.values.put("api.timeout", 1500);
    cfg.values.put("api.retries", 3);
    cfg.values.put("api.retryDelay", 1000);
    client = new ApiClient(NOOP_LOGGER, cfg);

    // 3 attempts * 1500ms + 2 inter-attempt delays * 1000ms = 6500ms.
    // The old `.get(timeout + 1000)` = 2500ms truncated this by 4s.
    assertEquals(6500L, client.getOverallTimeoutMs());
  }

  @Test
  void reloadChangesTheBudget() {
    MapConfig cfg = new MapConfig();
    cfg.values.put("api.timeout", 1500);
    cfg.values.put("api.retries", 1);
    cfg.values.put("api.retryDelay", 1000);
    client = new ApiClient(NOOP_LOGGER, cfg);
    assertEquals(1500L, client.getOverallTimeoutMs());

    // Simulate editing config.yml then `/hwl reload`.
    cfg.values.put("api.timeout", 2000);
    cfg.values.put("api.retries", 2);
    client.updateConfig();

    // 2 * 2000 + 1 * 1000 = 5000ms, visible after reload.
    assertEquals(5000L, client.getOverallTimeoutMs());
  }
}
