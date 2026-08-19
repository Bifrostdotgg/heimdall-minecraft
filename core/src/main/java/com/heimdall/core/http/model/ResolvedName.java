package com.heimdall.core.http.model;

import com.heimdall.core.util.Strings;

/**
 * The bot's answer to {@code GET /players/resolve}: a UUID it already knew, or one Mojang named.
 *
 * <p>Read-only. The plugin must not invent a UUID when this is absent.
 */
public final class ResolvedName {

    private final String uuid;
    private final String username;
    private final String source;

    private ResolvedName(String uuid, String username, String source) {
        this.uuid = uuid;
        this.username = username;
        this.source = source == null ? "" : source;
    }

    public static ResolvedName of(String uuid, String username, String source) {
        if (Strings.isBlank(uuid) || Strings.isBlank(username)) {
            throw new IllegalArgumentException("uuid and username are required");
        }
        return new ResolvedName(uuid.trim(), username.trim(), source);
    }

    public String uuid() {
        return uuid;
    }

    public String username() {
        return username;
    }

    /** {@code minecraft_player} or {@code mojang}. */
    public String source() {
        return source;
    }
}
