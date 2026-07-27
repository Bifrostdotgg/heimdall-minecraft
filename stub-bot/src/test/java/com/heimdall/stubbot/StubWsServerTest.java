package com.heimdall.stubbot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** The tunnel: upgrade authentication, the envelope, correlation, and the v3 capability handshake. */
class StubWsServerTest {

    private static final String GUILD = StubBotConfig.DEFAULT_GUILD_ID;
    private static final String KEY = "unit-test-key";
    private static final String SERVER = "survival";

    private StubBot bot;

    private StubBot boot(StubBotConfig config) {
        bot = StubBot.start(config.port(0).apiKey(KEY).bindHost("127.0.0.1"));
        return bot;
    }

    private StubBot boot() {
        return boot(StubBotConfig.withDemoFixtures());
    }

    @AfterEach
    void stopBot() {
        if (bot != null) {
            bot.close();
            bot = null;
        }
    }

    // ── Upgrade authentication ───────────────────────────────────────────────

    @Nested
    @DisplayName("upgrade authentication")
    class Upgrade {

        @Test
        void aCorrectlySignedUpgradeIsAccepted() throws Exception {
            boot();
            try (TestWsClient client = TestWsClient.connect(bot, GUILD, SERVER, KEY)) {
                assertNotNull(bot.ws().awaitConnection(GUILD, SERVER, 2000),
                        "the server should have registered the connection");
                assertNotNull(client.await("ping", 2000),
                        "the bot pings immediately on connect — that is how the plugin learns the "
                                + "link is live before its own heartbeat tick");
            }
        }

        @Test
        @DisplayName("a signature over the path WITH the query string is rejected")
        void signingTheQueryStringIsRejected() {
            boot();
            String timestamp = TestWsClient.timestamp();
            String wrong = Hmac.sign(KEY, timestamp, "GET",
                    "/ws/minecraft/" + GUILD + "?serverId=" + SERVER, "");
            assertThrows(CompletionException.class,
                    () -> TestWsClient.connect(bot, GUILD, SERVER, wrong, timestamp),
                    "this is the exact mistake a developer makes after implementing the HTTP side");
        }

        @Test
        void missingAuthParamsAreRejected() {
            boot();
            assertThrows(CompletionException.class,
                    () -> TestWsClient.connect(bot, GUILD, SERVER, null, null));
        }

        @Test
        void aStaleTimestampIsRejected() {
            boot();
            String stale = String.valueOf(System.currentTimeMillis() / 1000L - 3600L);
            String signature = Hmac.sign(KEY, stale, "GET", "/ws/minecraft/" + GUILD, "");
            assertThrows(CompletionException.class,
                    () -> TestWsClient.connect(bot, GUILD, SERVER, signature, stale));
        }

        @Test
        void aWrongSecretIsRejected() {
            boot();
            String timestamp = TestWsClient.timestamp();
            String signature = Hmac.sign("some-other-key", timestamp, "GET", "/ws/minecraft/" + GUILD, "");
            assertThrows(CompletionException.class,
                    () -> TestWsClient.connect(bot, GUILD, SERVER, signature, timestamp));
        }

        @Test
        @DisplayName("a guild id outside 17-20 digits does not match the route at all")
        void malformedGuildIdIsRejected() {
            boot();
            String timestamp = TestWsClient.timestamp();
            String signature = Hmac.sign(KEY, timestamp, "GET", "/ws/minecraft/123", "");
            assertThrows(CompletionException.class,
                    () -> TestWsClient.connect(bot, "123", SERVER, signature, timestamp));
        }
    }

    // ── identify ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("identify")
    class Identify {

