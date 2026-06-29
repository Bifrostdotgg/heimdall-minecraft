package com.heimdall.whitelist.velocity;

import com.google.inject.Inject;
import com.heimdall.whitelist.BuildConstants;
import com.heimdall.whitelist.core.*;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.slf4j.Logger;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Main Velocity plugin class for Heimdall Whitelist
 */
@Plugin(id = "heimdall-whitelist", name = "HeimdallWhitelist", version = BuildConstants.VERSION, description = "Dynamic whitelist integration with Heimdall Discord bot", url = "https://github.com/Bifrostdotgg/heimdall-minecraft", authors = {
    "Lerndmina" }, dependencies = { @Dependency(id = "luckperms", optional = true) })
public class HeimdallVelocityPlugin {

  private final ProxyServer server;
  private final Logger slf4jLogger;
  private final Path dataDirectory;

  private VelocityLogger logger;
  private VelocityConfigProvider configProvider;
  private ApiClient apiClient;
  private WhitelistManager whitelistManager;
  private WhitelistCache whitelistCache;
  private VelocityLuckPermsManager luckPermsManager;
  private OffenseManager offenseManager;
  private WebSocketClient wsClient;
  private UpdateChecker updateChecker;
  private final long startedAtMs = System.currentTimeMillis();
  private final Map<UUID, Long> linkCooldowns = new ConcurrentHashMap<>();

  @Inject
  public HeimdallVelocityPlugin(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
    this.server = server;
    this.slf4jLogger = logger;
    this.dataDirectory = dataDirectory;
  }

  @Subscribe
  public void onProxyInitialization(ProxyInitializeEvent event) {
    // Initialize adapters
    configProvider = new VelocityConfigProvider(dataDirectory);
    logger = new VelocityLogger(slf4jLogger, configProvider.getBoolean("logging.debug", false));

    // Initialize core managers
    apiClient = new ApiClient(logger, configProvider);

    // Verify guildId is configured
    String gid = apiClient.getGuildId();
    if (gid == null || gid.isEmpty()) {
      logger.warning("================================================");
      logger.warning("  'api.guildId' is not set in config.json!");
      logger.warning("  The whitelist plugin will NOT process joins");
      logger.warning("  until a guild ID is configured.");
      logger.warning("================================================");
    }

    whitelistManager = new WhitelistManager(logger, configProvider, apiClient);

    // Initialize whitelist cache
    long cacheWindow = configProvider.getLong("cache.cacheWindow", 60);
    long extendOnJoin = configProvider.getLong("cache.extendOnJoin", 120);
    long extendOnLeave = configProvider.getLong("cache.extendOnLeave", 180);
    long maxExtensionHours = configProvider.getLong("cache.maxExtensionHours", 24);
    whitelistCache = new WhitelistCache(logger, dataDirectory.toFile(), cacheWindow, extendOnJoin, extendOnLeave,
        maxExtensionHours);

    // Initialize LuckPerms manager (optional - will log warning if not available)
    luckPermsManager = new VelocityLuckPermsManager(logger);

    // Schedule cache cleanup task
    long cleanupInterval = configProvider.getLong("cache.cleanupInterval", 30);
    server.getScheduler().buildTask(this, () -> {
      whitelistCache.cleanupExpiredEntries();
    }).repeat(cleanupInterval, TimeUnit.MINUTES).schedule();

    // Generate server ID if not set
    if (configProvider.getString("server.serverId", "").isEmpty()) {
      String serverId = UUID.randomUUID().toString();
      configProvider.set("server.serverId", serverId);
      configProvider.save();
      logger.info("Generated new server ID: " + serverId);
    }

    // Initialize offense manager and schedule periodic refresh
    offenseManager = new OffenseManager(logger, apiClient);
    CompletableFuture.runAsync(() -> offenseManager.refresh());
    server.getScheduler().buildTask(this, () -> offenseManager.refresh())
        .repeat(5, TimeUnit.MINUTES).schedule();

    // Initialize update checker (asks the bot for the latest published version)
    updateChecker = new UpdateChecker(logger, apiClient);
    if (configProvider.getBoolean("updates.checkEnabled", true)) {
      long intervalHours = Math.max(1, configProvider.getLong("updates.checkIntervalHours", 12));
      CompletableFuture.runAsync(() -> updateChecker.checkNow());
      server.getScheduler().buildTask(this, () -> updateChecker.checkNow())
          .repeat(intervalHours, TimeUnit.HOURS).schedule();
    }

    // Register command
    server.getCommandManager().register("hwl", new HeimdallCommand(), "heimdallwhitelist");
    server.getCommandManager().register("linkdiscord", new LinkDiscordCommand());
    server.getCommandManager().register("offend", new OffendCommand());

    logger.info("Heimdall Whitelist Plugin (Velocity) enabled successfully!");
    logger.info("API URL: " + configProvider.getString("api.baseUrl", "Not set"));
    logger.info("Server ID: " + configProvider.getString("server.serverId", "Not set"));

    // Log LuckPerms status
    if (luckPermsManager != null && luckPermsManager.isAvailable()) {
      logger.info("LuckPerms integration: ENABLED - Role sync will work");
    } else {
      logger.warning("LuckPerms integration: DISABLED - Role sync will NOT work");
      logger.warning("Install LuckPerms on Velocity to enable Discord role sync");
    }

    // Check if plugin is enabled and warn accordingly
    boolean enabled = configProvider.getBoolean("enabled", false);
    if (enabled) {
      logger.info("Whitelist protection is ACTIVE - all players will be checked");
    } else {
      logger.warning("================================================");
      logger.warning("WHITELIST PROTECTION IS DISABLED!");
      logger.warning("All players can join without Discord verification!");
      logger.warning("Enable in config.json or use '/hwl enable' command");
      logger.warning("================================================");
    }

    // Initialize WebSocket tunnel
    wsClient = new WebSocketClient(logger, configProvider);
    wsClient.setGuildId(apiClient.getGuildId());
    wsClient.setMessageHandler((type, msg) -> handleWsMessage(type, msg));
    wsClient.setIdentifyMetadataSupplier(this::buildIdentifyMetadata);
    wsClient.setHealthSupplier(this::buildHealthSnapshot);
    if (configProvider.getBoolean("websocket.enabled", false)) {
      wsClient.connect();
    }
  }

