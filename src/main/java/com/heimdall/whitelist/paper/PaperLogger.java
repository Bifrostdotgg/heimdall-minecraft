package com.heimdall.whitelist.paper;

import com.heimdall.whitelist.core.PluginLogger;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Paper/Bukkit implementation of PluginLogger
 */
public class PaperLogger implements PluginLogger {

  private final JavaPlugin plugin;
  private final boolean debugEnabled;

  public PaperLogger(JavaPlugin plugin) {
    this.plugin = plugin;
    this.debugEnabled = plugin.getConfig().getBoolean("logging.debug", false);
  }

  @Override
  public void info(String message) {
    plugin.getLogger().info(message);
  }

  @Override
  public void warning(String message) {
    plugin.getLogger().warning(message);
  }

  @Override
  public void severe(String message) {
    plugin.getLogger().severe(message);
  }

  @Override
  public void debug(String message) {
    if (debugEnabled || plugin.getConfig().getBoolean("logging.debug", false)) {
      plugin.getLogger().info("[DEBUG] " + message);
    }
  }
}
