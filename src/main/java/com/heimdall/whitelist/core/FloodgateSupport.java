package com.heimdall.whitelist.core;

import java.util.UUID;

/**
 * Optional Floodgate integration, accessed entirely via reflection so the plugin
 * has NO compile- or run-time dependency on Floodgate. When Floodgate is present
 * (Geyser Bedrock support), this resolves a joining player's true gamertag and
 * XUID so we can send explicit Bedrock identity to the bot.
 *
 * <p>Why explicit fields matter: Floodgate rewrites Bedrock usernames with a
 * configurable prefix (default {@code .}) and a synthetic UUID. The bot can infer
 * Bedrock from the synthetic-UUID shape and strip its configured prefix, but a
 * server running a NON-default Floodgate prefix that isn't mirrored in the
 * dashboard would break that inference. Sending {@code isBedrock} +
 * {@code bedrockGamertag} (prefix-free) + {@code bedrockXuid} makes matching
 * robust regardless of prefix config.
 *
 * <p>Every access is guarded and failure-tolerant: if Floodgate is absent or the
 * API shape differs, {@link #resolve(String)} returns {@code null} and the bot
 * falls back to synthetic-UUID + prefix handling.
 */
public final class FloodgateSupport {

  private FloodgateSupport() {}

  /** Resolved Bedrock identity: prefix-free gamertag + XUID (either may be null). */
  public record BedrockIdentity(String gamertag, String xuid) {}

  /** Tri-state cache of whether the Floodgate API class is loadable. */
  private static volatile Boolean available;

  private static boolean isAvailable() {
    Boolean cached = available;
    if (cached != null) {
      return cached;
    }
    boolean present;
    try {
      Class.forName("org.geysermc.floodgate.api.FloodgateApi");
      present = true;
    } catch (Throwable ignored) {
      present = false;
    }
    available = present;
    return present;
  }

  /**
   * Resolve the Bedrock identity for a joining player, or {@code null} if the
   * player isn't a Bedrock/Floodgate player (or Floodgate isn't installed).
   *
   * @param uuidString the (possibly synthetic) UUID the platform reports
   */
  public static BedrockIdentity resolve(String uuidString) {
    if (uuidString == null || !isAvailable()) {
      return null;
    }
    try {
      UUID uuid = UUID.fromString(uuidString);
      Class<?> apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
      Object api = apiClass.getMethod("getInstance").invoke(null);
      if (api == null) {
        return null;
      }
      boolean isFloodgate =
          (boolean) apiClass.getMethod("isFloodgatePlayer", UUID.class).invoke(api, uuid);
      if (!isFloodgate) {
        return null;
      }
      Object player = apiClass.getMethod("getPlayer", UUID.class).invoke(api, uuid);
      if (player == null) {
        return null;
      }
      Class<?> playerClass = player.getClass();
      // getJavaUsername() = the Java-safe gamertag WITHOUT the Floodgate prefix.
      String gamertag = (String) playerClass.getMethod("getJavaUsername").invoke(player);
      String xuid = null;
      try {
        Object x = playerClass.getMethod("getXuid").invoke(player);
        xuid = x != null ? x.toString() : null;
      } catch (Throwable ignored) {
        // XUID is a bonus; the gamertag alone is enough to match.
      }
      if (gamertag == null || gamertag.isBlank()) {
        return null;
      }
      return new BedrockIdentity(gamertag, xuid);
    } catch (Throwable ignored) {
      return null;
    }
  }
}