  @Subscribe
  public void onProxyShutdown(ProxyShutdownEvent event) {
    // Shutdown WebSocket
    if (wsClient != null) {
      wsClient.shutdown();
    }

    // Shutdown cache
    if (whitelistCache != null) {
      whitelistCache.shutdown();
    }

    // Shutdown API client
    if (apiClient != null) {
      apiClient.shutdown();
    }

    logger.info("Heimdall Whitelist Plugin disabled!");
  }

  @Subscribe(order = PostOrder.FIRST)
  public void onLogin(LoginEvent event) {
    // Check if the plugin is globally enabled
    if (!configProvider.getBoolean("enabled", false)) {
      if (configProvider.getBoolean("logging.debug", false)) {
        logger.debug("Plugin is disabled, allowing " + event.getPlayer().getUsername() + " without whitelist check");
      }
      return;
    }

    // Config-driven bypass (see BypassList): the heimdall.bypass permission can't
    // gate login, so honour a UUID allowlist. Bypassed players skip every check
    // below, including the guild-ID guard (issue #796 / MC-2).
    if (BypassList.isBypassed(
        configProvider.getStringList("bypass.uuids"), event.getPlayer().getUniqueId().toString())) {
      if (configProvider.getBoolean("logging.debug", false)) {
        logger.debug("Bypass UUID match for " + event.getPlayer().getUsername() + " — skipping whitelist check");
      }
      return;
    }

    // Guard: guild ID must be resolved before we can check the API
    if (apiClient.getGuildId() == null || apiClient.getGuildId().isEmpty()) {
      logger.warning("Guild ID not resolved — cannot check whitelist for " + event.getPlayer().getUsername()
          + ". Set 'api.guildId' in config.yml or ensure the bot is reachable for auto-resolution.");
      String errorMessage = configProvider.getString("messages.apiUnavailable",
          "§cWhitelist system is temporarily unavailable. Please try again later.");
      event.setResult(ResultedEvent.ComponentResult.denied(
          LegacyComponentSerializer.legacySection().deserialize(errorMessage)));
      return;
    }

    Player player = event.getPlayer();
    String username = player.getUsername();
    String uuid = player.getUniqueId().toString();
    String ip = player.getRemoteAddress().getAddress().getHostAddress();
    UUID playerUuid = player.getUniqueId();

    if (configProvider.getBoolean("logging.debug", false)) {
      logger.debug("Checking whitelist for " + username + " (" + uuid + ") from " + ip);
    }

    // Cache early-allow: a recently-verified player connects without an API call,
    // mirroring Paper. Previously the Velocity cache was constructed but never
    // read or written, so every login hit the API and an outage fail-closed the
    // whole proxy (issue #796 / MC-3).
    boolean cacheEnabled = configProvider.getBoolean("cache.enabled", true);
    Boolean cachedWhitelisted = cacheEnabled ? whitelistCache.isCachedWhitelisted(uuid, username) : null;
    if (cacheEnabled && cachedWhitelisted != null && cachedWhitelisted) {
      if (configProvider.getBoolean("logging.debug", false)) {
        logger.debug("Cache hit for " + username + ": allowing based on cache");
      }
      return;
    }

    // Get current LuckPerms groups if available
    List<String> currentGroups = null;
    if (luckPermsManager != null && luckPermsManager.isAvailable()) {
      currentGroups = luckPermsManager.getPlayerGroups(playerUuid);
      if (configProvider.getBoolean("logging.debug", false)) {
        logger.debug("Current LuckPerms groups for " + username + ": " + currentGroups);
      }
    }

    // LoginEvent fires after authentication, so we have the UUID
    try {
      WhitelistResponse response = whitelistManager.checkPlayerWhitelist(
          username,
          uuid,
          ip,
          currentGroups,
          configProvider.getString("server.publicIp", "localhost"),
          cachedWhitelisted != null);

      if (configProvider.getBoolean("logging.logDecisions", true)) {
        logger.info("Whitelist decision for " + username + ": " + response.toString());
      }

      if (response.shouldBeWhitelisted()) {
        // Allow connection
        if (configProvider.getBoolean("logging.debug", false)) {
          logger.debug("Allowing " + username + " to connect");
        }

        // Cache only players actually let through — never an unlinked player who
        // is about to be kicked for their link code, or the next attempt would
        // early-allow from cache and they'd never link (issue #796 / MC-3, MC-4).
        boolean mustShowAuthCode = "show_auth_code".equals(response.getAction());
        if (cacheEnabled && !mustShowAuthCode) {
          whitelistCache.addWhitelistedPlayer(uuid, username);
        }

        // Apply role sync if enabled and LuckPerms is available
        if (response.isRoleSyncEnabled() && response.getManagedGroups() != null
            && !response.getManagedGroups().isEmpty()) {

          if (luckPermsManager != null && luckPermsManager.isAvailable()) {
            logger.info("Scheduling role sync for " + username + " with target groups: "
                + response.getTargetGroups() +
                " and managed groups: " + response.getManagedGroups());

            // Schedule role sync after a short delay to ensure player is fully connected
            final List<String> targetGroups = response.getTargetGroups();
            final List<String> managedGroups = response.getManagedGroups();

            server.getScheduler().buildTask(this, () -> {
              try {
                luckPermsManager.setPlayerGroups(playerUuid, targetGroups, managedGroups)
                    .thenAccept(success -> {
                      if (success) {
                        logger.info("Successfully applied role sync for " + username);
                      } else {
                        logger.warning("Role sync returned false for " + username);
                      }
                    });
              } catch (Exception e) {
                logger.warning("Failed to apply role sync for " + username + ": " + e.getMessage());
              }
            }).delay(2, TimeUnit.SECONDS).schedule();
          } else {
            logger.warning("Role sync requested for " + username + " but LuckPerms is not available on Velocity");
          }
        }

        // If the action is to show an auth code, deny with the code message
        if (mustShowAuthCode) {
          event.setResult(ResultedEvent.ComponentResult.denied(
              colorize(response.getKickMessage())));
        }
        // Otherwise, let them through (don't modify result)
      } else {
        // Deny connection
        event.setResult(ResultedEvent.ComponentResult.denied(
            colorize(response.getKickMessage())));
      }

    } catch (Exception e) {
      logger.severe("Failed to check whitelist for " + username + ": " + e.getMessage());

      // Handle API failure based on configured fallback mode
      String fallbackMode = configProvider.getString("advanced.apiFallbackMode", "deny");

      switch (fallbackMode.toLowerCase()) {
        case "allow":
          logger.warning("API failed for " + username + ", allowing connection (fail-open mode)");
          // Don't modify event result - allow through
          break;

        case "whitelist-only":
          // Serve the warm cache as the outage fallback, matching Paper. Without
          // this branch the shipped default (apiFallbackMode: whitelist-only)
          // silently behaved as fail-closed "deny" on Velocity (issue #796 / MC-3).
          Boolean fallbackCached = whitelistCache.isCachedWhitelisted(uuid, username);
          if (fallbackCached != null && fallbackCached) {
            logger.warning("API failed for " + username + ", allowing based on positive cache");
            // Don't modify event result - allow through
          } else {
            logger.warning("API failed for " + username + ", denying (no positive cache entry)");
            String wlErrorMessage = configProvider.getString("messages.apiUnavailable",
                "§cWhitelist system is temporarily unavailable. Please try again later.");
            event.setResult(ResultedEvent.ComponentResult.denied(colorize(wlErrorMessage)));
          }
          break;

        case "deny":
        default:
          logger.warning("API failed for " + username + ", denying connection (fail-closed mode)");
          String errorMessage = configProvider.getString("messages.apiUnavailable",
              "§cWhitelist system is temporarily unavailable. Please try again later.");
          event.setResult(ResultedEvent.ComponentResult.denied(colorize(errorMessage)));
          break;
      }
    }
  }

