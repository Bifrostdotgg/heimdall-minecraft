package com.heimdall.stubbot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The {@code bridge@1} half of the fixture: what the stub records coming in, and what it can push
 * back out.
 *
 * <p>Both halves exist for the connected smoke, which drives a real server in a container and cannot
 * reach into this JVM. Without the capture there is no way to assert that batching produced the
 * shape the bot expects; without {@code STUB_BOT_DISCORD_ON_ACK} there is no way to prove the plugin
 * subscribed to {@code bridge.discord} at all — which is precisely the failure {@code get_players}
 * shipped with once.
 */
class BridgeFramesTest {

    private static final String GUILD = StubBotConfig.DEFAULT_GUILD_ID;
    private static final String KEY = "unit-test-key";
    private static final String SERVER = "survival";

    private StubBot bot;

    private StubBot boot(StubBotConfig config) {
        bot = StubBot.start(config.port(0).apiKey(KEY).bindHost("127.0.0.1"));
        return bot;
    }

    @AfterEach
    void stopBot() {
        if (bot != null) {
            bot.close();
            bot = null;
        }
    }

    /** Drives the v3 handshake through to a config.ack, declaring the bridge capability. */
    private void handshakeAndAck(TestWsClient client) throws Exception {
        client.identifyV3(SERVER, "Survival", 1, List.of("whitelist@1", "bridge@1"));
        assertNotNull(client.await("identify_ack", 3000));
        JsonObject push = client.await("config.push", 3000);
        assertNotNull(push);
        JsonObject ackPayload = new JsonObject();
        ackPayload.addProperty("version", TestWsClient.payload(push).get("version").getAsInt());
        client.send("config.ack", ackPayload);
    }

    // ── Negotiation ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("bridge@1 is accepted, and bridge config is pushed for it")
    void bridgeIsNegotiated() throws Exception {
        boot(StubBotConfig.withDemoFixtures());
        try (TestWsClient client = TestWsClient.connect(bot, GUILD, SERVER, KEY)) {
            client.identifyV3(SERVER, "Survival", 1, List.of("bridge@1"));

            JsonObject ack = client.await("identify_ack", 3000);
            assertNotNull(ack);
            JsonArray accepted = TestWsClient.payload(ack).getAsJsonArray("accepted");
            assertEquals(1, accepted.size());
            assertEquals("bridge@1", accepted.get(0).getAsString(),
                    "echoed in the client's own spelling, not normalised");

            JsonObject push = client.await("config.push", 3000);
            assertNotNull(push);
            assertTrue(TestWsClient.payload(push).getAsJsonObject("modules").has("bridge"),
                    "narrowing is by BASE id, so bridge@1 lets the `bridge` key through — a module "
                            + "that received no config would run on defaults and nobody would know");
        }
    }

    @Test
    @DisplayName("bridge@2 is dropped rather than downgraded")
    void aFutureMajorIsDropped() throws Exception {
        boot(StubBotConfig.withDemoFixtures());
        try (TestWsClient client = TestWsClient.connect(bot, GUILD, SERVER, KEY)) {
            client.identifyV3(SERVER, "Survival", 1, List.of("bridge@2"));

            JsonObject ack = client.await("identify_ack", 3000);
            assertNotNull(ack);
            assertEquals(0, TestWsClient.payload(ack).getAsJsonArray("accepted").size(),
                    "exact major match: a bot that speaks major 1 says nothing about major 2");
        }
    }

    // ── Inbound capture ──────────────────────────────────────────────────────

