package com.heimdall.whitelist.paper;

import com.heimdall.whitelist.core.ConfigProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collections;
import java.util.List;

/**
 * Paper/Bukkit implementation of ConfigProvider
 */
public class PaperConfigProvider implements ConfigProvider {

  private final JavaPlugin plugin;

  public PaperConfigProvider(JavaPlugin plugin) {
    this.plugin = plugin;
  }

  @Override
  public String getString(String path, String def) {
    return plugin.getConfig().getString(path, def);
  }

  @Override
  public int getInt(String path, int def) {
    return plugin.getConfig().getInt(path, def);
  }

  @Override
  public long getLong(String path, long def) {
    return plugin.getConfig().getLong(path, def);
  }

  @Override
  public boolean getBoolean(String path, boolean def) {
    return plugin.getConfig().getBoolean(path, def);
  }

  @Override
  public List<String> getStringList(String path) {
    List<String> list = plugin.getConfig().getStringList(path);
    return list != null ? list : Collections.emptyList();
  }

  @Override
  public void set(String path, Object value) {
    plugin.getConfig().set(path, value);
  }

  @Override
  public void save() {
    plugin.saveConfig();
  }

  @Override
  public void reload() {
    plugin.reloadConfig();
  }
}