  /**
   * Extend a connecting player's positive cache entry on join, matching Paper's
   * PaperJoinLeaveListener. No-op for players with no positive cache entry. The
   * extension is bounded by WhitelistCache's max-extension ceiling (issue #771).
   */
  @Subscribe
  public void onPostLogin(PostLoginEvent event) {
    Player player = event.getPlayer();

    if (whitelistCache != null && configProvider.getBoolean("cache.enabled", true)) {
      whitelistCache.extendCacheOnJoin(player.getUniqueId().toString(), player.getUsername());
    }

    // Notify admins when a plugin update is available.
    if (updateChecker != null
        && configProvider.getBoolean("updates.notifyAdmins", true)
        && updateChecker.isUpdateAvailable()
        && updateChecker.getLatestRelease() != null
        && player.hasPermission("heimdall.admin")) {
      String latest = updateChecker.getLatestRelease().getVersion();
      player.sendMessage(colorize("&e[HeimdallWhitelist] &7An update is available: &f"
          + updateChecker.getCurrentVersion() + " &7→ &a" + latest));
      player.sendMessage(colorize("&7Run &f/hwl update&7 to download it."));
    }
  }

  /**
   * Extend a player's positive cache entry on disconnect, matching Paper. No-op
   * for players with no positive cache entry.
   */
  @Subscribe
  public void onDisconnect(DisconnectEvent event) {
    if (whitelistCache == null || !configProvider.getBoolean("cache.enabled", true)) {
      return;
    }
    Player player = event.getPlayer();
    whitelistCache.extendCacheOnLeave(player.getUniqueId().toString(), player.getUsername());
  }

