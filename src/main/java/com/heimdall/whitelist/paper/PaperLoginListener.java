package com.heimdall.whitelist.paper;

import com.heimdall.whitelist.core.WhitelistResponse;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerLoginEvent;

import java.util.List;
import java.util.UUID;

/**
 * Paper/Bukkit login event listener
 */
public class PaperLoginListener implements Listener {

  private final HeimdallPaperPlugin plugin;

  public PaperLoginListener(HeimdallPaperPlugin plugin) {
    this.plugin = plugin;
  }

  @EventHandler(priority = EventPriority.LOW)
  public void onPlayerPreLogin(AsyncPlayerPreLoginEvent event) {
    // Skip if the event is already cancelled (e.g., by ban plugins like LiteBans)
    if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
      if (plugin.getConfig().getBoolean("logging.debug", false)) {
        plugin.getPluginLogger().debug("Skipping whitelist check for " + event.getName() +
            " - already denied by another plugin: " + event.getLoginResult());
      }
      return;
    }

    // Check if the plugin is globally enabled
    if (!plugin.getConfig().getBoolean("enabled", false)) {
      if (plugin.getConfig().getBoolean("logging.debug", false)) {
        plugin.getPluginLogger().debug("Plugin is disabled, allowing " + event.getName() + " without whitelist check");
      }
      return;
    }

    // Config-driven bypass: the heimdall.bypass permission can't be checked here
    // (permissions aren't attached at pre-login), so honour a UUID allowlist that
    // IS known at this point. Bypassed players skip every whitelist check below
    // — including the guild-ID guard (issue #796 / MC-2).
    if (com.heimdall.whitelist.core.BypassList.isBypassed(
        plugin.getConfig().getStringList("bypass.uuids"), event.getUniqueId().toString())) {
      if (plugin.getConfig().getBoolean("logging.debug", false)) {
        plugin.getPluginLogger().debug("Bypass UUID match for " + event.getName() + " — skipping whitelist check");
      }
      return;
    }

