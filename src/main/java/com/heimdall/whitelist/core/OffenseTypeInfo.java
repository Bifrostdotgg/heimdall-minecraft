package com.heimdall.whitelist.core;

import java.util.List;

/**
 * Cached offense type data fetched from the bot API.
 * Used for tab completion and offline display.
 */
public final class OffenseTypeInfo {

  private final String typeId;
  private final String displayName;
  private final String description;
  private final List<String> offenses;
  private final boolean enabled;

  public OffenseTypeInfo(String typeId, String displayName, String description,
      List<String> offenses, boolean enabled) {
    this.typeId = typeId;
    this.displayName = displayName;
    this.description = description;
    this.offenses = offenses;
    this.enabled = enabled;
  }

  public String getTypeId() {
    return typeId;
  }

  public String getDisplayName() {
    return displayName;
  }

  public String getDescription() {
    return description;
  }

  public List<String> getOffenses() {
    return offenses;
  }

  public boolean isEnabled() {
    return enabled;
  }

  @Override
  public String toString() {
    return displayName + " (" + typeId + ") — offenses: " + String.join(", ", offenses);
  }
}