  /**
   * Convert legacy color codes to Component
   */
  private Component colorize(String message) {
    return LegacyComponentSerializer.legacySection().deserialize(message.replace('&', '§'));
  }

  /* ═══════════════════════ /hwl version & update ════════════════ */

  private void handleVersionCommand(CommandSource source) {
    source.sendMessage(colorize("&eHeimdall Whitelist &7v&f" + updateChecker.getCurrentVersion()));
    source.sendMessage(colorize("&7Checking for updates..."));
    server.getScheduler().buildTask(this, () -> {
      boolean available = updateChecker.checkNow();
      if (available && updateChecker.getLatestRelease() != null) {
        source.sendMessage(colorize("&aUpdate available: &f" + updateChecker.getLatestRelease().getVersion()));
        source.sendMessage(colorize("&7Run &f/hwl update&7 to download it."));
      } else {
        source.sendMessage(colorize("&aYou are running the latest version."));
      }
    }).schedule();
  }

  private void handleUpdateCommand(CommandSource source) {
    if (!updateChecker.isUpdateAvailable() || updateChecker.getLatestRelease() == null) {
      source.sendMessage(colorize("&eNo update available, or no check has run yet. Try &f/hwl version&e first."));
      return;
    }

    final PluginReleaseInfo rel = updateChecker.getLatestRelease();
    source.sendMessage(colorize("&eDownloading HeimdallWhitelist &f" + rel.getVersion() + "&e..."));

    server.getScheduler().buildTask(this, () -> {
      try {
        // Velocity has no plugins/update auto-apply folder — download into the
        // plugin's data directory and instruct a manual move + restart.
        File target = new File(dataDirectory.toFile(), "heimdall-whitelist-" + rel.getVersion() + ".jar");
        updateChecker.downloadUpdate(target);
        source.sendMessage(colorize("&aDownloaded to &f" + target.getAbsolutePath()));
        source.sendMessage(colorize(
            "&7Replace the old jar in your proxy's &fplugins/&7 folder with this file, then restart."));
      } catch (Exception e) {
        source.sendMessage(colorize("&cUpdate failed: " + e.getMessage()));
      }
    }).schedule();
  }

  /**
   * Admin command handler
   */
  private class HeimdallCommand implements SimpleCommand {

