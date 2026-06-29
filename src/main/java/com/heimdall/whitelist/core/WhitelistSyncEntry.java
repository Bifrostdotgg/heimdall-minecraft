package com.heimdall.whitelist.core;

/**
 * One whitelisted player returned by the bot's whitelist pre-warm sync endpoint
 * ({@code GET /minecraft/whitelist/sync}). The plugin loads these into the
 * {@link WhitelistCache} so a brief bot outage (redeploy/restart) is invisible
 * to every whitelisted player, not just the ones who happened to join recently.
 */
public class WhitelistSyncEntry {
  private final String uuid;
  private final String username;

  public WhitelistSyncEntry(String uuid, String username) {
    this.uuid = uuid;
    this.username = username;
  }

  public String getUuid() {
    return uuid;
  }

  public String getUsername() {
    return username;
  }
}
