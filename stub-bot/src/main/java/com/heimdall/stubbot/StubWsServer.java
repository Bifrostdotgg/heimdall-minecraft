package com.heimdall.stubbot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.java_websocket.WebSocket;
import org.java_websocket.drafts.Draft;
import org.java_websocket.exceptions.InvalidDataException;
import org.java_websocket.framing.CloseFrame;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.handshake.ServerHandshakeBuilder;
import org.java_websocket.server.WebSocketServer;

/**
 * The WebSocket half of the fixture: {@code /ws/minecraft/{guildId}}, HMAC-authenticated on the
 * upgrade, speaking the {@code {id, type, payload}} envelope.
 *
 * <p>Two behaviours here are contract, not implementation detail:
 *
 * <ul>
 *   <li><strong>The upgrade signature covers the path WITHOUT its query string</strong>, even though
 *       the signature travels in that query string. HTTP signs the path WITH the query. See
 *       {@link Hmac}.
 *   <li><strong>Correlation is by echoed id.</strong> A request the stub sends carries a fresh id;
 *       the plugin's reply must carry the same one. Anything with an unrecognised id is treated as
 *       unsolicited and forwarded to the {@link WsMessageListener}.
 * </ul>
 *
 * <p>On top of the v2 protocol this implements the v3 capability handshake — see
 * {@link #handleIdentify}. A client that does not declare {@code protocolVersion} and
 * {@code capabilities} gets exactly the v2 behaviour, silence included.
 */
public final class StubWsServer extends WebSocketServer {

    /** The bot's own route regex — a guild id outside 17-20 digits is not a guild id. */
    private static final Pattern WS_PATH = Pattern.compile("^/ws/minecraft/(\\d{17,20})$");

    /** See the note on {@code StubHttpApi.GSON} — {@code role_sync} carries an explicit null uuid. */
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    private final StubBotConfig config;
    private final CountDownLatch started = new CountDownLatch(1);
    private final ScheduledExecutorService scheduler;

    /** guildId → serverId → connection. */
    private final Map<String, Map<String, ConnectedServer>> connections = new ConcurrentHashMap<>();

    /** Outstanding requests awaiting a correlated reply, keyed by message id. */
    private final Map<String, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();

    private volatile WsMessageListener messageListener;
    private final AtomicInteger configVersion;