    @Override
    public void execute(Invocation invocation) {
      CommandSource source = invocation.source();
      String[] args = invocation.arguments();

      if (!source.hasPermission("heimdall.admin")) {
        source.sendMessage(colorize("&cYou don't have permission to use this command!"));
        return;
      }

      if (args.length == 0) {
        source.sendMessage(colorize("&eHeimdall Whitelist Commands (Velocity):"));
        source.sendMessage(colorize("&7/hwl reload - Reload configuration"));
        source.sendMessage(colorize("&7/hwl status - Show plugin status"));
        source.sendMessage(colorize("&7/hwl enable - Enable the whitelist plugin"));
        source.sendMessage(colorize("&7/hwl disable - Disable the whitelist plugin"));
        source.sendMessage(colorize("&7/hwl test <player> - Test whitelist check for player"));
        source.sendMessage(colorize("&7/hwl cache stats - Show cache statistics"));
        source.sendMessage(colorize("&7/hwl cache clear - Clear the whitelist cache"));
        source.sendMessage(colorize("&7/hwl offense reload - Refresh cached offense types"));
        source.sendMessage(colorize("&7/hwl offense types - List all offense types"));
        source.sendMessage(colorize("&7/hwl version - Show version & check for updates"));
        source.sendMessage(colorize("&7/hwl update - Download the latest version"));
        return;
      }

      String subCommand = args[0].toLowerCase();

      switch (subCommand) {
        case "reload":
          configProvider.reload();
          apiClient.updateConfig();
          logger.setDebugEnabled(configProvider.getBoolean("logging.debug", false));
          // Re-read WS settings in place. Rebuilding the client orphaned the old
          // scheduler + selector thread and dropped the message handler wiring.
          if (wsClient != null) {
            if (configProvider.getBoolean("websocket.enabled", false)) {
              wsClient.reconnect(apiClient.getGuildId());
            } else {
              wsClient.disconnect();
            }
          }
          source.sendMessage(
              colorize(configProvider.getString("messages.reloaded", "&aPlugin reloaded!")));
          break;

        case "status":
          boolean enabled = configProvider.getBoolean("enabled", false);
          String enabledStatus = enabled ? "&aENABLED" : "&cDISABLED";
          String luckPermsStatus = (luckPermsManager != null && luckPermsManager.isAvailable())
              ? "&aAVAILABLE"
              : "&cNOT AVAILABLE";

          String statusMsg = configProvider.getString("messages.status", "Status: OK")
              .replace("{url}", configProvider.getString("api.baseUrl", "Not set"))
              .replace("{serverId}", configProvider.getString("server.serverId", "Not set"))
              .replace("{lastCheck}", whitelistManager.getLastCheckTime());

          source.sendMessage(colorize(statusMsg));
          source.sendMessage(colorize("&7Plugin Status: " + enabledStatus));
          source.sendMessage(colorize("&7Platform: Velocity Proxy"));
          source.sendMessage(colorize("&7LuckPerms: " + luckPermsStatus));

          if (!enabled) {
            source.sendMessage(colorize(
                "&eWarning: Plugin is disabled. All players can join without whitelist checks!"));
            source.sendMessage(colorize("&7Enable with '/hwl enable'"));
          }

          if (luckPermsManager == null || !luckPermsManager.isAvailable()) {
            source.sendMessage(colorize("&eWarning: LuckPerms not available. Role sync will not work."));
          }
          break;

        case "enable":
          configProvider.set("enabled", true);
          configProvider.save();
          source.sendMessage(colorize("&aHeimdall Whitelist plugin enabled!"));
          source.sendMessage(colorize("&eWhitelist checks are now active for all players."));
          break;

        case "disable":
          configProvider.set("enabled", false);
          configProvider.save();
          source.sendMessage(colorize("&cHeimdall Whitelist plugin disabled!"));
          source.sendMessage(colorize("&eWarning: All players can now join without whitelist checks!"));
          break;

        case "test":
          if (args.length < 2) {
            source.sendMessage(colorize("&cUsage: /hwl test <username>"));
            return;
          }

          String testPlayer = args[1];
          source.sendMessage(colorize("&eTesting whitelist check for " + testPlayer + "..."));

          // Perform async test
          CompletableFuture.runAsync(() -> {
            try {
              String testUuid = createTestUuid(testPlayer);
              WhitelistResponse response = whitelistManager.checkPlayerWhitelist(
                  testPlayer,
                  testUuid,
                  "127.0.0.1");

              server.getScheduler().buildTask(HeimdallVelocityPlugin.this, () -> {
                source.sendMessage(colorize("&aTest Results for " + testPlayer + ":"));
                source.sendMessage(colorize("&7Should be whitelisted: "
                    + (response.shouldBeWhitelisted() ? "YES" : "NO")));
                source.sendMessage(colorize("&7Has auth: " + (response.hasAuth() ? "YES" : "NO")));
                source.sendMessage(colorize("&7Action: " + response.getAction()));
                source.sendMessage(colorize("&7Message: " + response.getKickMessage()));
              }).schedule();
            } catch (Exception e) {
              server.getScheduler().buildTask(HeimdallVelocityPlugin.this, () -> {
                source.sendMessage(colorize("&cTest failed: " + e.getMessage()));
              }).schedule();
            }
          });
          break;

        case "cache":
          if (args.length < 2) {
            source.sendMessage(colorize("&cUsage: /hwl cache <stats|clear>"));
            return;
          }

          String cacheSubCommand = args[1].toLowerCase();
          switch (cacheSubCommand) {
            case "stats":
              source.sendMessage(colorize("&eWhitelist Cache Statistics:"));
              source.sendMessage(colorize("&7" + whitelistCache.getCacheStats()));
              break;

            case "clear":
              whitelistCache.clear();
              whitelistManager.clearCache();
              source.sendMessage(colorize("&aWhitelist cache cleared successfully!"));
              break;

            default:
              source.sendMessage(colorize("&cUnknown cache subcommand: " + cacheSubCommand));
              source.sendMessage(colorize("&7Available: stats, clear"));
              break;
          }
          break;

        case "offense":
          handleOffenseSubcommand(source, args);
          break;

        case "version":
          handleVersionCommand(source);
          break;

        case "update":
          handleUpdateCommand(source);
          break;

        default:
          source.sendMessage(colorize("&cUnknown subcommand: " + subCommand));
          break;
      }
    }

    @Override
    public List<String> suggest(Invocation invocation) {
      String[] args = invocation.arguments();
      if (args.length <= 1) {
        return Arrays.asList("reload", "status", "enable", "disable", "test", "cache", "offense", "version", "update");
      } else if (args.length == 2 && args[0].equalsIgnoreCase("cache")) {
        return Arrays.asList("stats", "clear");
      } else if (args.length == 2 && args[0].equalsIgnoreCase("offense")) {
        return Arrays.asList("reload", "types");
      }
      return Arrays.asList();
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
      return invocation.source().hasPermission("heimdall.admin");
    }
  }

  /* ════════════════════ /hwl offense handler ═════════════════════ */

  private void handleOffenseSubcommand(CommandSource source, String[] args) {
    if (args.length < 2) {
      source.sendMessage(colorize("&cUsage: /hwl offense <reload|types>"));
      return;
    }

    String sub = args[1].toLowerCase();

    switch (sub) {
      case "reload":
        source.sendMessage(colorize("&eRefreshing offense types..."));
        CompletableFuture.runAsync(() -> {
          offenseManager.refresh();
          server.getScheduler().buildTask(HeimdallVelocityPlugin.this, () -> {
            source.sendMessage(colorize("&aOffense types refreshed. " + offenseManager.getTypes().size()
                + " types loaded."));
          }).schedule();
        });
        break;

      case "types":
        List<OffenseTypeInfo> types = offenseManager.getTypes();
        if (types.isEmpty()) {
          source.sendMessage(colorize("&eNo offense types loaded. Use &f/hwl offense reload&e to fetch."));
          return;
        }
        source.sendMessage(colorize("&eOffense Types (" + types.size() + "):"));
        for (OffenseTypeInfo type : types) {
          String status = type.isEnabled() ? "&a\u2713" : "&c\u2717";
          source.sendMessage(colorize(
              status + " &f" + type.getDisplayName() + " &7(" + type.getTypeId() + ")"));
          source.sendMessage(colorize(
              "  &7Offenses: " + String.join(", ", type.getOffenses())));
        }
        break;

      default:
        source.sendMessage(colorize("&cUnknown offense subcommand: " + sub));
        source.sendMessage(colorize("&7Available: reload, types"));
        break;
    }
  }