        @Test
        @DisplayName("a v2 client gets NO ack — silence is the v2 contract")
        void v2GetsNoAck() throws Exception {
            boot();
            try (TestWsClient client = TestWsClient.connect(bot, GUILD, SERVER, KEY)) {
                client.identifyV2(SERVER, "Survival");

                ConnectedServer server = bot.ws().awaitIdentify(GUILD, SERVER, 2000);
                assertNotNull(server);
                assertEquals("Survival", server.identify().get("serverName").getAsString());
                assertEquals("2.4.0", server.identify().get("pluginVersion").getAsString());
                assertNull(server.protocolVersion());
                assertFalse(server.identifyAcked());

                assertTrue(client.absent("identify_ack", 400),
                        "acking a v2 client would be a protocol change no deployed plugin asked for");
                assertTrue(client.absent("config.push", 400));
            }
        }

        @Test
        @DisplayName("a v3 client gets identify_ack, then config.push, and its config.ack is recorded")
        void v3Handshake() throws Exception {
            boot();
            try (TestWsClient client = TestWsClient.connect(bot, GUILD, SERVER, KEY)) {
                client.identifyV3(SERVER, "Survival", 1, List.of("whitelist", "rolesync"));

                JsonObject ack = client.await("identify_ack", 3000);
                assertNotNull(ack, "a capability-declaring client must be acknowledged");
                JsonObject ackPayloadIn = TestWsClient.payload(ack);
                assertEquals(3, ackPayloadIn.get("protocolVersion").getAsInt(),
                        "the ack declares the BOT's protocol version, not an echo of the client's");
                // `accepted` is the list of capabilities the bot will honour, in the client's own
                // spelling — not a yes/no. A client reading it as a boolean sees false and
                // downgrades itself while the bot believes it negotiated v3.
                assertEquals(List.of("whitelist", "rolesync"),
                        TestWsClient.strings(ackPayloadIn.getAsJsonArray("accepted")));
                assertEquals(bot.ws().configVersion(),
                        ackPayloadIn.get("configVersion").getAsInt());

                JsonObject push = client.await("config.push", 3000);
                assertNotNull(push, "the ack is immediately followed by the config");
                JsonObject config = TestWsClient.payload(push);
                assertEquals(bot.ws().configVersion(), config.get("version").getAsInt());

                JsonObject modules = config.getAsJsonObject("modules");
                assertTrue(modules.has("whitelist"));
                assertTrue(modules.has("rolesync"));
                assertFalse(modules.has("offenses"),
                        "config for a module the client cannot run is how a silently-ignored "
                                + "setting is born — the push is narrowed to declared capabilities");
                assertFalse(modules.has("console"));

                JsonObject ackPayload = new JsonObject();
                ackPayload.addProperty("version", config.get("version").getAsInt());
                client.send("config.ack", ackPayload);

                ConnectedServer server = bot.ws().awaitIdentify(GUILD, SERVER, 2000);
                assertNotNull(server);
                for (int i = 0; i < 100 && server.acknowledgedConfigVersion() == null; i++) {
                    Thread.sleep(20L);
                }
                assertEquals(config.get("version").getAsInt(), server.acknowledgedConfigVersion());
                assertEquals(List.of("whitelist", "rolesync"), server.capabilities());
            }
        }

        @Test
        @DisplayName("an unsupported capability is dropped from `accepted`, silently")
        void unsupportedCapabilitiesAreOmitted() throws Exception {
            boot();
            try (TestWsClient client = TestWsClient.connect(bot, GUILD, SERVER, KEY)) {
                // whitelist@2 is a major this bot does not speak; teleport@1 is a module it has
                // never heard of. There is no refusal frame for either — omission from `accepted` is
                // the entire signal a client gets, which is why this test exists.
                client.identifyV3(SERVER, "Survival", 3,
                        List.of("whitelist@1", "whitelist@2", "teleport@1", "rolesync@1"));

                JsonObject ack = client.await("identify_ack", 3000);
                assertNotNull(ack);
                assertEquals(List.of("whitelist@1", "rolesync@1"),
                        TestWsClient.strings(TestWsClient.payload(ack).getAsJsonArray("accepted")));
                assertTrue(client.isOpen(),
                        "a partially-understood client is still a working client");
            }
        }

