package com.heimdall.whitelist.paper;

import com.heimdall.whitelist.BuildConstants;
import com.heimdall.whitelist.api.HeimdallTunnel;
import com.heimdall.whitelist.core.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Main Paper/Bukkit plugin class for Heimdall Whitelist
 */
public class HeimdallPaperPlugin extends JavaPlugin implements Listener {

  private PluginLogger logger;
  private ConfigProvider configProvider;
  private ApiClient apiClient;
  private WhitelistManager whitelistManager;
  private WhitelistCache whitelistCache;
  private PaperLuckPermsManager luckPermsManager;
  private OffenseManager offenseManager;
  private WebSocketClient wsClient;
  private HeimdallTunnelImpl tunnel;
  private ConsoleStreamer consoleStreamer;
  private UpdateChecker updateChecker;
  private final long startedAtMs = System.currentTimeMillis();
  private int cacheCleanupTaskId = -1;
  private int offenseRefreshTaskId = -1;
  private int updateCheckTaskId = -1;
  private int consoleFlushTaskId = -1;
  /**
   * In-memory /linkdiscord cooldowns, keyed by player UUID. Previously these were
   * written into the live config object and persisted by saveConfig(), so the
   * file grew one key per player forever and /hwl reload wiped them all (cooldown
   * bypass). Matches the Velocity implementation (issue #796 / MC-8).
   */
  private final Map<UUID, Long> linkCooldowns = new ConcurrentHashMap<>();