    // Guard: guild ID must be resolved before we can check the API
    if (plugin.getApiClient().getGuildId() == null || plugin.getApiClient().getGuildId().isEmpty()) {
      plugin.getPluginLogger().warning("Guild ID not resolved — cannot check whitelist for " + event.getName()
          + ". Set 'api.guildId' in config.yml or ensure the bot is reachable for auto-resolution.");
      String errorMessage = plugin.getConfig().getString("messages.apiUnavailable",
          "§cWhitelist system is temporarily unavailable. Please try again later.");
      event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
          LegacyComponentSerializer.legacySection().deserialize(errorMessage));
      return;
    }

    String username = event.getName();
    String uuid = event.getUniqueId().toString();
    String ip = event.getAddress().getHostAddress();

    if (plugin.getConfig().getBoolean("logging.debug", false)) {
      plugin.getPluginLogger().debug("Checking whitelist for " + username + " (" + uuid + ") from " + ip);
    }

    // Check cache only if caching is enabled
    boolean cacheEnabled = plugin.getConfig().getBoolean("cache.enabled", true);
    Boolean cachedResult = null;

    if (cacheEnabled) {
      cachedResult = plugin.getWhitelistCache().isCachedWhitelisted(uuid, username);
      if (cachedResult != null && cachedResult) {
        if (plugin.getConfig().getBoolean("logging.debug", false)) {
          plugin.getPluginLogger().debug("Cache hit for " + username + ": allowing based on cache");
        }
        // Role sync starved by cache pre-warm: the pre-warm sync keeps every
        // whitelisted player permanently cached, so this early-allow branch is
        // the common path and the API-path role sync below would otherwise
        // never run. Fire an async, fire-and-forget check so role sync and the
        // bot's connection history/join feed still happen — it must never
        // block or affect the login result.
        runCacheHitSyncAsync(username, uuid, ip);
        return;
      }
    }

    // Cache miss/disabled or no positive cache - check with API
    if (plugin.getConfig().getBoolean("logging.debug", false)) {
      if (!cacheEnabled) {
        plugin.getPluginLogger().debug("Cache disabled for " + username + ", checking API");
      } else if (cachedResult == null) {
        plugin.getPluginLogger().debug("No cache entry for " + username + ", checking API");
      } else {
        plugin.getPluginLogger().debug("Not in positive cache for " + username + ", checking API");
      }
    }

    try {
      // Get current groups for role sync
      List<String> currentGroups = null;
      PaperLuckPermsManager luckPermsManager = plugin.getLuckPermsManager();
      if (luckPermsManager != null && luckPermsManager.isAvailable()) {
        currentGroups = luckPermsManager.getPlayerGroups(event.getUniqueId());
      }

      // Check whitelist with API
      WhitelistResponse response = plugin.getWhitelistManager().checkPlayerWhitelist(
          username, uuid, ip, currentGroups,
          plugin.getConfig().getString("server.publicIp", "localhost"),
          plugin.getWhitelistCache().isCachedWhitelisted(uuid, username) != null);

      if (plugin.getConfig().getBoolean("logging.logDecisions", true)) {
        plugin.getPluginLogger().info("Whitelist decision for " + username + ": " + response.toString());
      }

      if (response.shouldBeWhitelisted()) {
        // Players who must still link (action=show_auth_code) are whitelisted but
        // are about to be kicked with their link code — do NOT cache them as
        // allowed, or the next attempt would early-allow from cache and they'd
        // never link (issue #796 / MC-4). Only cache players actually let through.
        boolean mustShowAuthCode = "show_auth_code".equals(response.getAction());
        if (cacheEnabled && !mustShowAuthCode) {
          plugin.getWhitelistCache().addWhitelistedPlayer(uuid, username);
        }

        // Apply role sync if enabled
        scheduleRoleSync(username, uuid, response);

        // If the action is to show an auth code, kick with the code
        if ("show_auth_code".equals(response.getAction())) {
          event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST,
              LegacyComponentSerializer.legacySection().deserialize(
                  response.getKickMessage().replace('&', '§')));
        }
      } else {
        // Deny the connection
        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST,
            LegacyComponentSerializer.legacySection().deserialize(
                response.getKickMessage().replace('&', '§')));
      }

    } catch (Exception e) {
      plugin.getPluginLogger().severe("Failed to check whitelist for " + username + ": " + e.getMessage());

      // Handle API failure based on configured fallback mode
      String fallbackMode = plugin.getConfig().getString("advanced.apiFallbackMode", "deny");

      switch (fallbackMode.toLowerCase()) {
        case "allow":
          plugin.getPluginLogger().warning("API failed for " + username + ", allowing connection (fail-open mode)");

          // Schedule a message to the player after they join
          plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            Player player = plugin.getServer().getPlayer(username);
            if (player != null && player.isOnline()) {
              String message = plugin.getConfig().getString("messages.apiUnavailableAllowed",
                  "§eAPI temporarily unavailable - access granted.\n§7Please link your Discord account when possible.");
              player.sendMessage(LegacyComponentSerializer.legacySection().deserialize(message));
            }
          }, 20L);
          break;

        case "whitelist-only":
          Boolean cachedWhitelisted = plugin.getWhitelistCache().isCachedWhitelisted(uuid, username);
          if (cachedWhitelisted != null && cachedWhitelisted) {
            plugin.getPluginLogger()
                .warning("API failed for " + username + ", allowing based on positive cache");
          } else {
            plugin.getPluginLogger().warning("API failed for " + username + ", denying (no positive cache entry)");
            String errorMessage = plugin.getConfig().getString("messages.apiUnavailable",
                "§cWhitelist system is temporarily unavailable. Please try again later.");
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST,
                LegacyComponentSerializer.legacySection().deserialize(errorMessage));
          }
          break;

        case "deny":
        default:
          plugin.getPluginLogger().warning("API failed for " + username + ", denying connection (fail-closed mode)");
          String errorMessage = plugin.getConfig().getString("messages.apiUnavailable",
              "§cWhitelist system is temporarily unavailable. Please try again later.");
          event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST,
              LegacyComponentSerializer.legacySection().deserialize(errorMessage));
          break;
      }
    }
  }

  /**
   * Schedule LuckPerms role sync from a whitelist response, after a short delay
   * so the player is fully connected. No-op when the response doesn't request
   * role sync. Shared by the API login path and the async cache-hit path.
   */
  private void scheduleRoleSync(String username, String uuid, WhitelistResponse response) {
    if (!response.isRoleSyncEnabled() || response.getManagedGroups() == null
        || response.getManagedGroups().isEmpty()) {
      return;
    }

    plugin.getPluginLogger()
        .info("Scheduling role sync for " + username + " with target groups: "
            + response.getTargetGroups() +
            " and managed groups: " + response.getManagedGroups());

    // Schedule role sync for after the player has fully connected
    plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
      try {
        UUID playerUuid = UUID.fromString(uuid);
        PaperLuckPermsManager luckPermsManager = plugin.getLuckPermsManager();
        if (luckPermsManager != null && luckPermsManager.isAvailable()) {
          luckPermsManager.setPlayerGroups(playerUuid, response.getTargetGroups(),
              response.getManagedGroups());
          plugin.getPluginLogger().info("Successfully applied role sync for " + username);
        }
      } catch (Exception e) {
        plugin.getPluginLogger().warning("Failed to apply role sync for " + username + ": " + e.getMessage());
      }
    }, 40L); // 2 seconds delay
  }

  /**
   * Fire-and-forget whitelist check for a player admitted from the positive
   * cache (role sync starved by cache pre-warm). Runs off the login thread and
   * must never block, kick, or otherwise affect the already-allowed login:
   * role sync rides on the API response, and the /connection-attempt call also
   * restores the bot's connection history / lastSeen / dashboard join feed for
   * cache-hit joins. A non-whitelisted response is NOT acted on here —
   * revocation propagation is handled by the pre-warm prune.
   */
  private void runCacheHitSyncAsync(String username, String uuid, String ip) {
    plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
      try {
        // Mirror the login guards: skip when the plugin was disabled or the
        // guild ID is unresolved by the time this runs.
        if (!plugin.getConfig().getBoolean("enabled", false)
            || plugin.getApiClient().getGuildId() == null
            || plugin.getApiClient().getGuildId().isEmpty()) {
          return;
        }

        List<String> currentGroups = null;
        PaperLuckPermsManager luckPermsManager = plugin.getLuckPermsManager();
        if (luckPermsManager != null && luckPermsManager.isAvailable()) {
          currentGroups = luckPermsManager.getPlayerGroups(UUID.fromString(uuid));
        }

        WhitelistResponse response = plugin.getWhitelistManager().checkPlayerWhitelist(
            username, uuid, ip, currentGroups,
            plugin.getConfig().getString("server.publicIp", "localhost"),
            true);

        if (response.shouldBeWhitelisted()) {
          scheduleRoleSync(username, uuid, response);
        } else if (plugin.getConfig().getBoolean("logging.debug", false)) {
          plugin.getPluginLogger().debug("Async cache-hit check for " + username
              + " returned non-whitelisted; leaving cached login untouched (pre-warm prune handles revocation)");
        }
      } catch (Exception e) {
        // Player is already admitted from cache — never surface this.
        if (plugin.getConfig().getBoolean("logging.debug", false)) {
          plugin.getPluginLogger()
              .debug("Async cache-hit whitelist check failed for " + username + ": " + e.getMessage());
        }
      }
    });
  }

  @EventHandler(priority = EventPriority.HIGH)
  public void onPlayerLogin(PlayerLoginEvent event) {
    Player player = event.getPlayer();

    // Check for bypass permission
    if (player.hasPermission("heimdall.bypass")) {
      if (plugin.getConfig().getBoolean("logging.debug", false)) {
        plugin.getPluginLogger()
            .debug("Player " + player.getName() + " bypassed whitelist check (has heimdall.bypass permission)");
      }
      event.allow();
      return;
    }

    // The actual whitelist check was done in AsyncPlayerPreLoginEvent
  }
}
