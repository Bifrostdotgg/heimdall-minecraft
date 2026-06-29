package com.heimdall.whitelist.core;

import java.util.List;

/**
 * Config-driven whitelist bypass list (issue #796 / MC-2).
 *
 * The {@code heimdall.bypass} permission can't gate the whitelist on either
 * platform: Paper denies in {@code AsyncPlayerPreLoginEvent} (before the player
 * — and therefore its permissions — exists), and Velocity never checked a
 * bypass at login at all. A UUID allowlist read from config is the reliable
 * cross-platform mechanism because the UUID is known at pre-login.
 */
public final class BypassList {

  private BypassList() {
  }

  /**
   * Whether the given player UUID is on the configured bypass list. Matching is
   * case-insensitive and trims surrounding whitespace so dashed/undashed and
   * sloppily-pasted config entries still match.
   *
   * @param configuredUuids the {@code bypass.uuids} list (may be null/empty)
   * @param playerUuid      the connecting player's UUID string
   * @return true if the player should skip whitelist enforcement
   */
  public static boolean isBypassed(List<String> configuredUuids, String playerUuid) {
    if (configuredUuids == null || configuredUuids.isEmpty() || playerUuid == null) {
      return false;
    }
    String target = playerUuid.trim();
    for (String entry : configuredUuids) {
      if (entry != null && entry.trim().equalsIgnoreCase(target)) {
        return true;
      }
    }
    return false;
  }
}