    StubWsServer(StubBotConfig config) {
        super(new InetSocketAddress("127.0.0.1", 0));
        this.config = config;
        this.configVersion = new AtomicInteger(config.configVersion());
        setReuseAddr(true);
        // Named and owned, per the repo's executor rule: the sweep and the request timeouts both
        // run here, so a hung fixture is traceable to one thread rather than to "some pool".
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "stub-bot-ws-sweep");
            thread.setDaemon(true);
            return thread;
        });
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    public void onStart() {
        started.countDown();
    }

    /** Blocks until the server is listening, then returns its loopback port. */
    int awaitPort(long timeoutMs) {
        try {
            if (!started.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("stub WebSocket server did not start within " + timeoutMs + "ms");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the stub WebSocket server", e);
        }
        return getPort();
    }

    void startSweep() {
        scheduler.scheduleAtFixedRate(
                this::sweep, config.pingIntervalMs(), config.pingIntervalMs(), TimeUnit.MILLISECONDS);
    }

    void shutdown() {
        scheduler.shutdownNow();
        for (CompletableFuture<JsonObject> future : pending.values()) {
            future.completeExceptionally(new IllegalStateException("Server shutting down"));
        }
        pending.clear();
        try {
            stop(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ── Upgrade authentication ───────────────────────────────────────────────

    @Override
    public ServerHandshakeBuilder onWebsocketHandshakeReceivedAsServer(
            WebSocket conn, Draft draft, ClientHandshake request) throws InvalidDataException {
        Upgrade upgrade = parseUpgrade(request.getResourceDescriptor());
        if (upgrade == null) {
            throw new InvalidDataException(CloseFrame.POLICY_VALIDATION, "unknown WebSocket path");
        }
        if (upgrade.signature == null || upgrade.timestamp == null) {
            StubLog.warn("rejected upgrade for guild " + upgrade.guildId + ": missing auth params");
            throw new InvalidDataException(CloseFrame.POLICY_VALIDATION, "missing auth params");
        }
        // Path WITHOUT the query string — the asymmetry with the HTTP side is the contract.
        if (!Hmac.verify(config.apiKey(), "GET", upgrade.path, "", upgrade.signature, upgrade.timestamp)) {
            StubLog.warn("rejected upgrade for guild " + upgrade.guildId + ": invalid HMAC");
            throw new InvalidDataException(CloseFrame.POLICY_VALIDATION, "invalid signature");
        }
        if (!config.guildId().equals(upgrade.guildId)) {
            StubLog.warn("rejected upgrade: guild " + upgrade.guildId + " is not configured");
            throw new InvalidDataException(CloseFrame.POLICY_VALIDATION, "unknown guild");
        }
        return super.onWebsocketHandshakeReceivedAsServer(conn, draft, request);
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        Upgrade upgrade = parseUpgrade(handshake.getResourceDescriptor());
        if (upgrade == null) {
            conn.close(CloseFrame.POLICY_VALIDATION, "unknown WebSocket path");
            return;
        }

        long now = System.currentTimeMillis();
        ConnectedServer server = new ConnectedServer(upgrade.guildId, upgrade.serverId, conn, now);
        conn.setAttachment(server);

        Map<String, ConnectedServer> guildMap =
                connections.computeIfAbsent(upgrade.guildId, unused -> new ConcurrentHashMap<>());
        ConnectedServer previous = guildMap.put(upgrade.serverId, server);
        if (previous != null && previous.socket().isOpen()) {
            previous.socket().close(CloseFrame.NORMAL, "Replaced by new connection");
        }

        StubLog.info("ws connected: guild=" + upgrade.guildId + " server=" + upgrade.serverId);

        // The real bot pings immediately on connect, which is also how the plugin learns the link is
        // live before its own heartbeat tick.
        send(conn, newId(), "ping", new JsonObject());
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        ConnectedServer server = conn.getAttachment();
        if (server == null) {
            return;
        }
        Map<String, ConnectedServer> guildMap = connections.get(server.guildId());
        if (guildMap != null) {
            guildMap.remove(server.serverId(), server);
            if (guildMap.isEmpty()) {
                connections.remove(server.guildId(), guildMap);
            }
        }
        StubLog.info("ws disconnected: guild=" + server.guildId() + " server=" + server.serverId()
                + " code=" + code + " reason=" + reason);
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        StubLog.warn("ws error: " + ex);
    }

    // ── Message handling ─────────────────────────────────────────────────────

    @Override
    public void onMessage(WebSocket conn, String message) {
        ConnectedServer server = conn.getAttachment();
        if (server == null) {
            return;
        }

        JsonObject envelope;
        try {
            JsonElement parsed = JsonParser.parseString(message);
            if (!parsed.isJsonObject()) {
                return;
            }
            envelope = parsed.getAsJsonObject();
        } catch (JsonParseException e) {
            StubLog.warn("unparseable message from " + server.serverId());
            return;
        }
        if (!envelope.has("id") || !envelope.has("type")) {
            StubLog.warn("malformed message from " + server.serverId() + " (missing id or type)");
            return;
        }

        String id = envelope.get("id").getAsString();
        String type = envelope.get("type").getAsString();
        JsonObject payload = envelope.has("payload") && envelope.get("payload").isJsonObject()
                ? envelope.getAsJsonObject("payload")
                : new JsonObject();

        StubLog.debug("ws recv " + type + " id=" + id + " from " + server.serverId());

        switch (type) {
            case "identify" -> {
                server.touch(System.currentTimeMillis());
                handleIdentify(conn, server, id, payload);
                return;
            }
            case "pong" -> {
                server.touch(System.currentTimeMillis());
                return;
            }
            case "ping" -> {
                // The plugin pings us too; echo the id back so its own correlation works.
                server.touch(System.currentTimeMillis());
                send(conn, id, "pong", new JsonObject());
                return;
            }
            case "health" -> {
                server.touch(System.currentTimeMillis());
                server.setLastHealth(payload);
                return;
            }
            case "console_line" -> {
                if (payload.has("lines") && payload.get("lines").isJsonArray()) {
                    for (JsonElement line : payload.getAsJsonArray("lines")) {
                        if (line.isJsonObject()) {
                            server.addConsoleLine(line.getAsJsonObject());
                        }
                    }
                }
                return;
            }
            case "config.ack" -> {
                server.touch(System.currentTimeMillis());
                if (payload.has("version") && payload.get("version").isJsonPrimitive()) {
                    server.setAcknowledgedConfigVersion(payload.get("version").getAsInt());
                    StubLog.info("config acked by " + server.serverId() + " at version "
                            + server.acknowledgedConfigVersion());
                }
                return;
            }
            default -> {
                // Fall through to correlation.
            }
        }

        CompletableFuture<JsonObject> future = pending.remove(id);
        if (future != null) {
            future.complete(payload);
            return;
        }

        WsMessageListener listener = messageListener;
        if (listener != null) {
            listener.onMessage(server.guildId(), server.serverId(), id, type, payload);
        }
    }

    /**
     * {@code identify}, plus the v3 capability handshake.
     *
     * <p>A v2 client sends metadata only and the bot answers nothing — the connection is simply
     * considered identified. A v3 client additionally declares {@code protocolVersion} and
     * {@code capabilities}, and gets three things back:
     *
     * <ol>
     *   <li>{@code identify_ack} echoing the identify's id, carrying {@code accepted} (false when the
     *       client speaks a protocol newer than the stub understands) and the current
     *       {@code configVersion};
     *   <li>{@code config.push} with {@code version} and {@code modules}, narrowed to the modules the
     *       client actually declared a capability for — pushing config for a module the client cannot
     *       run is how a "silently ignored setting" bug is born;
     *   <li>whatever {@code config.ack} the client sends back, recorded on the connection.
     * </ol>
     *
     * <p>A rejected {@code identify_ack} is deliberately NOT followed by a config push, and the
     * socket stays open: the client is told plainly that it is too new rather than being dropped
     * into a reconnect loop it cannot diagnose.
     */
    private void handleIdentify(WebSocket conn, ConnectedServer server, String id, JsonObject payload) {
        Integer protocolVersion = payload.has("protocolVersion") && payload.get("protocolVersion").isJsonPrimitive()
                ? payload.get("protocolVersion").getAsInt()
                : null;

        List<String> capabilities = new ArrayList<>();
        boolean declaredCapabilities = false;
        if (payload.has("capabilities")) {
            JsonElement raw = payload.get("capabilities");
            if (raw.isJsonArray()) {
                declaredCapabilities = true;
                for (JsonElement element : raw.getAsJsonArray()) {
                    capabilities.add(element.getAsString());
                }
            } else if (raw.isJsonObject()) {
                // Also accept the map form ({"whitelist": true, …}) so a client that models
                // capabilities as flags rather than a list is not silently downgraded to v2.
                declaredCapabilities = true;
                for (Map.Entry<String, JsonElement> entry : raw.getAsJsonObject().entrySet()) {
                    if (entry.getValue().isJsonPrimitive() && entry.getValue().getAsBoolean()) {
                        capabilities.add(entry.getKey());
                    }
                }
            }
        }

        server.setIdentify(payload, protocolVersion, capabilities);

        String serverName = payload.has("serverName") ? payload.get("serverName").getAsString() : server.serverId();
        StubLog.info("identified: server=" + server.serverId() + " name=" + serverName
                + " protocol=" + (protocolVersion == null ? "v2" : String.valueOf(protocolVersion))
                + " capabilities=" + capabilities);

        if (protocolVersion == null || !declaredCapabilities) {
            // v2 client: no ack, no config push. Exactly what the real bot does today.
            return;
        }

        boolean accepted = protocolVersion <= config.maxProtocolVersion();
        JsonObject ack = new JsonObject();
        ack.addProperty("accepted", accepted);
        ack.addProperty("configVersion", configVersion.get());
        if (!accepted) {
            ack.addProperty("reason", "protocolVersion " + protocolVersion
                    + " is newer than the highest this bot speaks (" + config.maxProtocolVersion() + ")");
        }
        send(conn, id, "identify_ack", ack);
        server.setIdentifyAcked(true);

        if (accepted) {
            send(conn, newId(), "config.push", buildConfig(capabilities));
        }
    }

    /** {@code {version, modules}}, with modules narrowed to the client's declared capabilities. */
    private JsonObject buildConfig(Collection<String> capabilities) {
        JsonObject modules = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : config.modules().entrySet()) {
            if (capabilities.isEmpty() || capabilities.contains(entry.getKey())) {
                modules.add(entry.getKey(), entry.getValue());
            }
        }
        JsonObject push = new JsonObject();
        push.addProperty("version", configVersion.get());
        push.add("modules", modules);
        return push;
    }

    // ── Test hooks ───────────────────────────────────────────────────────────

    /** Registers a listener for envelopes the stub does not handle itself. */
    public void setMessageListener(WsMessageListener listener) {
        this.messageListener = listener;
    }

    /** Connected servers for a guild. */
    public List<ConnectedServer> connected(String guildId) {
        Map<String, ConnectedServer> guildMap = connections.get(guildId);
        return guildMap == null ? List.of() : List.copyOf(guildMap.values());
    }

    /** A specific connected server, or null. */
    public ConnectedServer connected(String guildId, String serverId) {
        Map<String, ConnectedServer> guildMap = connections.get(guildId);
        return guildMap == null ? null : guildMap.get(serverId);
    }

    /** Waits for a server to connect, returning it or null on timeout. */
    public ConnectedServer awaitConnection(String guildId, String serverId, long timeoutMs) {
        return await(timeoutMs, () -> connected(guildId, serverId));
    }

    /** Waits for a server to send {@code identify}, returning it or null on timeout. */
    public ConnectedServer awaitIdentify(String guildId, String serverId, long timeoutMs) {
        return await(timeoutMs, () -> {
            ConnectedServer server = connected(guildId, serverId);
            return server != null && server.identify() != null ? server : null;
        });
    }

    /**
     * Broadcasts to every open connection in a guild — {@code role_sync} and friends. Returns the
     * number of sockets it actually reached, which is the signal the bot uses to decide whether the
     * push landed or has to wait for the next join.
     */
    public int broadcast(String guildId, String type, JsonObject payload) {
        Map<String, ConnectedServer> guildMap = connections.get(guildId);
        if (guildMap == null) {
            return 0;
        }
        String id = newId();
        int delivered = 0;
        for (ConnectedServer server : guildMap.values()) {
            if (server.socket().isOpen()) {
                send(server.socket(), id, type, payload);
                delivered++;
            }
        }
        return delivered;
    }

    /** Sends {@code role_sync} to every connected server in the guild. */
    public int sendRoleSync(
            String guildId,
            String uuid,
            String username,
            List<String> targetGroups,
            List<String> managedGroups,
            List<String> groupsAdded,
            List<String> groupsRemoved) {
        JsonObject payload = new JsonObject();
        if (uuid == null) {
            payload.add("uuid", com.google.gson.JsonNull.INSTANCE);
        } else {
            payload.addProperty("uuid", uuid);
        }
        payload.addProperty("username", username);
        payload.add("targetGroups", toArray(targetGroups));
        payload.add("managedGroups", toArray(managedGroups));
        payload.add("groupsAdded", toArray(groupsAdded));
        payload.add("groupsRemoved", toArray(groupsRemoved));
        return broadcast(guildId, "role_sync", payload);
    }

    /** Sends {@code get_players} and waits for the correlated reply. */
    public CompletableFuture<JsonObject> getPlayers(String guildId, String serverId, long timeoutMs) {
        return request(guildId, serverId, "get_players", new JsonObject(), timeoutMs);
    }

    /** Sends {@code run_command} and waits for the correlated reply. */
    public CompletableFuture<JsonObject> runCommand(
            String guildId, String serverId, String command, long timeoutMs) {
        JsonObject payload = new JsonObject();
        payload.addProperty("command", command);
        return request(guildId, serverId, "run_command", payload, timeoutMs);
    }

    /** Sends {@code probe_player} and waits for the correlated reply. */
    public CompletableFuture<JsonObject> probePlayer(
            String guildId, String serverId, String uuid, String username, long timeoutMs) {
        JsonObject payload = new JsonObject();
        payload.addProperty("uuid", uuid);
        payload.addProperty("username", username);
        return request(guildId, serverId, "probe_player", payload, timeoutMs);
    }

    /** Sends {@code update} and waits for the correlated reply. */
    public CompletableFuture<JsonObject> triggerUpdate(String guildId, String serverId, long timeoutMs) {
        return request(guildId, serverId, "update", new JsonObject(), timeoutMs);
    }

    /**
     * Bumps the config version and re-pushes to a connected server — the hot-toggle path a phase 1
     * test drives when it flips a module on or off mid-session.
     */
    public int pushConfig(String guildId, String serverId) {
        ConnectedServer server = connected(guildId, serverId);
        if (server == null || !server.socket().isOpen()) {
            return 0;
        }
        configVersion.incrementAndGet();
        send(server.socket(), newId(), "config.push", buildConfig(server.capabilities()));
        return 1;
    }

    /** The config version the stub currently advertises. */
    public int configVersion() {
        return configVersion.get();
    }

    /**
     * Sends a request and returns a future for the correlated reply.
     *
     * <p>The future is completed from the socket's read thread and timed out from the sweep
     * scheduler — no {@code CompletableFuture} async stage is ever created without naming the
     * executor it runs on, which is the repo-wide rule the conformance suite enforces over this
     * module too.
     */
    public CompletableFuture<JsonObject> request(
            String guildId, String serverId, String type, JsonObject payload, long timeoutMs) {
        ConnectedServer server = connected(guildId, serverId);
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        if (server == null || !server.socket().isOpen()) {
            future.completeExceptionally(new IllegalStateException("Server not connected"));
            return future;
        }

        String id = newId();
        pending.put(id, future);
        scheduler.schedule(
                () -> {
                    CompletableFuture<JsonObject> stale = pending.remove(id);
                    if (stale != null) {
                        stale.completeExceptionally(new TimeoutException("Request timed out (" + type + ")"));
                    }
                },
                timeoutMs,
                TimeUnit.MILLISECONDS);
        send(server.socket(), id, type, payload);
        return future;
    }

    // ── Internals ────────────────────────────────────────────────────────────

    /**
     * Pings every live connection and drops the ones that have gone quiet.
     *
     * <p>Same shape as the bot's sweep: liveness is refreshed by {@code pong} <em>and</em> by
     * {@code health}, so a plugin whose heartbeat carries health but no explicit pong still counts
     * as alive.
     */
    private void sweep() {
        long staleBefore = System.currentTimeMillis() - config.livenessTimeoutMs();
        for (Map.Entry<String, Map<String, ConnectedServer>> guildEntry : connections.entrySet()) {
            for (ConnectedServer server : guildEntry.getValue().values()) {
                if (server.lastSeenMs() < staleBefore) {
                    StubLog.warn("stale connection: guild=" + server.guildId()
                            + " server=" + server.serverId() + " — closing");
                    server.socket().close(CloseFrame.GOING_AWAY, "Heartbeat timeout");
                } else if (server.socket().isOpen()) {
                    send(server.socket(), newId(), "ping", new JsonObject());
                }
            }
        }
    }

    private void send(WebSocket conn, String id, String type, JsonObject payload) {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("id", id);
        envelope.addProperty("type", type);
        envelope.add("payload", payload);
        String json = GSON.toJson(envelope);
        StubLog.debug("ws send " + type + " id=" + id);
        if (conn.isOpen()) {
            conn.send(json);
        }
    }

    private static JsonArray toArray(List<String> values) {
        JsonArray array = new JsonArray();
        if (values != null) {
            for (String value : values) {
                array.add(value);
            }
        }
        return array;
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }

    private static <T> T await(long timeoutMs, java.util.function.Supplier<T> supplier) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            T value = supplier.get();
            if (value != null) {
                return value;
            }
            try {
                Thread.sleep(20L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return supplier.get();
    }

    /** The pieces of a {@code /ws/minecraft/{guildId}?…} upgrade URL the stub cares about. */
    private static Upgrade parseUpgrade(String resourceDescriptor) {
        if (resourceDescriptor == null) {
            return null;
        }
        int question = resourceDescriptor.indexOf('?');
        String path = question < 0 ? resourceDescriptor : resourceDescriptor.substring(0, question);
        String query = question < 0 ? "" : resourceDescriptor.substring(question + 1);

        Matcher matcher = WS_PATH.matcher(path);
        if (!matcher.matches()) {
            return null;
        }

        Map<String, String> params = new LinkedHashMap<>();
        if (!query.isEmpty()) {
            for (String pair : query.split("&")) {
                int equals = pair.indexOf('=');
                if (equals < 0) {
                    continue;
                }
                params.put(
                        URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
            }
        }

        Upgrade upgrade = new Upgrade();
        upgrade.path = path;
        upgrade.guildId = matcher.group(1);
        // The bot defaults an absent serverId to "default" rather than rejecting the upgrade.
        upgrade.serverId = params.getOrDefault("serverId", "default");
        upgrade.signature = params.get("signature");
        upgrade.timestamp = params.get("timestamp");
        return upgrade;
    }

    private static final class Upgrade {
        private String path;
        private String guildId;
        private String serverId;
        private String signature;
        private String timestamp;
    }
}
