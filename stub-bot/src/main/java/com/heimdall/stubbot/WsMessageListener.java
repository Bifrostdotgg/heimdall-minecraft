package com.heimdall.stubbot;

import com.google.gson.JsonObject;

/**
 * Receives every envelope the stub did not handle itself — the equivalent of the real manager's
 * {@code onClientMessage} hook. Tests use it to observe unsolicited traffic
 * ({@code player_join}, {@code player_leave}, …) without reaching into the socket.
 */
@FunctionalInterface
public interface WsMessageListener {

    void onMessage(String guildId, String serverId, String id, String type, JsonObject payload);
}
