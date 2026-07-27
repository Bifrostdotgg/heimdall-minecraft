package com.heimdall.core.util;

import java.util.List;

/**
 * Config-driven whitelist bypass, by UUID (ported from v2 — issue #796 / MC-2).
 *
 * <p>A {@code heimdall.bypass} permission cannot gate the whitelist on either platform: Bukkit
 * denies in {@code AsyncPlayerPreLoginEvent}, before the player object — and therefore its
 * permissions — exists, and Velocity never checked a bypass at login at all. The UUID <em>is</em>
 * known at pre-login, so a UUID allowlist read from config is the one mechanism that works
 * everywhere.
 *
 * <p>Matching trims and case-folds because these lists are pasted by hand into a config file.
 */
public final class BypassList {

    private BypassList() {
    }

    /**
     * Whether the connecting player's UUID appears in the configured bypass list.
     *
     * @param configuredUuids the configured UUIDs; may be {@code null}, empty, or contain nulls
     * @param playerUuid the connecting player's UUID; {@code null} is never bypassed
     * @return {@code true} if whitelist enforcement should be skipped for this player
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
