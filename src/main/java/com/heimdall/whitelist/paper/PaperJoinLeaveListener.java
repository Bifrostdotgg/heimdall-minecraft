package com.heimdall.whitelist.paper;

import com.heimdall.whitelist.core.UpdateChecker;
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

    // Notify admins when a plugin update is available.
    UpdateChecker updateChecker = plugin.getUpdateChecker();
    if (updateChecker != null
        && plugin.getConfig().getBoolean("updates.notifyAdmins", true)
        && updateChecker.isUpdateAvailable()
        && updateChecker.getLatestRelease() != null
        && player.hasPermission("heimdall.admin")) {
      String latest = updateChecker.getLatestRelease().getVersion();
      player.sendMessage(plugin.colorize("&e[HeimdallWhitelist] &7An update is available: &f"
          + updateChecker.getCurrentVersion() + " &7→ &a" + latest));
      player.sendMessage(plugin.colorize("&7Run &f/hwl update&7 to download it (applied on restart)."));
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
