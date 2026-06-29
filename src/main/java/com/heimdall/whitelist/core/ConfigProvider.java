package com.heimdall.whitelist.core;

import java.util.List;

/**
 * Platform-agnostic configuration provider interface.
 * Implementations bridge to the platform's native config system.
 */
public interface ConfigProvider {
  String getString(String path, String def);

  int getInt(String path, int def);

  long getLong(String path, long def);

  boolean getBoolean(String path, boolean def);

  List<String> getStringList(String path);

  void set(String path, Object value);

  void save();

  void reload();
}