        @Test
        @DisplayName("a bare capability id reads as major 1 and is echoed back verbatim")
        void bareCapabilityIdIsMajorOne() throws Exception {
            boot();
            try (TestWsClient client = TestWsClient.connect(bot, GUILD, SERVER, KEY)) {
                client.identifyV3(SERVER, "Survival", 3, List.of("whitelist"));

                JsonObject ack = client.await("identify_ack", 3000);
                assertEquals(List.of("whitelist"),
                        TestWsClient.strings(TestWsClient.payload(ack).getAsJsonArray("accepted")),
                        "echoed in the client's own spelling — NOT normalised to whitelist@1, "
                                + "because a client comparing what it sent to what came back would "
                                + "otherwise conclude it had been refused");
            }
        }

        @Test
        @DisplayName("modules@1 and config@1 are accepted but are never keys in config.push")
        void metaCapabilitiesAreAckedAndPushNothing() throws Exception {
            boot();
            try (TestWsClient client = TestWsClient.connect(bot, GUILD, SERVER, KEY)) {
                client.identifyV3(SERVER, "Survival", 3, List.of("modules@1", "config@1"));

                JsonObject ack = client.await("identify_ack", 3000);
                assertEquals(List.of("modules@1", "config@1"),
                        TestWsClient.strings(TestWsClient.payload(ack).getAsJsonArray("accepted")));

                JsonObject modules = TestWsClient.payload(client.await("config.push", 3000))
                        .getAsJsonObject("modules");
                assertEquals(0, modules.size(),
                        "they are negotiated and acknowledged like any other capability, but no "
                                + "config document has keys by those names");
            }
        }

        @Test
        @DisplayName("an unregistered server is acked at version 0 and gets no config at all")
        void unregisteredServerGetsNoConfig() throws Exception {
            boot(StubBotConfig.withDemoFixtures().unregisterServer(SERVER));
            try (TestWsClient client = TestWsClient.connect(bot, GUILD, SERVER, KEY)) {
                client.identifyV3(SERVER, "Survival", 3, List.of("whitelist@1"));

                JsonObject ack = client.await("identify_ack", 3000);
                assertNotNull(ack, "it is still a v3 client — the ack is what stops it "
                        + "falling back to v2-compat");
                assertEquals(0, TestWsClient.payload(ack).get("configVersion").getAsInt(),
                        "the bot never reads the config store for a serverId it has no row for");
                assertTrue(client.absent("config.push", 400),
                        "the client runs on its own defaults, which is exactly v2 behaviour");
            }
        }

        @Test
        @DisplayName("a registered server gets the config push, for contrast")
        void registeredServerGetsConfig() throws Exception {
            boot();
            try (TestWsClient client = TestWsClient.connect(bot, GUILD, SERVER, KEY)) {
                client.identifyV3(SERVER, "Survival", 3, List.of("whitelist@1"));

                assertNotNull(client.await("identify_ack", 3000));
                assertNotNull(client.await("config.push", 3000));
            }
        }

        @Test
        @DisplayName("capabilities may also arrive as a flag map")
        void capabilitiesAsAnObject() throws Exception {
            boot();
            try (TestWsClient client = TestWsClient.connect(bot, GUILD, SERVER, KEY)) {
                JsonObject capabilities = new JsonObject();
                capabilities.addProperty("whitelist", true);
                capabilities.addProperty("console", false);

                JsonObject payload = new JsonObject();
                payload.addProperty("serverId", SERVER);
                payload.addProperty("protocolVersion", 1);
                payload.add("capabilities", capabilities);
                client.send("identify", payload);

                assertNotNull(client.await("identify_ack", 3000));
                JsonObject modules = TestWsClient.payload(client.await("config.push", 3000))
                        .getAsJsonObject("modules");
                assertTrue(modules.has("whitelist"));
                assertFalse(modules.has("console"), "declared false means not declared");
            }
        }
    }

