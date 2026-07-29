package com.heimdall.stubbot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code STUB_BOT_REQUEST_ON_ACK}: the hook that lets a shell script drive an on-demand request.
 *
 * <p>The connected smoke runs a real server in a container and cannot reach into this JVM to call
 * {@code bot.ws().getPlayers(...)}, so without this the "the dashboard asks a live server a question"
 * half of the contract is exercised only by unit tests — which is exactly the gap that let v3 ship
 * with the {@code get_players} plumbing complete and nothing subscribed to it.
 */
class OnAckRequestTest {

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

    /** Drives the v3 handshake through to a config.ack, returning the version that was acked. */
    private int handshakeAndAck(TestWsClient client) throws Exception {
        client.identifyV3(SERVER, "Survival", 1, List.of("whitelist"));
        assertNotNull(client.await("identify_ack", 3000));
        JsonObject push = client.await("config.push", 3000);
        assertNotNull(push);
        int version = TestWsClient.payload(push).get("version").getAsInt();
        JsonObject ackPayload = new JsonObject();
        ackPayload.addProperty("version", version);
        client.send("config.ack", ackPayload);
        return version;
    }

    @Test
    @DisplayName("the named request is sent once the server acknowledges its config")
    void theRequestIsSentAfterTheAck() throws Exception {
        boot(StubBotConfig.withDemoFixtures().requestOnAck(List.of("get_players")));
        try (TestWsClient client = TestWsClient.connect(bot, GUILD, SERVER, KEY)) {
            handshakeAndAck(client);

            JsonObject request = client.await("get_players", 5000);
            assertNotNull(request, "after the ack, not after identify — a request a module has to "
                    + "answer must be asked once the modules are up");
            assertTrue(TestWsClient.payload(request).entrySet().isEmpty(),
                    "get_players carries no payload; the server id is the connection");
        }
    }

    @Test
    @DisplayName("a correlated reply completes the request rather than being filed as unsolicited")
    void aReplyIsCorrelated() throws Exception {
        boot(StubBotConfig.withDemoFixtures().requestOnAck(List.of("get_players")));
        try (TestWsClient client = TestWsClient.connect(bot, GUILD, SERVER, KEY)) {
            handshakeAndAck(client);
            JsonObject request = client.await("get_players", 5000);
            assertNotNull(request);

            // What a working plugin sends back, echoing the id. The stub's log line is what the
            // smoke greps for, and it is only produced when this correlation holds.
            JsonObject reply = new JsonObject();
            JsonArray players = new JsonArray();
            JsonObject steve = new JsonObject();
            steve.addProperty("uuid", "11111111-1111-1111-1111-111111111111");
            steve.addProperty("username", "Steve");
            steve.addProperty("ip", "203.0.113.7");
            players.add(steve);
            reply.add("players", players);
            client.send(request.get("id").getAsString(), "player_list", reply);

            // Nothing to await on the client side, so the observable effect is that no second
            // request goes out and the connection stays healthy.
            assertTrue(client.absent("get_players", 500),
                    "the hook fires once per server — a re-push during a hot toggle produces a "
                            + "second ack, and re-asking on each would turn this into noise");
        }
    }

    @Test
    @DisplayName("nothing is sent when the hook is not configured")
    void nothingIsSentByDefault() throws Exception {
        boot(StubBotConfig.withDemoFixtures());
        try (TestWsClient client = TestWsClient.connect(bot, GUILD, SERVER, KEY)) {
            handshakeAndAck(client);

            assertTrue(client.absent("get_players", 800),
                    "the default has to leave every existing scenario exactly as it was");
        }
    }

    @Test
    @DisplayName("whitelist_changed is broadcast as a notification — an id, an empty payload, no reply")
    void whitelistChangedIsANotification() throws Exception {
        boot(StubBotConfig.withDemoFixtures());
        try (TestWsClient client = TestWsClient.connect(bot, GUILD, SERVER, KEY)) {
            handshakeAndAck(client);
            assertNotNull(bot.ws().awaitIdentify(GUILD, SERVER, 2000));

            assertEquals(1, bot.ws().sendWhitelistChanged(GUILD));

            JsonObject frame = client.await("whitelist_changed", 3000);
            assertNotNull(frame);
            assertTrue(TestWsClient.payload(frame).entrySet().isEmpty(),
                    "the frame carries no diff — the bot is the source of truth and the plugin has a "
                            + "conditional sync endpoint already");
            assertTrue(frame.has("id") && !frame.get("id").getAsString().isEmpty(),
                    "v2's client dropped any frame without an id before dispatch, so the bot puts "
                            + "one on everything — even where nothing correlates on it");
        }
    }

    @Test
    @DisplayName("the setting parses from the environment as a comma-separated list")
    void theSettingParsesFromTheEnvironment() {
        StubBotConfig config = StubBotConfig.fromEnvironment(
                java.util.Map.of("STUB_BOT_REQUEST_ON_ACK", "get_players, run_command"),
                new String[0]);

        assertEquals(List.of("get_players", "run_command"), config.requestOnAck(),
                "the smoke harness sets this as an environment variable on a container");
    }
}