    @Test
    @DisplayName("bridge.chat lines are captured with uuid, name, msg and ts intact")
    void chatIsCaptured() throws Exception {
        boot(StubBotConfig.withDemoFixtures());
        try (TestWsClient client = TestWsClient.connect(bot, GUILD, SERVER, KEY)) {
            handshakeAndAck(client);
            assertNotNull(bot.ws().awaitIdentify(GUILD, SERVER, 3000));

            JsonArray lines = new JsonArray();
            lines.add(chatLine("11111111-1111-1111-1111-111111111111", "Steve", "  hello  ", 1000L));
            lines.add(chatLine("22222222-2222-2222-2222-222222222222", "Alex", "hi", 1001L));
            JsonObject payload = new JsonObject();
            payload.add("lines", lines);
            client.send("bridge.chat", payload);

            List<JsonObject> captured = awaitChat(2);
            assertEquals("Steve", captured.get(0).get("name").getAsString());
            assertEquals("  hello  ", captured.get(0).get("msg").getAsString(),
                    "verbatim — the fixture must not tidy up what the plugin sent, or it would hide "
                            + "a plugin that did");
            assertEquals(1000L, captured.get(0).get("ts").getAsLong());
            assertEquals("hi", captured.get(1).get("msg").getAsString());
        }
    }

    @Test
    @DisplayName("bridge.event carries its kind, and detail only for a death")
    void eventsAreCaptured() throws Exception {
        boot(StubBotConfig.withDemoFixtures());
        try (TestWsClient client = TestWsClient.connect(bot, GUILD, SERVER, KEY)) {
            handshakeAndAck(client);
            assertNotNull(bot.ws().awaitIdentify(GUILD, SERVER, 3000));

            JsonArray events = new JsonArray();
            events.add(event("join", "Steve", null));
            events.add(event("death", "Steve", "Steve fell from a high place"));
            events.add(event("leave", "Steve", null));
            JsonObject payload = new JsonObject();
            payload.add("events", events);
            client.send("bridge.event", payload);

            List<JsonObject> captured = awaitEvents(3);
            assertEquals("join", captured.get(0).get("kind").getAsString());
            assertFalse(captured.get(0).has("detail"));
            assertEquals("death", captured.get(1).get("kind").getAsString());
            assertEquals("Steve fell from a high place", captured.get(1).get("detail").getAsString());
            assertEquals("leave", captured.get(2).get("kind").getAsString());
        }
    }

    @Test
    @DisplayName("a bridge frame is NOT filed as an unsolicited message")
    void bridgeFramesAreHandledNotFallenThrough() throws Exception {
        // The ordering hazard the design names: a plugin-originated frame must be recognised by
        // type before it reaches correlation or the unsolicited listener. If bridge.chat fell
        // through, the capture above would be empty and the listener would see it instead.
        boot(StubBotConfig.withDemoFixtures());
        final java.util.List<String> unsolicited =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        bot.ws().setMessageListener((guildId, serverId, id, type, payload) -> unsolicited.add(type));
        try (TestWsClient client = TestWsClient.connect(bot, GUILD, SERVER, KEY)) {
            handshakeAndAck(client);
            assertNotNull(bot.ws().awaitIdentify(GUILD, SERVER, 3000));

            JsonArray lines = new JsonArray();
            lines.add(chatLine("11111111-1111-1111-1111-111111111111", "Steve", "hello", 1L));
            JsonObject payload = new JsonObject();
            payload.add("lines", lines);
            client.send("bridge.chat", payload);

            awaitChat(1);
            assertFalse(unsolicited.contains("bridge.chat"), "handled, not fallen through");
        }
    }

    // ── Outbound ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("STUB_BOT_DISCORD_ON_ACK pushes bridge.discord once the config is acked")
    void discordIsPushedOnAck() throws Exception {
        boot(StubBotConfig.withDemoFixtures()
                .discordOnAck(List.of("§b[Discord] §fsomeone: hello")));
        try (TestWsClient client = TestWsClient.connect(bot, GUILD, SERVER, KEY)) {
            handshakeAndAck(client);

            JsonObject frame = client.await("bridge.discord", 5000);
            assertNotNull(frame, "after the ack, not after identify — the bridge module has to be "
                    + "up or the frame lands on no subscription and is silently discarded");
            JsonArray messages = TestWsClient.payload(frame).getAsJsonArray("messages");
            assertEquals(1, messages.size());
            JsonObject message = messages.get(0).getAsJsonObject();
            assertEquals("§b[Discord] §fsomeone: hello", message.get("text").getAsString(),
                    "a FINISHED legacy-§ string: the bot resolved the template and inserted the "
                            + "user's content after formatting, so nothing typed in Discord can "
                            + "inject a colour code");
            assertTrue(message.get("ts").getAsLong() > 0L);
            assertTrue(frame.has("id") && !frame.get("id").getAsString().isEmpty(),
                    "a notification still carries an id — v2's client dropped any frame without one");

            assertTrue(client.absent("bridge.discord", 500),
                    "once per server: a re-push during a hot toggle produces a second ack, and "
                            + "re-sending on each would turn this into noise");
        }
    }