    // ── Client-initiated ping ────────────────────────────────────────────────

    @Nested
    @DisplayName("client ping")
    class ClientPing {

        @Test
        @DisplayName("a v3 client gets a pong echoing its id")
        void v3ClientGetsAPong() throws Exception {
            boot();
            try (TestWsClient client = TestWsClient.connect(bot, GUILD, SERVER, KEY)) {
                client.identifyV3(SERVER, "Survival", 3, List.of("whitelist@1"));
                assertNotNull(client.await("identify_ack", 3000));

                String id = client.send("ping", new JsonObject());

                JsonObject pong = client.await("pong", 3000);
                assertNotNull(pong, "a v3 client needs a way to measure its own round trip");
                assertEquals(id, pong.get("id").getAsString(),
                        "the id is echoed so the client can correlate the reply with its request");
            }
        }

        @Test
        @DisplayName("a v2 client gets silence, exactly as before")
        void v2ClientGetsSilence() throws Exception {
            boot();
            try (TestWsClient client = TestWsClient.connect(bot, GUILD, SERVER, KEY)) {
                client.identifyV2(SERVER, "Survival");

                client.send("ping", new JsonObject());

                assertTrue(client.absent("pong", 500),
                        "answering a v2 client would be a protocol change no deployed plugin asked "
                                + "for, and it would look healthy here and nowhere else");
            }
        }

        @Test
        @DisplayName("a client ping earns no liveness credit")
        void clientPingIsNotALivenessSignal() throws Exception {
            boot();
            try (TestWsClient client = TestWsClient.connect(bot, GUILD, SERVER, KEY)) {
                client.identifyV3(SERVER, "Survival", 3, List.of("whitelist@1"));
                assertNotNull(client.await("identify_ack", 3000));
                ConnectedServer server = bot.ws().awaitIdentify(GUILD, SERVER, 2000);
                assertNotNull(server);

                long before = server.lastSeenMs();
                Thread.sleep(20);
                client.send("ping", new JsonObject());
                assertNotNull(client.await("pong", 3000));

                assertEquals(before, server.lastSeenMs(),
                        "liveness must stay bot-attested. A plugin whose modules had all died but "
                                + "whose keepalive thread still ran would otherwise renew its own "
                                + "staleness deadline forever and never trip the sweep");
            }
        }
    }

    // ── Requests and broadcasts ──────────────────────────────────────────────

    @Nested
    @DisplayName("bot → plugin messages")
    class Requests {

        @Test
        @DisplayName("get_players is correlated by the echoed id")
        void getPlayersRoundTrip() throws Exception {
            boot();
            try (TestWsClient client = TestWsClient.connect(bot, GUILD, SERVER, KEY)) {
                assertNotNull(bot.ws().awaitConnection(GUILD, SERVER, 2000));

                CompletableFuture<JsonObject> reply = bot.ws().getPlayers(GUILD, SERVER, 5000);

                JsonObject request = client.await("get_players", 3000);
                assertNotNull(request);

                JsonObject response = new JsonObject();
                com.google.gson.JsonArray players = new com.google.gson.JsonArray();
                players.add("AllowedSteve");
                response.add("players", players);
                client.send(request.get("id").getAsString(), "player_list", response);

                JsonObject payload = reply.get(5, TimeUnit.SECONDS);
                assertEquals(1, payload.getAsJsonArray("players").size());
            }
        }

        @Test
        void runCommandCarriesTheCommandAndIsCorrelated() throws Exception {
            boot();
            try (TestWsClient client = TestWsClient.connect(bot, GUILD, SERVER, KEY)) {
                assertNotNull(bot.ws().awaitConnection(GUILD, SERVER, 2000));

                CompletableFuture<JsonObject> reply = bot.ws().runCommand(GUILD, SERVER, "say hello", 5000);

                JsonObject request = client.await("run_command", 3000);
                assertNotNull(request);
                assertEquals("say hello", TestWsClient.payload(request).get("command").getAsString());

                JsonObject response = new JsonObject();
                response.addProperty("success", true);
                response.addProperty("output", "hello");
                client.send(request.get("id").getAsString(), "command_result", response);

                assertTrue(reply.get(5, TimeUnit.SECONDS).get("success").getAsBoolean());
            }
        }

