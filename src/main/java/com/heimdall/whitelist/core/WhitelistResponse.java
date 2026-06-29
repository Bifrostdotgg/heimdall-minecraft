package com.heimdall.whitelist.core;

import java.util.List;

/**
 * Platform-agnostic whitelist check response.
 * Contains the result of a whitelist API check.
 */
public class WhitelistResponse {
  private final boolean shouldBeWhitelisted;
  private final boolean hasAuth;
  private final String kickMessage;
  private final String action;
  private final String authCode;
  private final boolean roleSyncEnabled;
  private final List<String> targetGroups;
  private final List<String> managedGroups;
  private final int queuePosition; // 0 = not in queue

  public WhitelistResponse(boolean shouldBeWhitelisted, boolean hasAuth, String kickMessage, String action) {
    this(shouldBeWhitelisted, hasAuth, kickMessage, action, null, false, null, null, 0);
  }

  public WhitelistResponse(boolean shouldBeWhitelisted, boolean hasAuth, String kickMessage, String action,
      String authCode) {
    this(shouldBeWhitelisted, hasAuth, kickMessage, action, authCode, false, null, null, 0);
  }

  public WhitelistResponse(boolean shouldBeWhitelisted, boolean hasAuth, String kickMessage, String action,
      String authCode, boolean roleSyncEnabled, List<String> targetGroups) {
    this(shouldBeWhitelisted, hasAuth, kickMessage, action, authCode, roleSyncEnabled, targetGroups, null, 0);
  }

  public WhitelistResponse(boolean shouldBeWhitelisted, boolean hasAuth, String kickMessage, String action,
      String authCode, boolean roleSyncEnabled, List<String> targetGroups, List<String> managedGroups) {
    this(shouldBeWhitelisted, hasAuth, kickMessage, action, authCode, roleSyncEnabled, targetGroups, managedGroups, 0);
  }

  public WhitelistResponse(boolean shouldBeWhitelisted, boolean hasAuth, String kickMessage, String action,
      String authCode, boolean roleSyncEnabled, List<String> targetGroups, List<String> managedGroups,
      int queuePosition) {
    this.shouldBeWhitelisted = shouldBeWhitelisted;
    this.hasAuth = hasAuth;
    this.kickMessage = kickMessage;
    this.action = action;
    this.authCode = authCode;
    this.roleSyncEnabled = roleSyncEnabled;
    this.targetGroups = targetGroups;
    this.managedGroups = managedGroups;
    this.queuePosition = queuePosition;
  }

  public boolean shouldBeWhitelisted() {
    return shouldBeWhitelisted;
  }

  public boolean hasAuth() {
    return hasAuth;
  }

  public String getKickMessage() {
    return kickMessage;
  }

  public String getAction() {
    return action;
  }

  public String getAuthCode() {
    return authCode;
  }

  public boolean isRoleSyncEnabled() {
    return roleSyncEnabled;
  }

  public List<String> getTargetGroups() {
    return targetGroups;
  }

  public List<String> getManagedGroups() {
    return managedGroups;
  }

  public int getQueuePosition() {
    return queuePosition;
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder("WhitelistResponse{");
    sb.append("action='").append(action).append('\'');
    sb.append(", shouldBeWhitelisted=").append(shouldBeWhitelisted);
    sb.append(", hasAuth=").append(hasAuth);
    if (kickMessage != null && !kickMessage.isEmpty()) {
      sb.append(", kickMessage='").append(kickMessage).append('\'');
    }
    if (authCode != null) {
      sb.append(", authCode='").append(authCode).append('\'');
    }
    if (roleSyncEnabled) {
      sb.append(", roleSyncEnabled=true");
      sb.append(", targetGroups=").append(targetGroups);
      sb.append(", managedGroups=").append(managedGroups);
    }
    if (queuePosition > 0) {
      sb.append(", queuePosition=").append(queuePosition);
    }
    sb.append('}');
    return sb.toString();
  }
}
