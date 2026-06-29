package com.heimdall.whitelist.paper;

import com.heimdall.whitelist.core.PluginLogger;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.types.InheritanceNode;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Paper/Bukkit LuckPerms integration
 */
public class PaperLuckPermsManager {

  private final JavaPlugin plugin;
  private final PluginLogger logger;
  private LuckPerms luckPerms;

  public PaperLuckPermsManager(JavaPlugin plugin, PluginLogger logger) {
    this.plugin = plugin;
    this.logger = logger;

    try {
      this.luckPerms = LuckPermsProvider.get();
      logger.info("LuckPerms integration enabled");
    } catch (IllegalStateException e) {
      logger.warning("LuckPerms not found! Role sync will not work.");
      this.luckPerms = null;
    }
  }

  /**
   * Check if LuckPerms is available
   */
  public boolean isAvailable() {
    return luckPerms != null;
  }

  /**
   * Get player's current groups
   */
  public List<String> getPlayerGroups(UUID playerUuid) {
    if (!isAvailable()) {
      return new ArrayList<>();
    }

    try {
      // At AsyncPlayerPreLoginEvent the player isn't online yet, so the LuckPerms
      // user is usually NOT in the in-memory cache — getUser() returns null and we
      // would report empty groups every login. Fall back to loading from storage
      // (this runs on the async pre-login thread, so blocking is fine) so the bot
      // receives real groups and can diff role-sync correctly (issue #796 / MC-11).
      User user = luckPerms.getUserManager().getUser(playerUuid);
      if (user == null) {
        user = luckPerms.getUserManager().loadUser(playerUuid).join();
      }
      if (user == null) {
        return new ArrayList<>();
      }

      return user.getInheritedGroups(user.getQueryOptions())
          .stream()
          .map(group -> group.getName())
          .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
    } catch (Exception e) {
      logger.warning("Failed to get groups for player " + playerUuid + ": " + e.getMessage());
      return new ArrayList<>();
    }
  }

  /**
   * Sync player's Discord-managed groups
   */
  public CompletableFuture<Boolean> setPlayerGroups(UUID playerUuid, List<String> targetGroups,
      List<String> managedGroups) {
    if (!isAvailable()) {
      return CompletableFuture.completedFuture(false);
    }

    return CompletableFuture.supplyAsync(() -> {
      try {
        User user = luckPerms.getUserManager().loadUser(playerUuid).join();
        if (user == null) {
          logger.warning("Could not load user " + playerUuid + " for role sync");
          return false;
        }

        // Ensure we have a list of managed groups from the API
        if (managedGroups == null || managedGroups.isEmpty()) {
          logger.warning(
              "No managed groups provided by API for player " + playerUuid + ", skipping role sync");
          return false;
        }

        // Ensure we have target groups (can be empty if user should have no Discord
        // roles)
        final List<String> finalTargetGroups = targetGroups != null ? targetGroups : new ArrayList<>();

        // Get current groups
        List<String> currentGroups = user.getInheritedGroups(user.getQueryOptions())
            .stream()
            .map(group -> group.getName())
            .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);

        // Find Discord-managed groups that need to be removed
        List<String> groupsToRemove = new ArrayList<>();
        for (String currentGroup : currentGroups) {
          if (managedGroups.contains(currentGroup) && !finalTargetGroups.contains(currentGroup)) {
            groupsToRemove.add(currentGroup);
          }
        }

        // Find Discord-managed groups that need to be added
        List<String> groupsToAdd = new ArrayList<>();
        for (String targetGroup : finalTargetGroups) {
          if (managedGroups.contains(targetGroup) && !currentGroups.contains(targetGroup)) {
            groupsToAdd.add(targetGroup);
          }
        }

        // Remove Discord-managed groups that are no longer needed
        for (String groupToRemove : groupsToRemove) {
          InheritanceNode node = InheritanceNode.builder(groupToRemove).build();
          user.data().remove(node);
          logger.info("Removed Discord-synced group '" + groupToRemove + "' from player " + playerUuid);
        }

        // Add new Discord-managed groups
        for (String groupToAdd : groupsToAdd) {
          if (luckPerms.getGroupManager().isLoaded(groupToAdd)) {
            InheritanceNode node = InheritanceNode.builder(groupToAdd).build();
            user.data().add(node);
            logger.info("Added Discord-synced group '" + groupToAdd + "' to player " + playerUuid);
          } else {
            logger.warning("Group '" + groupToAdd + "' does not exist, skipping");
          }
        }

        // Save changes only if there were changes
        if (!groupsToRemove.isEmpty() || !groupsToAdd.isEmpty()) {
          luckPerms.getUserManager().saveUser(user);
          logger.info("Synchronized Discord-managed groups for player " + playerUuid +
              " - Added: " + groupsToAdd + ", Removed: " + groupsToRemove);
        } else {
          logger.info("No Discord-managed group changes needed for player " + playerUuid);
        }

        return true;

      } catch (Exception e) {
        logger.severe("Failed to sync groups for player " + playerUuid + ": " + e.getMessage());
        return false;
      }
    });
  }
}