        @Test
        @DisplayName("a reply with the wrong id does not complete the request")
        void mismatchedIdIsTreatedAsUnsolicited() throws Exception {
            boot();
            try (TestWsClient client = TestWsClient.connect(bot, GUILD, SERVER, KEY)) {
                assertNotNull(bot.ws().awaitConnection(GUILD, SERVER, 2000));

                java.util.concurrent.BlockingQueue<String> unsolicited =
                        new java.util.concurrent.LinkedBlockingQueue<>();
                bot.ws().setMessageListener((guild, server, id, type, payload) -> unsolicited.add(type));

                CompletableFuture<JsonObject> reply = bot.ws().getPlayers(GUILD, SERVER, 800);
                assertNotNull(client.await("get_players", 3000));
                client.send("some-other-id", "player_list", new JsonObject());

                assertEquals("player_list", unsolicited.poll(3, TimeUnit.SECONDS),
                        "an uncorrelated message is forwarded to the listener, not swallowed");
                assertThrows(java.util.concurrent.ExecutionException.class,
                        () -> reply.get(5, TimeUnit.SECONDS),
                        "and the real request still times out");
            }
        }

        @Test
        @DisplayName("an envelope with an empty id or type is rejected, not merely a missing one")
        void emptyIdOrTypeIsMalformed() throws Exception {
            boot();
            try (TestWsClient client = TestWsClient.connect(bot, GUILD, SERVER, KEY)) {
                assertNotNull(bot.ws().awaitConnection(GUILD, SERVER, 2000));

                java.util.concurrent.BlockingQueue<String> seen =
                        new java.util.concurrent.LinkedBlockingQueue<>();
                bot.ws().setMessageListener((guild, server, id, type, payload) -> seen.add(type));

                client.send("", "player_join", new JsonObject());
                client.send("some-id", "", new JsonObject());

                assertNull(seen.poll(1, TimeUnit.SECONDS),
                        "the bot's guard is `!msg.type || !msg.id` — a truthiness test, so an empty "
                                + "string is as malformed as an absent key");

                // ...and a well-formed one still gets through, so the guard is not just rejecting
                // everything.
                client.send("real-id", "player_join", new JsonObject());
                assertEquals("player_join", seen.poll(3, TimeUnit.SECONDS));
            }
        }

        @Test
        @DisplayName("a request to a server that is not connected fails immediately")
        void requestToAbsentServer() {
            boot();
            CompletableFuture<JsonObject> reply = bot.ws().getPlayers(GUILD, "nope", 500);
            assertTrue(reply.isCompletedExceptionally());
        }

        @Test
        @DisplayName("role_sync is a broadcast, and reports how many sockets it reached")
        void roleSyncBroadcast() throws Exception {
            boot();
            try (TestWsClient one = TestWsClient.connect(bot, GUILD, "survival", KEY);
                    TestWsClient two = TestWsClient.connect(bot, GUILD, "creative", KEY)) {
                assertNotNull(bot.ws().awaitConnection(GUILD, "survival", 2000));
                assertNotNull(bot.ws().awaitConnection(GUILD, "creative", 2000));

                int delivered = bot.ws().sendRoleSync(GUILD,
                        "11111111-1111-1111-1111-111111111111", "AllowedSteve",
                        List.of("vip"), List.of("vip", "member"), List.of("vip"), List.of());
                assertEquals(2, delivered,
                        "the count is how the bot decides whether the push landed or has to wait "
                                + "for the next join");

                for (TestWsClient client : List.of(one, two)) {
                    JsonObject message = client.await("role_sync", 3000);
                    assertNotNull(message);
                    JsonObject payload = TestWsClient.payload(message);
                    assertEquals("AllowedSteve", payload.get("username").getAsString());
                    assertEquals(1, payload.getAsJsonArray("groupsAdded").size());
                }
            }
        }

