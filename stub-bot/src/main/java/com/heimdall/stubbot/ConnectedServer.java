package com.heimdall.stubbot;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.java_websocket.WebSocket;

/**
 * One connected Minecraft server, as the stub knows it.
 *
 * <p>Starts as a placeholder holding only what the upgrade URL carried ({@code guildId},
 * {@code serverId}) and is enriched by {@code identify} — the same two-step the real bot does, which
 * is why a plugin that never sends {@code identify} still shows up in the connection list, just
 * anonymously.
 */
public final class ConnectedServer {

    private final String guildId;
    private final String serverId;
    private final WebSocket socket;
    private final long connectedAtMs;
    private final AtomicLong lastSeenMs;

    private volatile JsonObject identify;
    private volatile JsonObject lastHealth;
    private volatile Integer protocolVersion;
    private volatile List<String> capabilities = Collections.emptyList();
    private volatile boolean identifyAcked;
    private volatile Integer acknowledgedConfigVersion;

    private final List<JsonObject> consoleLines = Collections.synchronizedList(new ArrayList<>());

    ConnectedServer(String guildId, String serverId, WebSocket socket, long nowMs) {
        this.guildId = guildId;
        this.serverId = serverId;
        this.socket = socket;
        this.connectedAtMs = nowMs;
        this.lastSeenMs = new AtomicLong(nowMs);
    }

    public String guildId() {
        return guildId;
    }

    public String serverId() {
        return serverId;
    }

    public long connectedAtMs() {
        return connectedAtMs;
    }

    public long lastSeenMs() {
        return lastSeenMs.get();
    }

    /** The raw {@code identify} payload, or null if the plugin has not identified yet. */
    public JsonObject identify() {
        return identify;
    }

    /** The most recent {@code health} snapshot, or null. */
    public JsonObject lastHealth() {
        return lastHealth;
    }

    /** The {@code protocolVersion} from {@code identify}, or null for a v2 client. */
    public Integer protocolVersion() {
        return protocolVersion;
    }

    /** The capabilities the client declared; empty for a v2 client. */
    public List<String> capabilities() {
        return capabilities;
    }

    /** Whether the stub answered this client's {@code identify} with an {@code identify_ack}. */
    public boolean identifyAcked() {
        return identifyAcked;
    }

    /** The config version the client last confirmed with {@code config.ack}, or null. */
    public Integer acknowledgedConfigVersion() {
        return acknowledgedConfigVersion;
    }

    /** Console lines received via {@code console_line}, oldest first. */
    public List<JsonObject> consoleLines() {
        synchronized (consoleLines) {
            return List.copyOf(consoleLines);
        }
    }

    WebSocket socket() {
        return socket;
    }

    void touch(long nowMs) {
        lastSeenMs.set(nowMs);
    }

    void setIdentify(JsonObject payload, Integer protocolVersion, List<String> capabilities) {
        this.identify = payload;
        this.protocolVersion = protocolVersion;
        this.capabilities = capabilities == null ? Collections.emptyList() : List.copyOf(capabilities);
    }

    void setLastHealth(JsonObject health) {
        this.lastHealth = health;
    }

    void setIdentifyAcked(boolean value) {
        this.identifyAcked = value;
    }

    void setAcknowledgedConfigVersion(Integer version) {
        this.acknowledgedConfigVersion = version;
    }

    void addConsoleLine(JsonObject line) {
        synchronized (consoleLines) {
            consoleLines.add(line);
            // Bounded so a chatty server cannot grow the fixture without limit during a long soak.
            if (consoleLines.size() > 2000) {
                consoleLines.remove(0);
            }
        }
    }
}