  /* ══════════════════════ /offend command ════════════════════════ */

  private class OffendCommand implements SimpleCommand {

    @Override
    public void execute(Invocation invocation) {
      CommandSource source = invocation.source();
      String[] args = invocation.arguments();

      if (!source.hasPermission("heimdall.offend")) {
        source.sendMessage(colorize("&cYou don't have permission to use this command!"));
        return;
      }

      if (args.length < 2) {
        source.sendMessage(colorize("&cUsage: /offend <player> <offense> [notes]"));
        return;
      }

      if (!configProvider.getBoolean("enabled", false)) {
        source.sendMessage(colorize("&cWhitelist system is currently disabled."));
        return;
      }

      String targetName = args[0];
      String offenseSlug = args[1].toLowerCase();

      // Collect optional notes
      String notes = null;
      if (args.length > 2) {
        notes = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
      }

      // Resolve target UUID. Offense history is keyed by UUID on the bot, so a
      // fabricated UUID would split the player's record (issue #797 / MC-7).
      // Velocity (a proxy) has no offline-player UUID cache, so an offline target
      // can't be resolved to a real UUID — reject rather than fabricate one.
      String targetUuid;
      Player targetPlayer = server.getPlayer(targetName).orElse(null);
      if (targetPlayer != null) {
        targetUuid = targetPlayer.getUniqueId().toString();
        targetName = targetPlayer.getUsername();
      } else {
        source.sendMessage(colorize("&cCould not resolve &f" + targetName
            + "&c — the player must be online to receive an offense on Velocity."));
        return;
      }

      // Resolve issuer info
      String issuedByUuid = null;
      String issuedByUsername = null;
      if (source instanceof Player) {
        Player issuer = (Player) source;
        issuedByUuid = issuer.getUniqueId().toString();
        issuedByUsername = issuer.getUsername();
      } else {
        issuedByUsername = "Console";
      }

      source.sendMessage(colorize("&eRecording offense &f" + offenseSlug + "&e against &f" + targetName + "&e..."));

      final String finalTargetName = targetName;
      final String finalTargetUuid = targetUuid;
      final String finalNotes = notes;
      final String finalIssuedByUuid = issuedByUuid;
      final String finalIssuedByUsername = issuedByUsername;

      CompletableFuture.runAsync(() -> {
        try {
          OffenseResponse response = apiClient.offend(finalTargetUuid, finalTargetName,
              offenseSlug, finalIssuedByUuid, finalIssuedByUsername, finalNotes).join();

          server.getScheduler().buildTask(HeimdallVelocityPlugin.this, () -> {
            source.sendMessage(colorize("&aOffense recorded for " + finalTargetName + ":"));
            source.sendMessage(colorize("&7Type: &f" + response.getOffenseType()));
            source.sendMessage(colorize("&7Action: &f" + response.getTierDescription()
                + " &7(tier " + response.getTierApplied() + ")"));
            source.sendMessage(colorize("&7Total points: &f" + response.getTotalPoints()));

            String command = response.getCommand();
            if (command != null && !command.isEmpty()) {
              source.sendMessage(colorize("&7Dispatching: &f" + command));
              server.getCommandManager().executeAsync(server.getConsoleCommandSource(), command);
            }
          }).schedule();
        } catch (Exception e) {
          server.getScheduler().buildTask(HeimdallVelocityPlugin.this, () -> {
            String msg = extractThrowableMessage(e);
            source.sendMessage(colorize("&cFailed to record offense: " + msg));
          }).schedule();
        }
      });
    }

    @Override
    public List<String> suggest(Invocation invocation) {
      String[] args = invocation.arguments();
      if (args.length <= 1) {
        // Player names
        String prefix = args.length == 0 ? "" : args[0].toLowerCase();
        return server.getAllPlayers().stream()
            .map(Player::getUsername)
            .filter(n -> n.toLowerCase().startsWith(prefix))
            .sorted()
            .collect(Collectors.toList());
      } else if (args.length == 2) {
        // Offense slugs
        return offenseManager.getMatchingSlugs(args[1]);
      }
      return Collections.emptyList();
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
      return invocation.source().hasPermission("heimdall.offend");
    }
  }

  /**
   * Player command to request a Discord link code (Velocity parity with Paper).
   */
  private class LinkDiscordCommand implements SimpleCommand {