        @Test
        void broadcastToAGuildWithNoConnectionsReportsZero() {
            boot();
            assertEquals(0, bot.ws().sendRoleSync(GUILD, null, "Nobody",
                    List.of(), List.of(), List.of(), List.of()));
        }

        @Test
        @DisplayName("pushConfig bumps the version and re-pushes — the hot-toggle path")
        void hotConfigPush() throws Exception {
            boot();
            try (TestWsClient client = TestWsClient.connect(bot, GUILD, SERVER, KEY)) {
                client.identifyV3(SERVER, "Survival", 1, List.of("whitelist"));
                int initial = TestWsClient.payload(client.await("config.push", 3000)).get("version").getAsInt();

                assertEquals(1, bot.ws().pushConfig(GUILD, SERVER));
                JsonObject second = client.await("config.push", 3000);
                assertNotNull(second);
                assertEquals(initial + 1, TestWsClient.payload(second).get("version").getAsInt());
            }
        }
    }

    // ── Liveness ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("liveness")
    class Liveness {

        @Test
        @DisplayName("a client that never answers a ping is swept")
        void silentClientIsClosed() throws Exception {
            boot(StubBotConfig.withDemoFixtures().pingIntervalMs(100).livenessTimeoutMs(300));
            try (TestWsClient client = TestWsClient.connect(bot, GUILD, SERVER, KEY)) {
                assertNotNull(bot.ws().awaitConnection(GUILD, SERVER, 2000));
                assertTrue(client.awaitClose(10_000),
                        "no pong, no health — the connection has to be reaped or the bot leaks "
                                + "sockets to servers that died without closing");
                assertNull(bot.ws().connected(GUILD, SERVER));
            }
        }

        @Test
        @DisplayName("health alone keeps a connection alive — it doubles as a liveness signal")
        void healthCountsAsLiveness() throws Exception {
            boot(StubBotConfig.withDemoFixtures().pingIntervalMs(100).livenessTimeoutMs(400));
            try (TestWsClient client = TestWsClient.connect(bot, GUILD, SERVER, KEY)) {
                assertNotNull(bot.ws().awaitConnection(GUILD, SERVER, 2000));

                for (int i = 0; i < 12; i++) {
                    JsonObject health = new JsonObject();
                    health.addProperty("tps", 19.9);
                    health.addProperty("onlinePlayers", i);
                    client.send("health", health);
                    Thread.sleep(100L);
                }

                ConnectedServer server = bot.ws().connected(GUILD, SERVER);
                assertNotNull(server, "a plugin whose heartbeat carries health but no pong is alive");
                assertNotNull(server.lastHealth());
                assertEquals(11, server.lastHealth().get("onlinePlayers").getAsInt());
            }
        }