    @Test
    @DisplayName("several lines go in one frame, in order")
    void severalLinesInOneFrame() throws Exception {
        boot(StubBotConfig.withDemoFixtures().discordOnAck(List.of("first", "second")));
        try (TestWsClient client = TestWsClient.connect(bot, GUILD, SERVER, KEY)) {
            handshakeAndAck(client);

            JsonArray messages =
                    TestWsClient.payload(client.await("bridge.discord", 5000))
                            .getAsJsonArray("messages");
            assertEquals(2, messages.size());
            assertEquals("first", messages.get(0).getAsJsonObject().get("text").getAsString());
            assertEquals("second", messages.get(1).getAsJsonObject().get("text").getAsString());
        }
    }

    @Test
    @DisplayName("nothing is pushed when the hook is not configured")
    void nothingIsPushedByDefault() throws Exception {
        boot(StubBotConfig.withDemoFixtures());
        try (TestWsClient client = TestWsClient.connect(bot, GUILD, SERVER, KEY)) {
            handshakeAndAck(client);

            assertTrue(client.absent("bridge.discord", 800),
                    "the default has to leave every existing scenario exactly as it was");
        }
    }

    @Test
    @DisplayName("the hook parses from the environment on `|`, not on a comma")
    void theSettingParsesFromTheEnvironment() {
        // A comma separator would split a perfectly ordinary chat line in half. A request type
        // never contains a comma; a rendered sentence very often does.
        StubBotConfig config = StubBotConfig.fromEnvironment(
                java.util.Map.of("STUB_BOT_DISCORD_ON_ACK", "hello, world|second line"),
                new String[0]);

        assertEquals(List.of("hello, world", "second line"), config.discordOnAck());
    }

    @Test
    @DisplayName("sendBridgeDiscord answers 0 for a server that is not connected")
    void sendingToNobodyIsZero() {
        boot(StubBotConfig.withDemoFixtures());

        assertEquals(0, bot.ws().sendBridgeDiscord(GUILD, "nowhere", List.of("hi")));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static JsonObject chatLine(String uuid, String name, String msg, long ts) {
        JsonObject line = new JsonObject();
        line.addProperty("uuid", uuid);
        line.addProperty("name", name);
        line.addProperty("msg", msg);
        line.addProperty("ts", ts);
        return line;
    }

    private static JsonObject event(String kind, String name, String detail) {
        JsonObject event = new JsonObject();
        event.addProperty("kind", kind);
        event.addProperty("uuid", "11111111-1111-1111-1111-111111111111");
        event.addProperty("name", name);
        if (detail != null) {
            event.addProperty("detail", detail);
        }
        event.addProperty("ts", 1L);
        return event;
    }

    /** Waits for the stub to have captured {@code expected} chat lines. */
    private List<JsonObject> awaitChat(int expected) {
        return await(expected, () -> bot.ws().connected(GUILD, SERVER).bridgeChatLines());
    }

    private List<JsonObject> awaitEvents(int expected) {
        return await(expected, () -> bot.ws().connected(GUILD, SERVER).bridgeEvents());
    }

    private static List<JsonObject> await(
            int expected, java.util.function.Supplier<List<JsonObject>> supplier) {
        long deadline = System.currentTimeMillis() + 5000L;
        List<JsonObject> seen = supplier.get();
        while (seen.size() < expected && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(20L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
            seen = supplier.get();
        }
        assertEquals(expected, seen.size(), "the stub never captured the frame");
        return seen;
    }
}