    @Override
    public void execute(Invocation invocation) {
      CommandSource source = invocation.source();

      if (!(source instanceof Player)) {
        source.sendMessage(colorize("&cThis command can only be used by players!"));
        return;
      }

      Player player = (Player) source;

      if (!player.hasPermission("heimdall.linkdiscord")) {
        player.sendMessage(colorize("&cYou don't have permission to use this command!"));
        return;
      }

      if (!configProvider.getBoolean("enabled", false)) {
        player.sendMessage(colorize("&cWhitelist system is currently disabled. Please contact an administrator."));
        return;
      }

      // Simple cooldown (30s) to prevent spam; staff can bypass
      if (!player.hasPermission("heimdall.bypass")) {
        long now = System.currentTimeMillis();
        long lastUsed = linkCooldowns.getOrDefault(player.getUniqueId(), 0L);
        long cooldownMs = 30_000L;

        if (now - lastUsed < cooldownMs) {
          long remainingSeconds = (cooldownMs - (now - lastUsed)) / 1000;
          player
              .sendMessage(colorize("&cPlease wait " + remainingSeconds + " seconds before using this command again."));
          return;
        }

        linkCooldowns.put(player.getUniqueId(), now);
      }

      player.sendMessage(colorize("&eRequesting Discord link code..."));

      // Run API call off the main thread to avoid blocking the proxy
      CompletableFuture.supplyAsync(() -> {
        try {
          return whitelistManager.requestLinkCode(player.getUsername().toLowerCase(), player.getUniqueId().toString());
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }).thenAccept(code -> {
        server.getScheduler().buildTask(HeimdallVelocityPlugin.this, () -> {
          StringBuilder borderBuilder = new StringBuilder();
          for (int i = 0; i < 50; i++) {
            borderBuilder.append("=");
          }
          String border = "&a" + borderBuilder.toString();
          player.sendMessage(colorize(border));
          player.sendMessage(colorize("&eYour Discord Link Code: &a&l" + code));
          player.sendMessage(colorize("&7Go to Discord and use: &f/confirm-code " + code));
          player.sendMessage(colorize("&7This code expires in 5 minutes"));
          player.sendMessage(colorize(border));
        }).schedule();
      }).exceptionally(ex -> {
        server.getScheduler().buildTask(HeimdallVelocityPlugin.this, () -> {
          String errorMessage = extractUserFacingLinkError(ex);
          String rawErrorMessage = extractThrowableMessage(ex);

          if (isAlreadyLinkedMessage(errorMessage)) {
            player.sendMessage(colorize("&eThis Minecraft account is already linked."));
            if (errorMessage != null && !errorMessage.isBlank()) {
              String info = stripAlreadyLinkedLead(errorMessage);
              if (info != null && !info.isBlank()) {
                player.sendMessage(colorize("&7" + info));
              }
            }
          } else if (containsIgnoreCase(errorMessage, "No linkable account")) {
            player.sendMessage(colorize(
                "&cYou don't have a linkable account. You may already be linked to Discord, or you're not whitelisted on this server."));
          } else if (containsIgnoreCase(rawErrorMessage, "API request failed")
              || containsIgnoreCase(errorMessage, "HTTP ")) {
            player.sendMessage(colorize(
                "&cFailed to generate link code. Please try again in a moment or contact staff if this persists."));
          } else {
            player.sendMessage(colorize(
                "&cFailed to generate link code. Please try again or contact staff if this persists."));
          }
          logger.warning("Link code generation failed for " + player.getUsername() + ": " + rawErrorMessage);
        }).schedule();
        return null;
      });
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
      return invocation.source().hasPermission("heimdall.linkdiscord");
    }
  }

  private String extractUserFacingLinkError(Throwable throwable) {
    return extractUserFacingLinkError(extractThrowableMessage(throwable));
  }

  private String extractThrowableMessage(Throwable throwable) {
    Throwable current = throwable;
    String message = null;

    while (current != null) {
      if (current.getMessage() != null && !current.getMessage().isBlank()) {
        message = current.getMessage();
      }
      current = current.getCause();
    }

    return message;
  }

  private boolean isAlreadyLinkedMessage(String errorMessage) {
    if (errorMessage == null) {
      return false;
    }

    String normalized = errorMessage.toLowerCase();
    return normalized.contains("already linked") || normalized.contains("linked to");
  }

  private String createTestUuid(String username) {
    return UUID.nameUUIDFromBytes(("heimdall-test:" + username.toLowerCase()).getBytes(StandardCharsets.UTF_8))
        .toString();
  }

  private boolean containsIgnoreCase(String value, String needle) {
    if (value == null || needle == null) {
      return false;
    }

    return value.toLowerCase().contains(needle.toLowerCase());
  }

  private String stripAlreadyLinkedLead(String errorMessage) {
    if (errorMessage == null) {
      return null;
    }

    String normalized = errorMessage.toLowerCase();
    if (normalized.startsWith("your minecraft account is already linked to")
        || normalized.startsWith("this minecraft account is already linked to")) {
      return null;
    }

    if (normalized.startsWith("your minecraft account is already linked.")
        || normalized.startsWith("this minecraft account is already linked.")) {
      int sentenceEnd = errorMessage.indexOf('.');
      if (sentenceEnd >= 0 && sentenceEnd + 1 < errorMessage.length()) {
        String detail = errorMessage.substring(sentenceEnd + 1).trim();
        return detail.isBlank() ? null : detail;
      }
      return null;
    }

    return errorMessage;
  }

