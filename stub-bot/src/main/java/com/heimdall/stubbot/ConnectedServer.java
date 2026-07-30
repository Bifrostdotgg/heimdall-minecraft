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
    private volatile List<String> acceptedCapabilities = Collections.emptyList();

    /**
     * Whether this serverId is in the bot's registry.
     *
     * <p>The real bot binds a serverId to exactly one API token in Mongo, and looks it up at upgrade
     * time. An unregistered connection is a real, supported state — it is what a server that has
     * been claimed but not yet written, or one connecting during a registry outage, actually is —
     * and it behaves visibly differently: it gets an {@code identify_ack} with
     * {@code configVersion: 0} and no {@code config.push} at all.
     */
    private volatile boolean registered = true;
    private volatile boolean identifyAcked;
    private volatile Integer acknowledgedConfigVersion;

    private final List<JsonObject> consoleLines = Collections.synchronizedList(new ArrayList<>());

    /**
     * Chat lines received via {@code bridge.chat}, and player events via {@code bridge.event}.
     *
     * <p>Captured so a test can assert on the wire shape the plugin actually produced — the whole
     * value of this fixture is that a plugin proven against it behaves the same way against
     * production, and a batching bug is only visible in the frame.
     *
     * <p>Held in memory, bounded, and <strong>never logged</strong>. The bridge's relay-only rule is
     * the plugin's and the bot's, not this fixture's, but the stub's log lines are what CI archives
     * — so they carry counts and kinds and no message body. See {@code StubWsServer#onMessage}.
     */
    private final List<JsonObject> bridgeChatLines = Collections.synchronizedList(new ArrayList<>());

    private final List<JsonObject> bridgeEvents = Collections.synchronizedList(new ArrayList<>());

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
    /** The capabilities the stub told this connection it would honour. */
    public List<String> acceptedCapabilities() {
        return acceptedCapabilities;
    }

    public void setAcceptedCapabilities(List<String> accepted) {
        this.acceptedCapabilities = accepted;
    }

    /** Whether this serverId is in the registry. See the field. */
    public boolean registered() {
        return registered;
    }

    public void setRegistered(boolean registered) {
        this.registered = registered;
    }

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
        return copyOf(consoleLines);
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
        append(consoleLines, line);
    }

    /** Chat lines received via {@code bridge.chat}, oldest first. */
    public List<JsonObject> bridgeChatLines() {
        return copyOf(bridgeChatLines);
    }

    /** Player events received via {@code bridge.event}, oldest first. */
    public List<JsonObject> bridgeEvents() {
        return copyOf(bridgeEvents);
    }

    void addBridgeChatLine(JsonObject line) {
        append(bridgeChatLines, line);
    }

    void addBridgeEvent(JsonObject event) {
        append(bridgeEvents, event);
    }

    private static void append(List<JsonObject> into, JsonObject entry) {
        synchronized (into) {
            into.add(entry);
            // Bounded so a chatty server cannot grow the fixture without limit during a long soak.
            if (into.size() > 2000) {
                into.remove(0);
            }
        }
    }

    private static List<JsonObject> copyOf(List<JsonObject> from) {
        synchronized (from) {
            return List.copyOf(from);
        }
    }
}
