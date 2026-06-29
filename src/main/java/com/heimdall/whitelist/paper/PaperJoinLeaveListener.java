package com.heimdall.whitelist.paper;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Paper/Bukkit join/leave event listener for cache extension
 */
public class PaperJoinLeaveListener implements Listener {

  private final HeimdallPaperPlugin plugin;

  public PaperJoinLeaveListener(HeimdallPaperPlugin plugin) {
    this.plugin = plugin;
  }

  @EventHandler
  public void onPlayerJoin(PlayerJoinEvent event) {
    Player player = event.getPlayer();
    String uuid = player.getUniqueId().toString();
    String username = player.getName();

    // Extend cache on successful join
    if (plugin.getConfig().getBoolean("cache.enabled", true)) {
      plugin.getWhitelistCache().extendCacheOnJoin(uuid, username);
    }
  }

  @EventHandler
  public void onPlayerQuit(PlayerQuitEvent event) {
    Player player = event.getPlayer();
    String uuid = player.getUniqueId().toString();
    String username = player.getName();

    // Extend cache on leave (they were clearly allowed to play)
    if (plugin.getConfig().getBoolean("cache.enabled", true)) {
      plugin.getWhitelistCache().extendCacheOnLeave(uuid, username);
    }
  }
}