  private String extractUserFacingLinkError(String rawMessage) {
    if (rawMessage == null || rawMessage.isBlank()) {
      return null;
    }

    int lastRuntimeIdx = rawMessage.lastIndexOf("RuntimeException:");
    String cleaned = lastRuntimeIdx >= 0 ? rawMessage.substring(lastRuntimeIdx + "RuntimeException:".length())
        : rawMessage;

    cleaned = cleaned
        .replace("java.util.concurrent.ExecutionException:", "")
        .replace("java.lang.RuntimeException:", "")
        .replace("API request failed:", "")
        .trim();

    return cleaned.isBlank() ? null : cleaned;
  }

  // Getters for other classes if needed
  public VelocityLogger getLogger() {
    return logger;
  }

  public VelocityConfigProvider getConfigProvider() {
    return configProvider;
  }

  public ApiClient getApiClient() {
    return apiClient;
  }

  public WhitelistManager getWhitelistManager() {
    return whitelistManager;
  }

  public WhitelistCache getWhitelistCache() {
    return whitelistCache;
  }

  public VelocityLuckPermsManager getLuckPermsManager() {
    return luckPermsManager;
  }

  public ProxyServer getServer() {
    return server;
  }

  public WebSocketClient getWsClient() {
    return wsClient;
  }

  /* ═══════════════════════ WebSocket message handler ═════════════════════ */

  private JsonObject buildIdentifyMetadata() {
    JsonObject m = new JsonObject();
    m.addProperty("pluginVersion", BuildConstants.VERSION);
    m.addProperty("platform", "velocity");
    m.addProperty("serverSoftware",
        server.getVersion().getName() + " " + server.getVersion().getVersion());
    m.addProperty("mcVersion", "");
    m.addProperty("startedAt", startedAtMs);
    return m;
  }

  private JsonObject buildHealthSnapshot() {
    JsonObject h = new JsonObject();
    // No TPS on a proxy — report online count + memory only.
    h.addProperty("onlinePlayers", server.getPlayerCount());
    Runtime rt = Runtime.getRuntime();
    h.addProperty("usedMemMb", (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024));
    h.addProperty("maxMemMb", rt.maxMemory() / (1024 * 1024));
    return h;
  }

  private void handleWsMessage(String type, JsonObject msg) {
    String id = msg.has("id") ? msg.get("id").getAsString() : "";
    JsonObject payload = msg.has("payload") ? msg.getAsJsonObject("payload") : new JsonObject();

    switch (type) {
      case "get_players": {
        JsonArray players = new JsonArray();
        for (Player p : server.getAllPlayers()) {
          JsonObject pj = new JsonObject();
          pj.addProperty("uuid", p.getUniqueId().toString());
          pj.addProperty("username", p.getUsername());
          pj.addProperty("server", p.getCurrentServer()
              .map(s -> s.getServerInfo().getName()).orElse("unknown"));
          players.add(pj);
        }
        JsonObject respPayload = new JsonObject();
        respPayload.add("players", players);
        wsClient.reply(id, "player_list", respPayload);
        break;
      }
      case "run_command": {
        String command = payload.has("command") ? payload.get("command").getAsString() : "";
        if (command.isEmpty())
          break;
        server.getCommandManager().executeAsync(server.getConsoleCommandSource(), command);
        JsonObject ack = new JsonObject();
        ack.addProperty("output", "Command dispatched: " + command);
        wsClient.reply(id, "command_result", ack);
        break;
      }
      case "probe_player": {
        // Velocity doesn't have Trace — proxy primarily handles get_players/run_command
        String uuid = payload.has("uuid") ? payload.get("uuid").getAsString() : "";
        logger.info("[WS] probe_player request for " + uuid + " — not applicable on proxy");
        JsonObject ack = new JsonObject();
        ack.addProperty("error", "Trace not available on proxy");
        wsClient.reply(id, "probe_result", ack);
        break;
      }
      case "update": {
        // Dashboard-triggered remote update — Velocity has no update folder, so
        // download into the plugin data dir and report where it landed.
        server.getScheduler().buildTask(this, () -> {
          JsonObject result = new JsonObject();
          try {
            boolean available = updateChecker.checkNow();
            if (!available || updateChecker.getLatestRelease() == null) {
              result.addProperty("success", false);
              result.addProperty("message", "Already up to date");
            } else {
              File target = new File(dataDirectory.toFile(),
                  "heimdall-whitelist-" + updateChecker.getLatestRelease().getVersion() + ".jar");
              updateChecker.downloadUpdate(target);
              result.addProperty("success", true);
              result.addProperty("version", updateChecker.getLatestRelease().getVersion());
              result.addProperty("message", "Downloaded to plugin data dir; move to plugins/ and restart");
            }
          } catch (Exception e) {
            result.addProperty("success", false);
            result.addProperty("message", e.getMessage());
          }
          wsClient.reply(id, "update_result", result);
        }).schedule();
        break;
      }

      default:
        if (configProvider.getBoolean("logging.debug", false)) {
          logger.debug("[WS] Unhandled message type: " + type);
        }
    }
  }
}
