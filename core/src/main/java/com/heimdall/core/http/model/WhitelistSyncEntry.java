package com.heimdall.core.http.model;

/**
 * One whitelisted player in the full-whitelist dump.
 *
 * <p>{@code username} is nullable — the bot sends an explicit {@code null} for a player it has a
 * UUID for but no name.
 */
public final class WhitelistSyncEntry {

    private final String uuid;
    private final String username;

    public WhitelistSyncEntry(String uuid, String username) {
        this.uuid = uuid;
        this.username = username;
    }

    public String uuid() {
        return uuid;
    }

    /** The player's name, or {@code null} if the bot does not know it. */
    public String username() {
        return username;
    }

    @Override
    public String toString() {
        return username == null ? uuid : username + " (" + uuid + ")";
    }
}