  @Override
  public void onEnable() {
    // Save default config if it doesn't exist
    saveDefaultConfig();

    // Initialize adapters
    logger = new PaperLogger(this);
    configProvider = new PaperConfigProvider(this);

    // Initialize core managers
    apiClient = new ApiClient(logger, configProvider);

    // Verify guildId is configured
    String gid = apiClient.getGuildId();
    if (gid == null || gid.isEmpty()) {
      logger.warning("================================================");
      logger.warning("  'api.guildId' is not set in config.yml!");
      logger.warning("  The whitelist plugin will NOT process joins");
      logger.warning("  until a guild ID is configured.");
      logger.warning("================================================");
    }

    whitelistManager = new WhitelistManager(logger, configProvider, apiClient);

    // Initialize LuckPerms integration only if LuckPerms is available
    if (getServer().getPluginManager().getPlugin("LuckPerms") != null) {
      try {
        luckPermsManager = new PaperLuckPermsManager(this, logger);
      } catch (NoClassDefFoundError e) {
        logger.warning("LuckPerms plugin found but API classes not available. Role sync disabled.");
        luckPermsManager = null;
      }
    } else {
      logger.info("LuckPerms not detected. Role sync features will be disabled.");
      luckPermsManager = null;
    }

    // Initialize whitelist cache
    long cacheWindow = getConfig().getLong("cache.cacheWindow", 60);
    long extendOnJoin = getConfig().getLong("cache.extendOnJoin", 120);
    long extendOnLeave = getConfig().getLong("cache.extendOnLeave", 180);
    long maxExtensionHours = getConfig().getLong("cache.maxExtensionHours", 24);
    whitelistCache = new WhitelistCache(logger, getDataFolder(), cacheWindow, extendOnJoin, extendOnLeave,
        maxExtensionHours);

    // Initialize offense manager
    offenseManager = new OffenseManager(logger, apiClient);
    // Fetch offense types async on startup
    getServer().getScheduler().runTaskAsynchronously(this, () -> offenseManager.refresh());
    // Refresh offense types every 5 minutes
    offenseRefreshTaskId = getServer().getScheduler().runTaskTimerAsynchronously(this,
        () -> offenseManager.refresh(), 5 * 60 * 20L, 5 * 60 * 20L).getTaskId();

    // Initialize update checker (asks the bot for the latest published version)
    updateChecker = new UpdateChecker(logger, apiClient);
    if (getConfig().getBoolean("updates.checkEnabled", true)) {
      long intervalHours = Math.max(1, getConfig().getLong("updates.checkIntervalHours", 12));
      long intervalTicks = intervalHours * 60 * 60 * 20L;
      // Initial check shortly after boot, then on the configured interval.
      updateCheckTaskId = getServer().getScheduler().runTaskTimerAsynchronously(this,
          () -> updateChecker.checkNow(), 20L * 10, intervalTicks).getTaskId();
    }

    // Register events
    getServer().getPluginManager().registerEvents(new PaperLoginListener(this), this);
    getServer().getPluginManager().registerEvents(new PaperJoinLeaveListener(this), this);

    // Start cache cleanup task
    long cleanupInterval = getConfig().getLong("cache.cleanupInterval", 30) * 60 * 20; // Convert minutes to ticks
    cacheCleanupTaskId = getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
      whitelistCache.cleanupExpiredEntries();
    }, cleanupInterval, cleanupInterval).getTaskId();

    // Generate server ID if not set
    if (getConfig().getString("server.serverId", "").isEmpty()) {
      String serverId = UUID.randomUUID().toString();
      getConfig().set("server.serverId", serverId);
      saveConfig();
      logger.info("Generated new server ID: " + serverId);
    }

    logger.info("Heimdall Whitelist Plugin (Paper) enabled successfully!");
    logger.info("API URL: " + getConfig().getString("api.baseUrl"));
    logger.info("Server ID: " + getConfig().getString("server.serverId"));

    // Check if plugin is enabled and warn accordingly
    boolean enabled = getConfig().getBoolean("enabled", false);
    if (enabled) {
      logger.info("Whitelist protection is ACTIVE - all players will be checked");
    } else {
      logger.warning("================================================");
      logger.warning("WHITELIST PROTECTION IS DISABLED!");
      logger.warning("All players can join without Discord verification!");
      logger.warning("Enable in config.yml or use '/hwl enable' command");
      logger.warning("================================================");
    }

    // Initialize WebSocket tunnel
    wsClient = new WebSocketClient(logger, configProvider);
    wsClient.setGuildId(apiClient.getGuildId());
    wsClient.setMessageHandler((type, msg) -> handleWsMessage(type, msg));
    wsClient.setIdentifyMetadataSupplier(this::buildIdentifyMetadata);
    wsClient.setHealthSupplier(this::buildHealthSnapshot);

    // Expose the socket as a shared service so other plugins (e.g. Trace) can
    // ride this one connection instead of opening their own.
    tunnel = new HeimdallTunnelImpl(wsClient);
    getServer().getServicesManager().register(HeimdallTunnel.class, tunnel, this,
        org.bukkit.plugin.ServicePriority.Normal);

    if (getConfig().getBoolean("websocket.enabled", false)) {
      wsClient.connect();
    }

    // Console streaming: capture INFO+ log lines and ship them to the bot over
    // the same tunnel. Only attach when explicitly enabled.
    if (getConfig().getBoolean("console.stream", true)) {
      consoleStreamer = new ConsoleStreamer(wsClient, logger);
      consoleStreamer.attach();
      // Drain + flush ~once per second on an async thread.
      consoleFlushTaskId = getServer().getScheduler().runTaskTimerAsynchronously(this,
          () -> consoleStreamer.flush(), 20L, 20L).getTaskId();
    }
  }

  @Override
  public void onDisable() {
    // Cancel cache cleanup task
    if (cacheCleanupTaskId != -1) {
      getServer().getScheduler().cancelTask(cacheCleanupTaskId);
    }

    // Cancel offense refresh task
    if (offenseRefreshTaskId != -1) {
      getServer().getScheduler().cancelTask(offenseRefreshTaskId);
    }

    // Cancel update check task
    if (updateCheckTaskId != -1) {
      getServer().getScheduler().cancelTask(updateCheckTaskId);
    }

    // Stop console streaming (cancel flush task + detach appender)
    if (consoleFlushTaskId != -1) {
      getServer().getScheduler().cancelTask(consoleFlushTaskId);
    }
    if (consoleStreamer != null) {
      consoleStreamer.detach();
    }

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

  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    String commandName = command.getName().toLowerCase();

    if (commandName.equalsIgnoreCase("linkdiscord")) {
      return handleLinkDiscordCommand(sender, args);
    }

    if (commandName.equalsIgnoreCase("offend")) {
      return handleOffendCommand(sender, args);
    }

    if (!commandName.equalsIgnoreCase("hwl")) {
      return false;
    }

    if (!sender.hasPermission("heimdall.admin")) {
      sender.sendMessage(colorize("&cYou don't have permission to use this command!"));
      return true;
    }

    if (args.length == 0) {
      sender.sendMessage(colorize("&eHeimdall Whitelist Commands:"));
      sender.sendMessage(colorize("&7/hwl reload - Reload configuration"));
      sender.sendMessage(colorize("&7/hwl status - Show plugin status"));
      sender.sendMessage(colorize("&7/hwl enable - Enable the whitelist plugin"));
      sender.sendMessage(colorize("&7/hwl disable - Disable the whitelist plugin"));
      sender.sendMessage(colorize("&7/hwl test <player> - Test whitelist check for player"));
      sender.sendMessage(colorize("&7/hwl cache stats - Show cache statistics"));
      sender.sendMessage(colorize("&7/hwl cache clear - Clear the whitelist cache"));
      sender.sendMessage(colorize("&7/hwl cache cleanup - Clean up expired cache entries"));
      sender.sendMessage(colorize("&7/hwl offense reload - Refresh cached offense types"));
      sender.sendMessage(colorize("&7/hwl offense types - List all offense types"));
      sender.sendMessage(colorize("&7/hwl version - Show version & check for updates"));
      sender.sendMessage(colorize("&7/hwl update - Download the latest version (applied on restart)"));
      return true;
    }

    String subCommand = args[0].toLowerCase();

    switch (subCommand) {
      case "reload":
        reloadConfig();
        apiClient.updateConfig();
        // Re-read WS settings in place. Rebuilding the client orphaned the old
        // scheduler + selector thread and dropped the message handler wiring.
        if (wsClient != null) {
          if (getConfig().getBoolean("websocket.enabled", false)) {
            wsClient.reconnect(apiClient.getGuildId());
          } else {
            wsClient.disconnect();
          }
        }
        sender.sendMessage(colorize(getConfig().getString("messages.reloaded", "&aPlugin reloaded!")));
        return true;

      case "status":
        boolean enabled = getConfig().getBoolean("enabled", false);
        String enabledStatus = enabled ? "&aENABLED" : "&cDISABLED";

        String statusMsg = getConfig().getString("messages.status", "Status: OK")
            .replace("{url}", getConfig().getString("api.baseUrl", "Not set"))
            .replace("{serverId}", getConfig().getString("server.serverId", "Not set"))
            .replace("{lastCheck}", whitelistManager.getLastCheckTime());

        sender.sendMessage(colorize(statusMsg));
        sender.sendMessage(colorize("&7Plugin Status: " + enabledStatus));

        if (!enabled) {
          sender.sendMessage(
              colorize("&eWarning: Plugin is disabled. All players can join without whitelist checks!"));
          sender.sendMessage(colorize("&7Enable in config.yml by setting 'enabled: true'"));
        }
        return true;

      case "enable":
        getConfig().set("enabled", true);
        saveConfig();
        sender.sendMessage(colorize("&aHeimdall Whitelist plugin enabled!"));
        sender.sendMessage(colorize("&eWhitelist checks are now active for all players."));
        return true;

      case "disable":
        getConfig().set("enabled", false);
        saveConfig();
        sender.sendMessage(colorize("&cHeimdall Whitelist plugin disabled!"));
        sender.sendMessage(colorize("&eWarning: All players can now join without whitelist checks!"));
        return true;

      case "test":
        if (args.length < 2) {
          sender.sendMessage(colorize("&cUsage: /hwl test <username>"));
          return true;
        }

        String testPlayer = args[1];
        sender.sendMessage(colorize("&eTesting whitelist check for " + testPlayer + "..."));

        // Perform async test
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
          try {
            String testUuid = createTestUuid(testPlayer);
            WhitelistResponse response = whitelistManager.checkPlayerWhitelist(
                testPlayer,
                testUuid,
                sender instanceof Player
                    ? ((Player) sender).getAddress().getAddress().getHostAddress()
                    : "127.0.0.1");

            getServer().getScheduler().runTask(this, () -> {
              sender.sendMessage(colorize("&aTest Results for " + testPlayer + ":"));
              sender.sendMessage(colorize(
                  "&7Should be whitelisted: " + (response.shouldBeWhitelisted() ? "YES" : "NO")));
              sender.sendMessage(colorize("&7Has auth: " + (response.hasAuth() ? "YES" : "NO")));
              sender.sendMessage(colorize("&7Action: " + response.getAction()));
              if (response.getQueuePosition() > 0) {
                sender.sendMessage(colorize("&7Queue position: &b#" + response.getQueuePosition()));
              }
              sender.sendMessage(colorize("&7Message: " + response.getKickMessage()));
            });
          } catch (Exception e) {
            getServer().getScheduler().runTask(this, () -> {
              sender.sendMessage(colorize("&cTest failed: " + e.getMessage()));
            });
          }
        });
        return true;

      case "cache":
        if (args.length < 2) {
          sender.sendMessage(colorize("&cUsage: /hwl cache <stats|clear|cleanup>"));
          return true;
        }

        String cacheSubCommand = args[1].toLowerCase();
        switch (cacheSubCommand) {
          case "stats":
            sender.sendMessage(colorize("&eWhitelist Cache Statistics:"));
            sender.sendMessage(colorize("&7" + whitelistCache.getCacheStats()));
            return true;

          case "clear":
            whitelistCache.clear();
            whitelistManager.clearCache();
            sender.sendMessage(colorize("&aWhitelist cache cleared successfully!"));
            return true;

          case "cleanup":
            whitelistCache.cleanupExpiredEntries();
            sender.sendMessage(colorize("&aExpired cache entries cleaned up!"));
            return true;

          default:
            sender.sendMessage(colorize("&cUnknown cache subcommand: " + cacheSubCommand));
            sender.sendMessage(colorize("&7Available: stats, clear, cleanup"));
            return true;
        }

      case "offense":
        return handleOffenseSubcommand(sender, args);

      case "version":
        return handleVersionCommand(sender);

      case "update":
        return handleUpdateCommand(sender);

      default:
        sender.sendMessage(colorize("&cUnknown subcommand: " + subCommand));
        return false;
    }
  }

  /* ═══════════════════════ /hwl version & update ════════════════ */

  private boolean handleVersionCommand(CommandSender sender) {
    sender.sendMessage(colorize("&eHeimdall Whitelist &7v&f" + updateChecker.getCurrentVersion()));
    sender.sendMessage(colorize("&7Checking for updates..."));
    getServer().getScheduler().runTaskAsynchronously(this, () -> {
      boolean available = updateChecker.checkNow();
      getServer().getScheduler().runTask(this, () -> {
        if (available && updateChecker.getLatestRelease() != null) {
          sender.sendMessage(colorize("&aUpdate available: &f" + updateChecker.getLatestRelease().getVersion()));
          sender.sendMessage(colorize("&7Run &f/hwl update&7 to download it (applied on restart)."));
        } else {
          sender.sendMessage(colorize("&aYou are running the latest version."));
        }
      });
    });
    return true;
  }

  private boolean handleUpdateCommand(CommandSender sender) {
    if (!updateChecker.isUpdateAvailable() || updateChecker.getLatestRelease() == null) {
      sender.sendMessage(colorize("&eNo update available, or no check has run yet. Try &f/hwl version&e first."));
      return true;
    }

    sender.sendMessage(colorize("&eDownloading HeimdallWhitelist &f"
        + updateChecker.getLatestRelease().getVersion() + "&e..."));

    getServer().getScheduler().runTaskAsynchronously(this, () -> {
      try {
        // Paper applies a JAR dropped in plugins/update/ (matching the current
        // plugin's file name) on the next server start.
        File updateFolder = new File(getDataFolder().getParentFile(), "update");
        File target = new File(updateFolder, getFile().getName());
        updateChecker.downloadUpdate(target);
        getServer().getScheduler().runTask(this,
            () -> sender.sendMessage(colorize("&aUpdate downloaded! Restart the server to apply it.")));
      } catch (Exception e) {
        getServer().getScheduler().runTask(this,
            () -> sender.sendMessage(colorize("&cUpdate failed: " + e.getMessage())));
      }
    });
    return true;
  }

  private boolean handleLinkDiscordCommand(CommandSender sender, String[] args) {
    if (!(sender instanceof Player)) {
      sender.sendMessage(colorize("&cThis command can only be used by players!"));
      return true;
    }

    if (!sender.hasPermission("heimdall.linkdiscord")) {
      sender.sendMessage(colorize("&cYou don't have permission to use this command!"));
      return true;
    }

    Player player = (Player) sender;
    String username = player.getName().toLowerCase();
    String uuid = player.getUniqueId().toString();

    if (!getConfig().getBoolean("enabled", false)) {
      sender.sendMessage(colorize("&cWhitelist system is currently disabled. Please contact an administrator."));
      return true;
    }

    long currentTime = System.currentTimeMillis();
    UUID playerId = player.getUniqueId();
    long lastUsed = linkCooldowns.getOrDefault(playerId, 0L);
    long cooldownTime = 30000;

    if (!player.hasPermission("heimdall.bypass") && (currentTime - lastUsed) < cooldownTime) {
      long remainingSeconds = (cooldownTime - (currentTime - lastUsed)) / 1000;
      sender.sendMessage(
          colorize("&cPlease wait " + remainingSeconds + " seconds before using this command again."));
      return true;
    }

    linkCooldowns.put(playerId, currentTime);

    sender.sendMessage(colorize("&eRequesting Discord link code..."));

    getServer().getScheduler().runTaskAsynchronously(this, () -> {
      try {
        String authCode = whitelistManager.requestLinkCode(username, uuid);

        getServer().getScheduler().runTask(this, () -> {
          StringBuilder borderBuilder = new StringBuilder();
          for (int i = 0; i < 50; i++) {
            borderBuilder.append("=");
          }
          String border = "&a" + borderBuilder;
          player.sendMessage(colorize(border));
          player.sendMessage(colorize("&eYour Discord Link Code: &a&l" + authCode));
          player.sendMessage(colorize("&7Go to Discord and use: &f/confirm-code " + authCode));
          player.sendMessage(colorize("&7This code expires in 5 minutes"));
          player.sendMessage(colorize(border));
        });
      } catch (Exception e) {
        getServer().getScheduler().runTask(this, () -> {
          String errorMessage = extractUserFacingLinkError(e);
          String rawErrorMessage = extractThrowableMessage(e);

          if (isAlreadyLinkedMessage(errorMessage)) {
            player.sendMessage(colorize("&eThis Minecraft account is already linked."));
            String info = stripAlreadyLinkedLead(errorMessage);
            if (info != null && !info.isBlank()) {
              player.sendMessage(colorize("&7" + info));
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

          logger.warning("Link code generation failed for " + username + ": " + rawErrorMessage);
        });
      }
    });

    return true;
  }

  /* ═══════════════════════ /offend command ═════════════════════ */

  private boolean handleOffendCommand(CommandSender sender, String[] args) {
    if (!sender.hasPermission("heimdall.offend")) {
      sender.sendMessage(colorize("&cYou don't have permission to use this command!"));
      return true;
    }

    if (args.length < 2) {
      sender.sendMessage(colorize("&cUsage: /offend <player> <offense> [notes]"));
      return true;
    }

    if (!getConfig().getBoolean("enabled", false)) {
      sender.sendMessage(colorize("&cWhitelist system is currently disabled."));
      return true;
    }

    String targetName = args[0];
    String offenseSlug = args[1].toLowerCase();

    // Collect optional notes (args[2..])
    String notes = null;
    if (args.length > 2) {
      notes = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
    }

    // Resolve target UUID. Offense history is keyed by UUID on the bot, so a
    // fabricated UUID (the old createTestUuid path) would file the offense under
    // an identity that never matches the player's real one — splitting history
    // and miscomputing escalation tiers (issue #797 / MC-7). Use the real UUID:
    // online from the live player, offline from the server's player cache.
    String targetUuid;
    Player targetPlayer = getServer().getPlayer(targetName);
    if (targetPlayer != null) {
      targetUuid = targetPlayer.getUniqueId().toString();
      targetName = targetPlayer.getName(); // Use correct casing
    } else {
      org.bukkit.OfflinePlayer cached = getServer().getOfflinePlayerIfCached(targetName);
      if (cached == null || cached.getUniqueId() == null) {
        sender.sendMessage(colorize("&cCould not resolve &f" + targetName
            + "&c — they must be online, or have joined this server before, to receive an offense."));
        return true;
      }
      targetUuid = cached.getUniqueId().toString();
      if (cached.getName() != null) {
        targetName = cached.getName(); // Use correct casing from cache
      }
    }

    // Resolve issuer info
    String issuedByUuid = null;
    String issuedByUsername = null;
    if (sender instanceof Player) {
      Player issuedBy = (Player) sender;
      issuedByUuid = issuedBy.getUniqueId().toString();
      issuedByUsername = issuedBy.getName();
    } else {
      issuedByUsername = "Console";
    }

    sender.sendMessage(colorize("&eRecording offense &f" + offenseSlug + "&e against &f" + targetName + "&e..."));

    final String finalTargetName = targetName;
    final String finalTargetUuid = targetUuid;
    final String finalNotes = notes;
    final String finalIssuedByUuid = issuedByUuid;
    final String finalIssuedByUsername = issuedByUsername;

    getServer().getScheduler().runTaskAsynchronously(this, () -> {
      try {
        OffenseResponse response = apiClient.offend(finalTargetUuid, finalTargetName,
            offenseSlug, finalIssuedByUuid, finalIssuedByUsername, finalNotes).join();

        getServer().getScheduler().runTask(this, () -> {
          sender.sendMessage(colorize("&aOffense recorded for " + finalTargetName + ":"));
          sender.sendMessage(colorize("&7Type: &f" + response.getOffenseType()));
          sender.sendMessage(colorize("&7Action: &f" + response.getTierDescription()
              + " &7(tier " + response.getTierApplied() + ")"));
          sender.sendMessage(colorize("&7Total points: &f" + response.getTotalPoints()));

          // Dispatch the command if available
          String command = response.getCommand();
          if (command != null && !command.isEmpty()) {
            sender.sendMessage(colorize("&7Dispatching: &f" + command));
            if (sender instanceof Player) {
              ((Player) sender).performCommand(command);
            } else {
              getServer().dispatchCommand(getServer().getConsoleSender(), command);
            }
          }
        });
      } catch (Exception e) {
        getServer().getScheduler().runTask(this, () -> {
          String msg = extractThrowableMessage(e);
          sender.sendMessage(colorize("&cFailed to record offense: " + msg));
        });
      }
    });

    return true;
  }

  /* ═══════════════════════ /hwl offense ════════════════════════ */

  private boolean handleOffenseSubcommand(CommandSender sender, String[] args) {
    if (args.length < 2) {
      sender.sendMessage(colorize("&cUsage: /hwl offense <reload|types>"));
      return true;
    }

    String sub = args[1].toLowerCase();

    switch (sub) {
      case "reload":
        sender.sendMessage(colorize("&eRefreshing offense types..."));
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
          offenseManager.refresh();
          getServer().getScheduler().runTask(this, () -> {
            sender.sendMessage(colorize("&aOffense types refreshed. " + offenseManager.getTypes().size()
                + " types loaded."));
          });
        });
        return true;

      case "types":
        List<OffenseTypeInfo> types = offenseManager.getTypes();
        if (types.isEmpty()) {
          sender.sendMessage(colorize("&eNo offense types loaded. Use &f/hwl offense reload&e to fetch."));
          return true;
        }
        sender.sendMessage(colorize("&eOffense Types (" + types.size() + "):"));
        for (OffenseTypeInfo type : types) {
          String status = type.isEnabled() ? "&a✓" : "&c✗";
          sender.sendMessage(colorize(
              status + " &f" + type.getDisplayName() + " &7(" + type.getTypeId() + ")"));
          sender.sendMessage(colorize(
              "  &7Offenses: " + String.join(", ", type.getOffenses())));
        }
        return true;

      default:
        sender.sendMessage(colorize("&cUnknown offense subcommand: " + sub));
        sender.sendMessage(colorize("&7Available: reload, types"));
        return true;
    }
  }

  /* ═══════════════════════ Tab completion ══════════════════════ */

  @Override
  public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
    String commandName = command.getName().toLowerCase();

    if (commandName.equals("offend") && sender.hasPermission("heimdall.offend")) {
      if (args.length == 1) {
        // Player names
        String prefix = args[0].toLowerCase();
        return getServer().getOnlinePlayers().stream()
            .map(p -> p.getName())
            .filter(n -> n.toLowerCase().startsWith(prefix))
            .sorted()
            .collect(Collectors.toList());
      } else if (args.length == 2) {
        // Offense slugs
        return offenseManager.getMatchingSlugs(args[1]);
      }
    }

    if (commandName.equals("hwl") && sender.hasPermission("heimdall.admin")) {
      if (args.length == 1) {
        String prefix = args[0].toLowerCase();
        return Arrays.asList("reload", "status", "enable", "disable", "test", "cache", "offense", "version", "update")
            .stream()
            .filter(s -> s.startsWith(prefix))
            .collect(Collectors.toList());
      }
      if (args.length == 2) {
        String sub = args[0].toLowerCase();
        String prefix = args[1].toLowerCase();
        if (sub.equals("cache")) {
          return Arrays.asList("stats", "clear", "cleanup").stream()
              .filter(s -> s.startsWith(prefix))
              .collect(Collectors.toList());
        }
        if (sub.equals("offense")) {
          return Arrays.asList("reload", "types").stream()
              .filter(s -> s.startsWith(prefix))
              .collect(Collectors.toList());
        }
      }
    }

    return null;
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

  /* ═══════════════════════ WS self-report (identify + health) ════════════ */

  private JsonObject buildIdentifyMetadata() {
    JsonObject m = new JsonObject();
    m.addProperty("pluginVersion", BuildConstants.VERSION);
    m.addProperty("platform", "paper");
    m.addProperty("serverSoftware", getServer().getName() + " " + getServer().getVersion());
    m.addProperty("mcVersion", getServer().getMinecraftVersion());
    m.addProperty("startedAt", startedAtMs);
    return m;
  }

  private JsonObject buildHealthSnapshot() {
    JsonObject h = new JsonObject();
    double[] tps = getServer().getTPS();
    h.addProperty("tps", Math.round(tps[0] * 100.0) / 100.0);
    h.addProperty("mspt", Math.round(getServer().getAverageTickTime() * 100.0) / 100.0);
    h.addProperty("onlinePlayers", getServer().getOnlinePlayers().size());
    h.addProperty("maxPlayers", getServer().getMaxPlayers());
    Runtime rt = Runtime.getRuntime();
    h.addProperty("usedMemMb", (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024));
    h.addProperty("maxMemMb", rt.maxMemory() / (1024 * 1024));
    return h;
  }

  /* ═══════════════════════ WebSocket message handler ═════════════════════ */

  private void handleWsMessage(String type, JsonObject msg) {
    String id = msg.has("id") ? msg.get("id").getAsString() : "";
    JsonObject payload = msg.has("payload") ? msg.getAsJsonObject("payload") : new JsonObject();

    switch (type) {
      case "get_players": {
        JsonArray players = new JsonArray();
        for (Player p : getServer().getOnlinePlayers()) {
          JsonObject pj = new JsonObject();
          pj.addProperty("uuid", p.getUniqueId().toString());
          pj.addProperty("username", p.getName());
          pj.addProperty("ip", p.getAddress() != null
              ? p.getAddress().getAddress().getHostAddress()
              : "unknown");
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
        getServer().getScheduler().runTask(this, () -> {
          getServer().dispatchCommand(getServer().getConsoleSender(), command);
        });
        // Send ack — we can't easily capture console output
        JsonObject ack = new JsonObject();
        ack.addProperty("output", "Command dispatched: " + command);
        wsClient.reply(id, "command_result", ack);
        break;
      }
      case "probe_player": {
        String uuid = payload.has("uuid") ? payload.get("uuid").getAsString() : "";
        // Always reply — a missing/malformed UUID previously threw out of
        // UUID.fromString (or hit the bare break), leaving the bot's request to
        // dangle until its full timeout (issue #797 / MC-12).
        UUID parsedUuid;
        try {
          if (uuid.isEmpty()) {
            throw new IllegalArgumentException("missing uuid");
          }
          parsedUuid = UUID.fromString(uuid);
        } catch (IllegalArgumentException ex) {
          JsonObject ack = new JsonObject();
          ack.addProperty("error", "Invalid player UUID: '" + uuid + "'");
          wsClient.reply(id, "probe_result", ack);
          break;
        }

        Player target = getServer().getPlayer(parsedUuid);
        if (target == null || !target.isOnline()) {
          JsonObject ack = new JsonObject();
          ack.addProperty("error", "Player not online");
          wsClient.reply(id, "probe_result", ack);
          break;
        }

        // Try to delegate to Trace plugin for mod probing
        org.bukkit.plugin.Plugin tracePlugin = getServer().getPluginManager().getPlugin("Trace");
        if (tracePlugin != null && tracePlugin.isEnabled()) {
          try {
            java.lang.reflect.Method probeMethod = tracePlugin.getClass()
                .getMethod("forceProbeForWs", Player.class);
            @SuppressWarnings("unchecked")
            CompletableFuture<JsonObject> future = (CompletableFuture<JsonObject>) probeMethod.invoke(tracePlugin,
                target);
            future.whenComplete((result, error) -> {
              if (error != null) {
                JsonObject errAck = new JsonObject();
                errAck.addProperty("error", error.getMessage());
                wsClient.reply(id, "probe_result", errAck);
              } else {
                wsClient.reply(id, "probe_result", result != null ? result : new JsonObject());
              }
            });
          } catch (NoSuchMethodException e) {
            JsonObject ack = new JsonObject();
            ack.addProperty("error", "Trace plugin does not support remote probing");
            wsClient.reply(id, "probe_result", ack);
          } catch (Exception e) {
            logger.warning("[WS] Failed to invoke Trace probe: " + e.getMessage());
            JsonObject ack = new JsonObject();
            ack.addProperty("error", "Probe invocation failed");
            wsClient.reply(id, "probe_result", ack);
          }
        } else {
          JsonObject ack = new JsonObject();
          ack.addProperty("error", "Trace plugin not available");
          wsClient.reply(id, "probe_result", ack);
        }
        break;
      }
      case "update": {
        // Dashboard-triggered remote update — download the latest jar into the
        // Paper update folder (applied on next restart) and report back.
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
          JsonObject result = new JsonObject();
          try {
            boolean available = updateChecker.checkNow();
            if (!available || updateChecker.getLatestRelease() == null) {
              result.addProperty("success", false);
              result.addProperty("message", "Already up to date");
            } else {
              File updateFolder = new File(getDataFolder().getParentFile(), "update");
              File target = new File(updateFolder, getFile().getName());
              updateChecker.downloadUpdate(target);
              result.addProperty("success", true);
              result.addProperty("version", updateChecker.getLatestRelease().getVersion());
              result.addProperty("message", "Downloaded; restart to apply");
            }
          } catch (Exception e) {
            result.addProperty("success", false);
            result.addProperty("message", e.getMessage());
          }
          wsClient.reply(id, "update_result", result);
        });
        break;
      }

      default:
        // Hand off to any plugin that registered for this type via HeimdallTunnel.
        if (tunnel != null && tunnel.dispatchInbound(type, id, payload)) {
          break;
        }
        logger.debug("[WS] Unhandled message type: " + type);
    }
  }

  /**
   * Convert legacy color codes to Component
   */
  public Component colorize(String message) {
    return LegacyComponentSerializer.legacyAmpersand().deserialize(message);
  }

  /**
   * Convert legacy section color codes to Component
   */
  public Component colorizeSection(String message) {
    return LegacyComponentSerializer.legacySection().deserialize(message);
  }

  public PluginLogger getPluginLogger() {
    return logger;
  }

  public ConfigProvider getConfigProvider() {
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

  public PaperLuckPermsManager getLuckPermsManager() {
    return luckPermsManager;
  }

  public WebSocketClient getWsClient() {
    return wsClient;
  }

  public UpdateChecker getUpdateChecker() {
    return updateChecker;
  }
}
