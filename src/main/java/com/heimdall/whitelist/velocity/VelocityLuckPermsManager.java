package com.heimdall.whitelist.velocity;

import com.heimdall.whitelist.core.PluginLogger;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.types.InheritanceNode;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Velocity LuckPerms integration for role sync.
 * Works with LuckPerms installed on the Velocity proxy.
 */
public class VelocityLuckPermsManager {

  private final PluginLogger logger;
  private volatile LuckPerms luckPerms;
  private volatile boolean loggedEnabled = false;

  public VelocityLuckPermsManager(PluginLogger logger) {
    this.logger = logger;
    // Attempt to resolve eagerly, but DON'T cache a permanent "unavailable"
    // result: LuckPerms may register its service after Heimdall initializes
    // (no hard load-order guarantee). resolve() retries on demand so role sync
    // self-heals once LuckPerms is up, instead of staying disabled for the
    // whole session (issue #796 / MC-10).
    resolve();
  }

  /**
   * Lazily (re)resolve the LuckPerms API. Returns the live instance or null if
   * LuckPerms still hasn't registered its service.
   */
  private synchronized LuckPerms resolve() {
    if (luckPerms != null) {
      return luckPerms;
    }
    try {
      luckPerms = LuckPermsProvider.get();
      if (!loggedEnabled) {
        logger.info("LuckPerms integration enabled (Velocity)");
        loggedEnabled = true;
      }
    } catch (IllegalStateException e) {
      luckPerms = null;
    }
    return luckPerms;
  }

  /**
   * Check if LuckPerms is available (resolving lazily if it loaded after us).
   */
  public boolean isAvailable() {
    return resolve() != null;
  }

  /**
   * Get player's current groups
   */
  public List<String> getPlayerGroups(UUID playerUuid) {
    if (!isAvailable()) {
      return new ArrayList<>();
    }

    try {
      // Try to get cached user first
      User user = luckPerms.getUserManager().getUser(playerUuid);

      // If not cached, load from storage
      if (user == null) {
        user = luckPerms.getUserManager().loadUser(playerUuid).join();
      }

      if (user == null) {
        logger.warning("Could not load user " + playerUuid + " from LuckPerms");
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
   * Sync player's Discord-managed groups.
   * This will add/remove groups based on what the API returns.
   * 
   * @param playerUuid    The player's UUID
   * @param targetGroups  The groups the player should have (from Discord roles)
   * @param managedGroups The list of groups that are managed by Discord (only
   *                      these will be modified)
   * @return CompletableFuture that resolves to true if sync was successful
   */
  public CompletableFuture<Boolean> setPlayerGroups(UUID playerUuid, List<String> targetGroups,
      List<String> managedGroups) {
    if (!isAvailable()) {
      return CompletableFuture.completedFuture(false);
    }

    return CompletableFuture.supplyAsync(() -> {
      try {
        // Load user from LuckPerms
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
          // Check if group exists in LuckPerms
          if (luckPerms.getGroupManager().isLoaded(groupToAdd) ||
              luckPerms.getGroupManager().loadGroup(groupToAdd).join().isPresent()) {
            InheritanceNode node = InheritanceNode.builder(groupToAdd).build();
            user.data().add(node);
            logger.info("Added Discord-synced group '" + groupToAdd + "' to player " + playerUuid);
          } else {
            logger.warning("Group '" + groupToAdd + "' does not exist in LuckPerms, skipping");
          }
        }

        // Save changes only if there were changes
        if (!groupsToRemove.isEmpty() || !groupsToAdd.isEmpty()) {
          luckPerms.getUserManager().saveUser(user).join();
          logger.info("Synchronized Discord-managed groups for player " + playerUuid +
              " - Added: " + groupsToAdd + ", Removed: " + groupsToRemove);
        } else {
          logger.info("No Discord-managed group changes needed for player " + playerUuid);
        }

        return true;

      } catch (Exception e) {
        logger.severe("Failed to sync groups for player " + playerUuid + ": " + e.getMessage());
        e.printStackTrace();
        return false;
      }
    });
  }

  /**
   * Clear cached user data for a player.
   * Useful when you need fresh data from storage.
   */
  public void cleanupUser(UUID playerUuid) {
    if (isAvailable()) {
      luckPerms.getUserManager().cleanupUser(luckPerms.getUserManager().getUser(playerUuid));
    }
  }
}
