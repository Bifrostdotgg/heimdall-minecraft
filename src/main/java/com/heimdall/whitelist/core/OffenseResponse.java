package com.heimdall.whitelist.core;

/**
 * Immutable data model for offense API response.
 * Returned by POST /offend endpoint.
 */
public final class OffenseResponse {

  private final String infractionId;
  private final String command;
  private final String action;
  private final Integer duration; // minutes, null if not applicable
  private final int totalPoints;
  private final int tierApplied;
  private final String tierDescription;
  private final String offenseType;

  public OffenseResponse(String infractionId, String command, String action, Integer duration,
      int totalPoints, int tierApplied, String tierDescription, String offenseType) {
    this.infractionId = infractionId;
    this.command = command;
    this.action = action;
    this.duration = duration;
    this.totalPoints = totalPoints;
    this.tierApplied = tierApplied;
    this.tierDescription = tierDescription;
    this.offenseType = offenseType;
  }

  public String getInfractionId() {
    return infractionId;
  }

  public String getCommand() {
    return command;
  }

  public String getAction() {
    return action;
  }

  public Integer getDuration() {
    return duration;
  }

  public int getTotalPoints() {
    return totalPoints;
  }

  public int getTierApplied() {
    return tierApplied;
  }

  public String getTierDescription() {
    return tierDescription;
  }

  public String getOffenseType() {
    return offenseType;
  }

  @Override
  public String toString() {
    return "OffenseResponse{" +
        "action='" + action + '\'' +
        ", tierApplied=" + tierApplied +
        ", totalPoints=" + totalPoints +
        ", command='" + command + '\'' +
        ", offenseType='" + offenseType + '\'' +
        '}';
  }
}
