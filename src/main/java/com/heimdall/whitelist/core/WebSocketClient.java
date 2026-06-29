package com.heimdall.whitelist.core;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * WebSocket client for the Heimdall bot 2-way tunnel.
 *
 * <p>
 * Connects outbound to the gateway (or bot directly). The plugin never opens
 * inbound ports. Supports auto-reconnect with exponential backoff, heartbeat
 * pings, and request/response correlation.
 *
 * <p>
 * Message protocol: JSON objects with {@code id}, {@code type}, and
 * {@code payload}.
 */
public class WebSocketClient {

  private final PluginLogger logger;
  private final ConfigProvider config;
  private final Gson gson = new Gson();

  private volatile WebSocket webSocket;
  private volatile boolean connected = false;
  private volatile boolean shutdownRequested = false;
  private volatile long lastPong = System.currentTimeMillis();

  // Config
  private String baseUrl;
  private String guildId;
  private String serverId;
  private String hmacSecret;
  private long reconnectDelay;
  private long maxReconnectDelay;
  private long heartbeatInterval;
  private long heartbeatTimeout;

  // State
  private long currentReconnectDelay;
  private ScheduledExecutorService scheduler;
  private ScheduledFuture<?> heartbeatTask;
  /** Reused across reconnects so we don't leak a selector thread per attempt. */
  private HttpClient httpClient;
  /** Collapses concurrent reconnect triggers (timeout + onError) into one. */
  private final AtomicBoolean reconnectScheduled = new AtomicBoolean(false);

  /** Pending request/response correlation */
  private final ConcurrentHashMap<String, CompletableFuture<JsonObject>> pendingRequests = new ConcurrentHashMap<>();

  /** External message handler: (type, full message json) */
  private volatile BiConsumer<String, JsonObject> messageHandler;

  public WebSocketClient(PluginLogger logger, ConfigProvider config) {
    this.logger = logger;
    this.config = config;
  }

  public void updateConfig() {
    this.baseUrl = config.getString("api.baseUrl", "http://localhost:3001");
    this.serverId = config.getString("server.serverId", "default");
    this.hmacSecret = config.getString("api.apiKey", "");
    this.reconnectDelay = config.getLong("websocket.reconnect-delay", 5000);
    this.maxReconnectDelay = config.getLong("websocket.max-reconnect-delay", 30000);
    this.heartbeatInterval = config.getLong("websocket.heartbeat-interval", 30000);
    this.heartbeatTimeout = config.getLong("websocket.heartbeat-timeout", 10000);
  }

  /**
   * Set the guild ID for the WebSocket connection.
   * Must be called before connect() — typically with the value from
   * ApiClient.getGuildId().
   */
  public void setGuildId(String guildId) {
    this.guildId = guildId;
  }

  /**
   * Register a handler for incoming messages from the bot.
   * The handler receives (messageType, fullJsonMessage).
   */
  public void setMessageHandler(BiConsumer<String, JsonObject> handler) {
    this.messageHandler = handler;
  }