        @Test
        @DisplayName("a client-initiated ping is NOT answered and does NOT refresh liveness")
        void clientPingIsNotAKeepalive() throws Exception {
            // Short sweep so the liveness half of this is observable inside a test.
            boot(StubBotConfig.withDemoFixtures().pingIntervalMs(100).livenessTimeoutMs(400));
            try (TestWsClient client = TestWsClient.connect(bot, GUILD, SERVER, KEY)) {
                assertNotNull(bot.ws().awaitConnection(GUILD, SERVER, 2000));

                java.util.concurrent.BlockingQueue<String> unsolicited =
                        new java.util.concurrent.LinkedBlockingQueue<>();
                bot.ws().setMessageListener((guild, server, id, type, payload) -> unsolicited.add(type));

                client.send("ping-id-1", "ping", new JsonObject());

                assertEquals("ping", unsolicited.poll(3, TimeUnit.SECONDS),
                        "the bot has no ping case at all — a client ping falls through correlation "
                                + "to the same listener trace.report lands on");
                assertTrue(client.absent("pong", 500),
                        "answering would be a protocol the real bot does not speak, and a plugin "
                                + "built against it would look healthy here and be reaped in prod");

                // Keep pinging and do nothing else. If client pings counted as liveness this
                // connection would survive indefinitely; under the real bot's rules it must not.
                long deadline = System.currentTimeMillis() + 8000;
                while (System.currentTimeMillis() < deadline
                        && bot.ws().connected(GUILD, SERVER) != null) {
                    try {
                        client.send("ping", new JsonObject());
                    } catch (RuntimeException e) {
                        break; // the sweep already closed it
                    }
                    Thread.sleep(100L);
                }
                assertNull(bot.ws().connected(GUILD, SERVER),
                        "only pong and health refresh liveness, so a client that merely pings is "
                                + "still a silent server and has to be reaped");
            }
        }

        @Test
        @DisplayName("identify does not refresh liveness either")
        void identifyIsNotAKeepalive() throws Exception {
            boot(StubBotConfig.withDemoFixtures().pingIntervalMs(100).livenessTimeoutMs(400));
            try (TestWsClient client = TestWsClient.connect(bot, GUILD, SERVER, KEY)) {
                assertNotNull(bot.ws().awaitConnection(GUILD, SERVER, 2000));

                long deadline = System.currentTimeMillis() + 8000;
                while (System.currentTimeMillis() < deadline
                        && bot.ws().connected(GUILD, SERVER) != null) {
                    try {
                        client.identifyV2(SERVER, "Survival");
                    } catch (RuntimeException e) {
                        break;
                    }
                    Thread.sleep(100L);
                }
                assertNull(bot.ws().connected(GUILD, SERVER),
                        "the bot's identify handler only records metadata — re-identifying in a "
                                + "loop must not keep a silent server on the books");
            }
        }

        @Test
        @DisplayName("reconnecting with the same serverId replaces the old connection")
        void reconnectReplaces() throws Exception {
            boot();
            TestWsClient first = TestWsClient.connect(bot, GUILD, SERVER, KEY);
            assertNotNull(bot.ws().awaitConnection(GUILD, SERVER, 2000));

            try (TestWsClient second = TestWsClient.connect(bot, GUILD, SERVER, KEY)) {
                assertTrue(first.awaitClose(5000), "the stale socket is closed, not left dangling");
                assertNotNull(bot.ws().connected(GUILD, SERVER));
                assertEquals(1, bot.ws().connected(GUILD).size());
                assertNotNull(second.await("ping", 3000));
            } finally {
                first.close();
            }
        }
    }

    @Test
    @DisplayName("console_line batches are collected per server")
    void consoleLines() throws Exception {
        boot();
        try (TestWsClient client = TestWsClient.connect(bot, GUILD, SERVER, KEY)) {
            assertNotNull(bot.ws().awaitConnection(GUILD, SERVER, 2000));

            com.google.gson.JsonArray lines = new com.google.gson.JsonArray();
            JsonObject line = new JsonObject();
            line.addProperty("ts", System.currentTimeMillis());
            line.addProperty("level", "INFO");
            line.addProperty("msg", "Done (1.234s)! For help, type \"help\"");
            lines.add(line);
            JsonObject payload = new JsonObject();
            payload.add("lines", lines);
            client.send("console_line", payload);

            ConnectedServer server = bot.ws().connected(GUILD, SERVER);
            for (int i = 0; i < 100 && server.consoleLines().isEmpty(); i++) {
                Thread.sleep(20L);
            }
            assertEquals(1, server.consoleLines().size());
            assertEquals("INFO", server.consoleLines().get(0).get("level").getAsString());
        }
    }
}
