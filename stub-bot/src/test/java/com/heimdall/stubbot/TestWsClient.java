package com.heimdall.stubbot;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The plugin's half of the tunnel, as a test client.
 *
 * <p>Built on the JDK's {@code java.net.http.WebSocket} — the same client stack the v2 plugin used —
 * so what the tests exercise is a real handshake against a real server, not a mocked one.
 */
final class TestWsClient implements AutoCloseable {

    private static final Gson GSON = new Gson();

    private final WebSocket socket;
    private final BlockingQueue<JsonObject> inbox;

    private TestWsClient(WebSocket socket, BlockingQueue<JsonObject> inbox) {
        this.socket = socket;
        this.inbox = inbox;
    }

    /**
     * Connects with a correctly signed upgrade URL.
     *
     * <p>The timestamp is captured once and used for both the signature and the query parameter. It
     * would be easy to call {@link #timestamp()} twice here, and it would pass almost always —
     * failing only when the two calls straddle a second boundary.
     */
    static TestWsClient connect(StubBot bot, String guildId, String serverId, String secret) {
        String timestamp = timestamp();
        String signature = Hmac.sign(secret, timestamp, "GET", "/ws/minecraft/" + guildId, "");
        return connect(bot, guildId, serverId, signature, timestamp);
    }

    /** Connects with caller-supplied auth params, for the rejection cases. */
    static TestWsClient connect(
            StubBot bot, String guildId, String serverId, String signature, String timestamp) {
        StringBuilder url = new StringBuilder("ws://127.0.0.1:" + bot.port() + "/ws/minecraft/" + guildId + "?");
        url.append("serverId=").append(encode(serverId));
        if (signature != null) {
            url.append("&signature=").append(encode(signature));
        }
        if (timestamp != null) {
            url.append("&timestamp=").append(encode(timestamp));
        }

        // The queue is created BEFORE the socket and captured directly by the listener. Routing
        // frames through a back-reference to the enclosing TestWsClient looks equivalent, but the
        // server pings the instant the upgrade completes — that frame can be delivered before
        // buildAsync().join() has returned and the reference has been assigned, and the resulting
        // NullPointerException inside onText tears the connection down. That failure looked like a
        // server bug, which is how it cost time the first time round.
        BlockingQueue<JsonObject> inbox = new LinkedBlockingQueue<>();
        WebSocket socket = HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .buildAsync(URI.create(url.toString()), new WebSocket.Listener() {
                    private final StringBuilder buffer = new StringBuilder();

                    @Override
                    public void onOpen(WebSocket webSocket) {
                        webSocket.request(1);
                    }

                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        buffer.append(data);
                        if (last) {
                            String text = buffer.toString();
                            buffer.setLength(0);
                            inbox.add(JsonParser.parseString(text).getAsJsonObject());
                        }
                        webSocket.request(1);
                        return null;
                    }
                })
                .join();

        return new TestWsClient(socket, inbox);
    }

    static String timestamp() {
        return String.valueOf(System.currentTimeMillis() / 1000L);
    }

    void send(String id, String type, JsonObject payload) {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("id", id);
        envelope.addProperty("type", type);
        envelope.add("payload", payload);
        socket.sendText(GSON.toJson(envelope), true).join();
    }

    void send(String type, JsonObject payload) {
        send(UUID.randomUUID().toString(), type, payload);
    }

    /** Sends a v2 {@code identify} — metadata only, no protocol version or capabilities. */
    void identifyV2(String serverId, String serverName) {
        JsonObject payload = new JsonObject();
        payload.addProperty("serverId", serverId);
        payload.addProperty("serverName", serverName);
        payload.addProperty("pluginVersion", "2.4.0");
        payload.addProperty("platform", "bukkit");
        payload.addProperty("mcVersion", "1.8.8");
        send("identify", payload);
    }

    /** Sends a v3 {@code identify} declaring a protocol version and capabilities. */
    void identifyV3(String serverId, String serverName, int protocolVersion, List<String> capabilities) {
        JsonObject payload = new JsonObject();
        payload.addProperty("serverId", serverId);
        payload.addProperty("serverName", serverName);
        payload.addProperty("pluginVersion", "3.0.0");
        payload.addProperty("platform", "bukkit");
        payload.addProperty("protocolVersion", protocolVersion);
        com.google.gson.JsonArray declared = new com.google.gson.JsonArray();
        for (String capability : capabilities) {
            declared.add(capability);
        }
        payload.add("capabilities", declared);
        send("identify", payload);
    }

    /** Waits for the next message of a given type, ignoring anything else (pings, mostly). */
    JsonObject await(String type, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            JsonObject message = inbox.poll(deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
            if (message == null) {
                return null;
            }
            if (type.equals(message.get("type").getAsString())) {
                return message;
            }
        }
        return null;
    }

    /** Asserts nothing of a given type arrives within the window. */
    boolean absent(String type, long windowMs) throws InterruptedException {
        return await(type, windowMs) == null;
    }

    boolean isOpen() {
        return !socket.isInputClosed() && !socket.isOutputClosed();
    }

    /** Waits for the server to close the connection, returning true if it did. */
    boolean awaitClose(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (socket.isInputClosed()) {
                return true;
            }
            Thread.sleep(25L);
        }
        return socket.isInputClosed();
    }

    static JsonObject payload(JsonObject envelope) {
        return envelope.getAsJsonObject("payload");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        socket.abort();
    }
}
