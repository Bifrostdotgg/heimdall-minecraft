package com.heimdall.whitelist.core;

/**
 * Platform-agnostic logging interface.
 * Implementations bridge to the platform's native logging system.
 */
public interface PluginLogger {
  void info(String message);

  void warning(String message);

  void severe(String message);

  void debug(String message);
}
