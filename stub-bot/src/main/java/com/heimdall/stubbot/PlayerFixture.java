package com.heimdall.stubbot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * One configured player: which of the six connection-attempt outcomes the stub answers with, plus
 * the outcome-specific extras.
 *
 * <p>Deserialised straight from the JSON in {@code STUB_BOT_PLAYERS} / {@code STUB_BOT_PLAYERS_FILE}
 * by Gson, so the field names here <em>are</em> the config schema. Fields left out of the JSON keep
 * the defaults below.
 */
public final class PlayerFixture {

    private String uuid;
    private String username;
    private String outcome = "deny";

    /** ALLOW only — the groups the plugin should end up with. */
    private List<String> targetGroups;
    /** ALLOW only — the groups Heimdall considers its own to add and remove. */
    private List<String> managedGroups;
    /**
     * ALLOW only — force {@code roleSync} to a specific shape. {@code null} (the default) derives it
     * from {@code targetGroups}: absent → {@code roleSync: null} (matching a legacy row with no
     * snapshot), present → {@code {enabled: true, targetGroups, managedGroups}}. {@code false} emits
     * {@code {enabled: false}}, which is what the bot sends in RCON mode to tell the plugin to keep
     * its hands off.
     */
    private Boolean roleSyncEnabled;

    /** PENDING_AUTH / EXISTING_LINK — the 6-digit code shown in-game. */
    private String authCode;
    /** PENDING_APPROVAL — position in the staff-approval queue. */
    private Integer queuePosition;
    /** REVOKED — substituted into {@code {reason}} in the revocation message. */
    private String revocationReason;
    /** Overrides the default message template for this player entirely. */
    private String message;

    /**
     * If set, {@code POST /request-link-code} answers {@code alreadyLinked: true} for this player
     * instead of minting a code.
     */
    private String linkedDiscordId;
    private String linkedDiscordUsername;
    private String linkedDiscordDisplayName;

    /** Gson needs this; application code should use {@link #of}. */
    public PlayerFixture() {
    }

    /** Convenience factory for tests and defaults. */
    public static PlayerFixture of(String uuid, String username, Outcome outcome) {
        PlayerFixture fixture = new PlayerFixture();
        fixture.uuid = uuid;
        fixture.username = username;
        fixture.outcome = outcome.name().toLowerCase(Locale.ROOT);
        return fixture;
    }

    public PlayerFixture withGroups(List<String> target, List<String> managed) {
        this.targetGroups = target == null ? null : new ArrayList<>(target);
        this.managedGroups = managed == null ? null : new ArrayList<>(managed);
        return this;
    }

    public PlayerFixture withAuthCode(String code) {
        this.authCode = code;
        return this;
    }

    public PlayerFixture withQueuePosition(int position) {
        this.queuePosition = position;
        return this;
    }

    public PlayerFixture withRevocationReason(String reason) {
        this.revocationReason = reason;
        return this;
    }

    public PlayerFixture withMessage(String value) {
        this.message = value;
        return this;
    }

    public PlayerFixture withRoleSyncEnabled(Boolean value) {
        this.roleSyncEnabled = value;
        return this;
    }

    public PlayerFixture linkedTo(String discordId, String username, String displayName) {
        this.linkedDiscordId = discordId;
        this.linkedDiscordUsername = username;
        this.linkedDiscordDisplayName = displayName;
        return this;
    }

    public String uuid() {
        return uuid;
    }

    public String username() {
        return username;
    }

    public Outcome outcome() {
        return Outcome.parse(outcome);
    }

    public List<String> targetGroups() {
        return targetGroups == null ? null : Collections.unmodifiableList(targetGroups);
    }

    public List<String> managedGroups() {
        return managedGroups == null ? Collections.emptyList() : Collections.unmodifiableList(managedGroups);
    }

    public Boolean roleSyncEnabled() {
        return roleSyncEnabled;
    }

    public String authCode() {
        return authCode;
    }

    public Integer queuePosition() {
        return queuePosition;
    }

    public String revocationReason() {
        return revocationReason;
    }

    public String message() {
        return message;
    }

    public String linkedDiscordId() {
        return linkedDiscordId;
    }

    public String linkedDiscordUsername() {
        return linkedDiscordUsername;
    }

    public String linkedDiscordDisplayName() {
        return linkedDiscordDisplayName;
    }

    /** Validates the fields Gson could have left null, so a bad fixture fails at load, not at request time. */
    void validate() {
        if (uuid == null || uuid.isBlank()) {
            throw new IllegalArgumentException("player fixture is missing 'uuid'");
        }
        // Throws on an unknown spelling.
        outcome();
    }
}