  /**
   * Start the WebSocket connection (async). Safe to call repeatedly.
   */
  public void connect() {
    if (shutdownRequested)
      return;
    updateConfig();

    if (guildId == null || guildId.isEmpty()) {
      logger.warning("[WS] Cannot connect: guildId not configured");
      return;
    }

    if (scheduler == null || scheduler.isShutdown()) {
      scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "heimdall-ws");
        t.setDaemon(true);
        return t;
      });
    }

    currentReconnectDelay = reconnectDelay;
    doConnect();
  }

  private void doConnect() {
    if (shutdownRequested)
      return;

    try {
      // Build WS URL: replace http(s) with ws(s), append path + auth params
      String wsBase = baseUrl.replaceFirst("^http", "ws");
      String path = "/ws/minecraft/" + guildId;

      // HMAC auth via query params
      String[] hmac = HmacSigner.sign(hmacSecret, "GET", path, "");
      String signature = hmac[0];
      String timestamp = hmac[1];

      String wsUrl = wsBase + path
          + "?serverId=" + encode(serverId)
          + "&signature=" + encode(signature)
          + "&timestamp=" + encode(timestamp);

      logger.info("[WS] Connecting to " + sanitizeUrl(wsUrl));

      if (httpClient == null) {
        httpClient = HttpClient.newHttpClient();
      }
      httpClient.newWebSocketBuilder()
          .buildAsync(URI.create(wsUrl), new WebSocket.Listener() {
            private final StringBuilder buffer = new StringBuilder();

            @Override
            public void onOpen(WebSocket ws) {
              webSocket = ws;
              connected = true;
              lastPong = System.currentTimeMillis();
              currentReconnectDelay = reconnectDelay; // reset backoff
              logger.info("[WS] Connected to Heimdall bot");

              // Send identify message
              JsonObject identify = new JsonObject();
              identify.addProperty("id", generateId());
              identify.addProperty("type", "identify");
              JsonObject payload = new JsonObject();
              payload.addProperty("serverId", serverId);
              payload.addProperty("serverName",
                  config.getString("server.displayName", serverId));
              identify.add("payload", payload);
              sendRaw(gson.toJson(identify));

              // Start heartbeat
              startHeartbeat();

              ws.request(1);
            }

            @Override
            public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
              buffer.append(data);
              if (last) {
                String text = buffer.toString();
                buffer.setLength(0);
                handleMessage(text);
              }
              ws.request(1);
              return null;
            }

            @Override
            public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
              connected = false;
              webSocket = null;
              stopHeartbeat();
              logger.info("[WS] Disconnected: code=" + statusCode + " reason=" + reason);
              scheduleReconnect();
              return null;
            }

            @Override
            public void onError(WebSocket ws, Throwable error) {
              connected = false;
              webSocket = null;
              stopHeartbeat();
              logger.warning("[WS] Error: " + error.getMessage());
              scheduleReconnect();
            }
          })
          .exceptionally(e -> {
            logger.warning("[WS] Connection failed: " + e.getMessage());
            scheduleReconnect();
            return null;
          });

    } catch (Exception e) {
      logger.warning("[WS] Failed to initiate connection: " + e.getMessage());
      scheduleReconnect();
    }
  }

  private void handleMessage(String text) {
    try {
      JsonObject msg = gson.fromJson(text, JsonObject.class);
      if (msg == null || !msg.has("type") || !msg.has("id"))
        return;

      String type = msg.get("type").getAsString();
      String id = msg.get("id").getAsString();

      // Handle ping → respond with pong
      if ("ping".equals(type)) {
        JsonObject pong = new JsonObject();
        pong.addProperty("id", id);
        pong.addProperty("type", "pong");
        pong.add("payload", new JsonObject());
        sendRaw(gson.toJson(pong));
        lastPong = System.currentTimeMillis();
        return;
      }

      // The bot's pong reply to OUR ping also proves liveness. Without this the
      // only thing refreshing lastPong was a bot-initiated ping, leaving almost
      // no slack against the bot's sweep interval and wedging healthy links.
      if ("pong".equals(type)) {
        lastPong = System.currentTimeMillis();
        return;
      }

      // Check if this is a response to a pending request
      CompletableFuture<JsonObject> pending = pendingRequests.remove(id);
      if (pending != null) {
        pending.complete(msg.has("payload") ? msg.getAsJsonObject("payload") : new JsonObject());
        return;
      }

      // Forward to external handler
      BiConsumer<String, JsonObject> handler = messageHandler;
      if (handler != null) {
        handler.accept(type, msg);
      }

    } catch (Exception e) {
      logger.warning("[WS] Failed to parse message: " + e.getMessage());
    }
  }

  // ── Sending ─────────────────────────────────────────────────────────

  private void sendRaw(String json) {
    WebSocket ws = webSocket;
    if (ws != null && connected) {
      ws.sendText(json, true);
    }
  }

  /**
   * Send a message and wait for a correlated response (by message ID).
   *
   * @param type    Message type (e.g. "probe_result", "player_list")
   * @param payload JSON payload
   * @param timeout Timeout in milliseconds
   * @return Future that completes with the response payload
   */
  public CompletableFuture<JsonObject> sendAndWait(String type, JsonObject payload, long timeout) {
    String id = generateId();
    JsonObject msg = new JsonObject();
    msg.addProperty("id", id);
    msg.addProperty("type", type);
    msg.add("payload", payload != null ? payload : new JsonObject());

    CompletableFuture<JsonObject> future = new CompletableFuture<>();
    pendingRequests.put(id, future);

    sendRaw(gson.toJson(msg));

    // Timeout
    if (scheduler != null && !scheduler.isShutdown()) {
      scheduler.schedule(() -> {
        CompletableFuture<JsonObject> p = pendingRequests.remove(id);
        if (p != null) {
          p.completeExceptionally(new TimeoutException("WS request timed out: " + type));
        }
      }, timeout, TimeUnit.MILLISECONDS);
    }

    return future;
  }

  /**
   * Send a fire-and-forget message (no response expected).
   */
  public void send(String type, JsonObject payload) {
    String id = generateId();
    JsonObject msg = new JsonObject();
    msg.addProperty("id", id);
    msg.addProperty("type", type);
    msg.add("payload", payload != null ? payload : new JsonObject());
    sendRaw(gson.toJson(msg));
  }

  /**
   * Reply to a request by echoing back its id (for request/response correlation).
   */
  public void reply(String requestId, String type, JsonObject payload) {
    JsonObject msg = new JsonObject();
    msg.addProperty("id", requestId);
    msg.addProperty("type", type);
    msg.add("payload", payload != null ? payload : new JsonObject());
    sendRaw(gson.toJson(msg));
  }

  // ── Heartbeat ───────────────────────────────────────────────────────

  private void startHeartbeat() {
    stopHeartbeat();
    if (scheduler == null || scheduler.isShutdown())
      return;
    heartbeatTask = scheduler.scheduleAtFixedRate(() -> {
      if (!connected)
        return;

      // Check for pong timeout. sendClose() only finishes if the peer completes
      // the close handshake — a black-holed peer never does, so onClose never
      // fires and the client wedges forever. Abort the socket and reconnect
      // ourselves instead of waiting on a handshake that will never arrive.
      if (System.currentTimeMillis() - lastPong > heartbeatInterval + heartbeatTimeout) {
        forceReconnect("Heartbeat timeout");
        return;
      }

      // Send ping (the bot sends pings too, but we also send our own)
      JsonObject ping = new JsonObject();
      ping.addProperty("id", generateId());
      ping.addProperty("type", "ping");
      ping.add("payload", new JsonObject());
      sendRaw(gson.toJson(ping));
    }, heartbeatInterval, heartbeatInterval, TimeUnit.MILLISECONDS);
  }

  private void stopHeartbeat() {
    if (heartbeatTask != null) {
      heartbeatTask.cancel(false);
      heartbeatTask = null;
    }
  }

  // ── Reconnection ────────────────────────────────────────────────────

  /**
   * Tear down the current (likely dead) socket and reconnect. Unlike
   * {@code sendClose()}, {@code abort()} doesn't wait on a close handshake, so
   * this recovers from a silently-dropped connection.
   */
  private void forceReconnect(String reason) {
    WebSocket ws = webSocket;
    connected = false;
    webSocket = null;
    stopHeartbeat();
    if (ws != null) {
      try {
        ws.abort();
      } catch (Exception ignored) {
      }
    }
    logger.warning("[WS] " + reason + " — aborting and reconnecting");
    scheduleReconnect();
  }

  private void scheduleReconnect() {
    if (shutdownRequested || scheduler == null || scheduler.isShutdown())
      return;

    // onClose, onError, the buildAsync exceptionally handler and the heartbeat
    // timeout can all fire for the same dead connection. Only schedule once, or
    // we spawn parallel doConnect chains and duplicate sockets.
    if (!reconnectScheduled.compareAndSet(false, true))
      return;

    logger.info("[WS] Reconnecting in " + (currentReconnectDelay / 1000) + "s...");
    scheduler.schedule(() -> {
      reconnectScheduled.set(false);
      doConnect();
    }, currentReconnectDelay, TimeUnit.MILLISECONDS);

    // Exponential backoff
    currentReconnectDelay = Math.min(currentReconnectDelay * 2, maxReconnectDelay);
  }

  /**
   * Re-read config and reconnect in place (used by {@code /hwl reload}). Reuses
   * the existing instance, scheduler and HttpClient instead of constructing a
   * throwaway client, which would orphan the old scheduler and selector thread.
   */
  public void reconnect(String guildId) {
    if (shutdownRequested)
      return;
    setGuildId(guildId);
    updateConfig();
    if (scheduler == null || scheduler.isShutdown()) {
      // No live scheduler (never connected, or previously disconnected) — start
      // fresh. connect() re-reads config and resets backoff.
      connect();
      return;
    }
    currentReconnectDelay = reconnectDelay; // reset backoff for the manual retry
    // Abort the current socket and let scheduleReconnect() (idempotent) drive a
    // single reconnect — the abort's onError would otherwise schedule a second.
    forceReconnect("Reload requested");
  }

  /**
   * Close the connection but keep the instance reusable (used when {@code /hwl
   * reload} turns the tunnel off). Unlike {@link #shutdown()} this does not latch
   * {@code shutdownRequested}, so a later {@link #reconnect}/{@link #connect} can
   * bring it back up.
   */
  public void disconnect() {
    stopHeartbeat();
    WebSocket ws = webSocket;
    webSocket = null;
    connected = false;
    if (ws != null) {
      try {
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "WebSocket disabled");
      } catch (Exception ignored) {
      }
    }
    if (scheduler != null) {
      scheduler.shutdownNow(); // cancels any pending reconnect task
      scheduler = null;
    }
    reconnectScheduled.set(false);
    logger.info("[WS] WebSocket disconnected (tunnel disabled)");
  }

  // ── Lifecycle ───────────────────────────────────────────────────────

  public boolean isConnected() {
    return connected && webSocket != null;
  }

  /**
   * Graceful shutdown — close the connection and stop the scheduler.
   */
  public void shutdown() {
    shutdownRequested = true;
    stopHeartbeat();
    if (webSocket != null) {
      try {
        webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Plugin shutting down");
      } catch (Exception ignored) {
      }
      webSocket = null;
    }
    connected = false;
    if (scheduler != null) {
      scheduler.shutdownNow();
      scheduler = null;
    }
    // Fail all pending requests
    for (Map.Entry<String, CompletableFuture<JsonObject>> entry : pendingRequests.entrySet()) {
      entry.getValue().completeExceptionally(new Exception("WebSocket shutting down"));
    }
    pendingRequests.clear();
    logger.info("[WS] WebSocket client shut down");
  }

  // ── Utilities ───────────────────────────────────────────────────────

  private static String generateId() {
    return UUID.randomUUID().toString().substring(0, 8);
  }

  private static String encode(String value) {
    try {
      return java.net.URLEncoder.encode(value, "UTF-8");
    } catch (Exception e) {
      return value;
    }
  }

  /** Strip HMAC params for safe logging */
  private static String sanitizeUrl(String url) {
    int idx = url.indexOf("?");
    return idx > 0 ? url.substring(0, idx) + "?..." : url;
  }
}
