package com.heimdall.core.tunnel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.heimdall.core.BuildConstants;
import com.heimdall.core.concurrent.HeimdallExecutors;
import com.heimdall.core.config.ServerRole;
import com.heimdall.core.json.Envelope;
import com.heimdall.core.json.Payload;
import com.heimdall.core.log.RecordingLogger;
import com.heimdall.core.remoteconfig.ConfigDocument;
import com.heimdall.core.remoteconfig.RemoteConfig;
import com.heimdall.core.testing.Await;
import com.heimdall.stubbot.ConnectedServer;
import com.heimdall.stubbot.StubBot;
import com.heimdall.stubbot.StubBotConfig;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The real client against the real fixture: HMAC upgrade, handshake, liveness, correlation.
 *
 * <p>Everything here runs against a live {@code stub-bot} over a real loopback WebSocket. That is
 * the point — the unit tests prove the client behaves correctly given a socket that behaves as
 * described, and this proves the description is right. A wrongly-signed upgrade, an identify the bot
 * cannot parse or a pong the sweep does not count all pass a fake and fail here.
 */
class TunnelStubIntegrationTest {

    private static final String GUILD = StubBotConfig.DEFAULT_GUILD_ID;
    private static final String API_KEY = StubBotConfig.DEFAULT_API_KEY;
    private static final String SERVER_ID = "survival";

    private final RecordingLogger logger = new RecordingLogger(true);

    @TempDir
    Path dataDir;

    private StubBot bot;
    private HeimdallExecutors executors;
    private TunnelClient client;

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.shutdown();
        }
        if (executors != null) {
            executors.shutdown(1000);
        }
        if (bot != null) {
            bot.close();
        }
    }

    private TunnelSettings.Builder settings(String baseUrl) {
        return TunnelSettings.builder()
                .endpoint(baseUrl)
                .guildId(GUILD)
                .serverId(SERVER_ID)
                .apiKey(API_KEY)
                .reconnectDelayMs(20L)
                .maxReconnectDelayMs(160L)
                .heartbeatIntervalMs(60_000L)
                .negotiationTimeoutMs(2_000L);
    }

    private TunnelClientBuilder clientBuilder(TunnelSettings settings, Set<String> capabilities) {
        executors = new HeimdallExecutors(logger, 2);
        return TunnelClient.builder(logger, executors)
                .settings(settings)
                .identitySource(() -> ServerIdentity.builder()
                        .serverName("Survival")
                        .platform("bukkit")
                        .serverSoftware("Paper")
                        .mcVersion("1.20.4")
                        .startedAtMs(1_700_000_000_000L)
                        .role(ServerRole.STANDALONE)
                        .build())
                .capabilitySource(() -> capabilities);
    }

    private static Set<String> caps(String... values) {
        return Collections.unmodifiableSet(new LinkedHashSet<String>(Arrays.asList(values)));
    }

    /**
     * The stub's {@code modules} object, keyed however the test needs.
     *
     * <p>Deliberately parameterised rather than using the demo defaults, because the key a module's
     * config is filed under is exactly what the narrowing question below is about.
     */
    private static JsonObject modules(String... ids) {
        JsonObject modules = new JsonObject();
        for (String id : ids) {
            JsonObject entry = new JsonObject();
            entry.addProperty("enabled", true);
            entry.addProperty("mode", "websocket");
            modules.add(id, entry);
        }
        return modules;
    }

    // ── (a) the full v3 handshake ────────────────────────────────────────────

    @Test
    @DisplayName("(a) real HMAC upgrade, identify with capabilities, identify_ack, config.push, config.ack")
    void fullV3Handshake() {
        bot = StubBot.start(StubBotConfig.withDemoFixtures()
                .port(0)
                .maxProtocolVersion(3)
                .configVersion(4)
                .modules(modules(Capabilities.WHITELIST, Capabilities.ROLE_SYNC)));

        RemoteConfig remoteConfig =
                new RemoteConfig(logger, dataDir.resolve("remote-config.json"), ConfigDocument.empty());
        client = clientBuilder(settings(bot.baseUrl()).build(),
                caps(Capabilities.WHITELIST, Capabilities.ROLE_SYNC))
                .configPushHandler(remoteConfig)
                .build();
        client.onModeChange(remoteConfig);
        client.connect();

        // The upgrade itself is the first assertion: the stub verifies the HMAC over the path
        // WITHOUT its query string, and a client that signed it the other way never gets here.
        ConnectedServer connected =
                Await.value("the stub to see an identify", () -> bot.ws().awaitIdentify(GUILD, SERVER_ID, 100));
        assertEquals(Integer.valueOf(3), connected.protocolVersion());
        assertEquals(Arrays.asList(Capabilities.WHITELIST, Capabilities.ROLE_SYNC),
                connected.capabilities());
        assertEquals("Survival", connected.identify().get("serverName").getAsString());
        assertEquals(BuildConstants.VERSION, connected.identify().get("pluginVersion").getAsString());
        assertEquals("standalone", connected.identify().get("role").getAsString());

        Await.until("the client to negotiate v3", () -> client.mode() == ProtocolMode.V3);
        assertEquals(4, client.configVersion());

        Await.until("the pushed config to be applied",
                () -> remoteConfig.moduleEnabled(Capabilities.WHITELIST));
        assertEquals("websocket",
                remoteConfig.moduleSettings(Capabilities.ROLE_SYNC).string("mode", null),
                "the stub sends settings flat alongside `enabled`, which is the shape the parser has "
                        + "to tolerate or every setting silently reads as absent");

        Await.until("the stub to record our config.ack",
                () -> Integer.valueOf(4).equals(bot.ws().connected(GUILD, SERVER_ID)
                        .acknowledgedConfigVersion()));
    }

    @Test
    @DisplayName("(a2) a hot re-push is applied and re-acked at the new version")
    void hotConfigPush() {
        bot = StubBot.start(StubBotConfig.withDemoFixtures()
                .port(0)
                .maxProtocolVersion(3)
                .configVersion(1)
                .modules(modules(Capabilities.WHITELIST)));

        RemoteConfig remoteConfig =
                new RemoteConfig(logger, dataDir.resolve("remote-config.json"), ConfigDocument.empty());
        client = clientBuilder(settings(bot.baseUrl()).build(), caps(Capabilities.WHITELIST))
                .configPushHandler(remoteConfig)
                .build();
        client.onModeChange(remoteConfig);
        client.connect();
        Await.until("the client to negotiate v3", () -> client.mode() == ProtocolMode.V3);

        assertEquals(1, bot.ws().pushConfig(GUILD, SERVER_ID));

        Await.until("the newer config version to be acked",
                () -> Integer.valueOf(2).equals(bot.ws().connected(GUILD, SERVER_ID)
                        .acknowledgedConfigVersion()));
        assertEquals(2, remoteConfig.version());
    }

    // ── (b) v2 compatibility ─────────────────────────────────────────────────

    @Test
    @DisplayName("(b) a bot that refuses our protocol version drops us to V2 compat and keeps the socket")
    void aBotTooOldForUsFallsBackToV2Compat() {
        bot = StubBot.start(StubBotConfig.withDemoFixtures().port(0).maxProtocolVersion(1));

        client = clientBuilder(settings(bot.baseUrl()).build(), caps(Capabilities.WHITELIST)).build();
        client.connect();

        Await.until("the client to fall back", () -> client.mode() == ProtocolMode.V2_COMPAT);
        assertTrue(client.isConnected(),
                "the bot deliberately keeps the socket open rather than dropping a client it cannot "
                        + "configure into a reconnect loop it could not diagnose");
        assertNotNull(bot.ws().connected(GUILD, SERVER_ID));
    }

    @Test
    @DisplayName("(b2) a bot that never answers identify — the deployed v2 case — also means V2 compat")
    void silenceMeansV2CompatOverARealSocket() throws Exception {
        // stub-bot always answers a v3 identify, so a bare socket server is the only way to produce
        // the case that actually matters: an unupgraded bot, where silence IS the protocol.
        try (FlakyWebSocketServer silent = new FlakyWebSocketServer()) {
            client = clientBuilder(settings(silent.baseUrl()).negotiationTimeoutMs(120L).build(),
                    caps(Capabilities.WHITELIST)).build();
            client.connect();

            Await.until("the negotiation deadline to expire",
                    () -> client.mode() == ProtocolMode.V2_COMPAT);
            assertTrue(client.isConnected(),
                    "treating silence as a failure would reconnect-loop against every bot that has "
                            + "not been upgraded yet");
            assertEquals(1, silent.acceptedConnections(),
                    "and it must not have reconnected while waiting");
        }
    }

    // ── (c) liveness ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("(c) answering the bot's pings survives its liveness sweep")
    void survivesTheLivenessSweep() {
        // The bot's sweep is the only liveness mechanism there is: a client-initiated ping gets no
        // reply and does not count. A client that failed to answer would be reaped here.
        bot = StubBot.start(StubBotConfig.withDemoFixtures()
                .port(0)
                .maxProtocolVersion(3)
                .pingIntervalMs(40L)
                .livenessTimeoutMs(200L));

        client = clientBuilder(settings(bot.baseUrl()).build(), caps(Capabilities.WHITELIST)).build();
        client.connect();
        Await.until("the client to negotiate v3", () -> client.mode() == ProtocolMode.V3);

        ConnectedServer connected = bot.ws().connected(GUILD, SERVER_ID);
        long connectedAt = connected.connectedAtMs();

        // Several sweep intervals and several liveness timeouts' worth of them.
        Await.until("the connection to have survived multiple sweeps",
                () -> bot.ws().connected(GUILD, SERVER_ID) != null
                        && bot.ws().connected(GUILD, SERVER_ID).lastSeenMs() > connectedAt + 200L);

        assertTrue(client.isConnected());
        assertNotNull(bot.ws().connected(GUILD, SERVER_ID),
                "the sweep closes any connection silent for the liveness timeout; staying open is "
                        + "the assertion");
    }

    // ── (d) subscriptions ────────────────────────────────────────────────────

    @Test
    @DisplayName("(d) role_sync reaches a subscribed handler, on heimdall-io rather than the socket thread")
    void roleSyncReachesASubscriber() {
        bot = StubBot.start(StubBotConfig.withDemoFixtures().port(0).maxProtocolVersion(3));

        client = clientBuilder(settings(bot.baseUrl()).build(), caps(Capabilities.ROLE_SYNC)).build();
        AtomicReference<Envelope> received = new AtomicReference<Envelope>();
        AtomicReference<String> handlerThread = new AtomicReference<String>();
        client.subscribe("role_sync", envelope -> {
            handlerThread.set(Thread.currentThread().getName());
            received.set(envelope);
        });
        client.connect();
        Await.until("the client to negotiate v3", () -> client.mode() == ProtocolMode.V3);

        assertEquals(1, bot.ws().sendRoleSync(GUILD,
                "11111111-2222-3333-4444-555555555555", "Steve",
                Arrays.asList("vip"), Arrays.asList("vip", "member"),
                Arrays.asList("vip"), Collections.<String>emptyList()));

        Await.until("the handler to run", () -> received.get() != null);
        Payload payload = received.get().payload();
        assertEquals("Steve", payload.string("username", null));
        assertEquals(Arrays.asList("vip", "member"), payload.strings("managedGroups"));
        assertTrue(handlerThread.get().startsWith("heimdall-io"),
                "a handler that applies LuckPerms groups must not be holding the socket's reading "
                        + "thread while it does");
    }

    // ── (e) correlation ──────────────────────────────────────────────────────

    @Test
    @DisplayName("(e) the bot asks, we reply with the echoed id, and its future completes")
    void correlatedRequestsRoundTrip() throws Exception {
        bot = StubBot.start(StubBotConfig.withDemoFixtures().port(0).maxProtocolVersion(3));

        client = clientBuilder(settings(bot.baseUrl()).build(),
                caps(Capabilities.WHITELIST, Capabilities.CONSOLE)).build();
        client.subscribe("get_players", envelope -> client.reply(envelope.id(), "player_list",
                Payload.builder().putStrings("players", Arrays.asList("Steve", "Alex")).build()));
        client.subscribe("run_command", envelope -> client.reply(envelope.id(), "command_result",
                Payload.builder()
                        .put("success", true)
                        .put("output", "ran: " + envelope.payload().string("command", ""))
                        .build()));
        client.connect();
        Await.until("the client to negotiate v3", () -> client.mode() == ProtocolMode.V3);

        CompletableFuture<JsonObject> players = bot.ws().getPlayers(GUILD, SERVER_ID, 5_000L);
        assertEquals(Arrays.asList("Steve", "Alex"),
                Arrays.asList(players.get(5, TimeUnit.SECONDS).getAsJsonArray("players").get(0)
                        .getAsString(),
                        players.get(5, TimeUnit.SECONDS).getAsJsonArray("players").get(1)
                                .getAsString()));

        CompletableFuture<JsonObject> command =
                bot.ws().runCommand(GUILD, SERVER_ID, "say hello", 5_000L);
        JsonObject result = command.get(5, TimeUnit.SECONDS);
        assertTrue(result.get("success").getAsBoolean());
        assertEquals("ran: say hello", result.get("output").getAsString(),
                "a reply that minted a fresh id instead of echoing would leave this future to time "
                        + "out, and the bot would file our answer as unsolicited");
    }

    // ── (g) the reconnect storm ──────────────────────────────────────────────

    @Test
    @DisplayName("(g) refused connections back off, reset on success, and leak no threads over 20 cycles")
    void reconnectStormLeaksNothing() throws Exception {
        try (FlakyWebSocketServer flaky = new FlakyWebSocketServer()) {
            flaky.refuseNext(4);

            client = clientBuilder(settings(flaky.baseUrl())
                    .reconnectDelayMs(10L)
                    .maxReconnectDelayMs(80L)
                    .negotiationTimeoutMs(60_000L)
                    .build(), caps(Capabilities.WHITELIST)).build();

            client.connect();
            Await.until("the client to get through the refusals", () -> client.isConnected());
            assertTrue(flaky.totalConnections() >= 5,
                    "four refusals then an accept: " + flaky.totalConnections());

            // Baselined AFTER the first successful connection, so the threads a healthy connection
            // legitimately owns are part of the baseline rather than half the allowance. (That the
            // backoff resets on a successful open is asserted precisely, and deterministically, in
            // TunnelClientInvariantsTest — inferring it here from wall-clock timing would be
            // guessing.)
            Await.until("the first connection's threads to settle",
                    () -> connectionThreadCount() == connectionThreadCount());
            int baseline = connectionThreadCount();

            // Twenty full cycles: the client aborts, reconnects, and the server accepts again.
            for (int cycle = 0; cycle < 20; cycle++) {
                final int expected = flaky.acceptedConnections() + 1;
                client.forceReconnect("test cycle " + cycle);
                Await.until("reconnect cycle " + cycle,
                        () -> flaky.acceptedConnections() >= expected && client.isConnected());
            }

            assertTrue(client.isConnected());
            Await.until("the abandoned connections' threads to be reaped",
                    () -> connectionThreadCount() <= baseline + 2);
            assertTrue(connectionThreadCount() <= baseline + 2,
                    "twenty connect cycles must not accumulate threads — v2 leaked a selector "
                            + "thread per attempt. baseline=" + baseline
                            + " after=" + connectionThreadCount());
        }
    }

    // ── (h) teardown ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("(h) shutdown closes the socket the bot can see and fails everything outstanding")
    void shutdownIsVisibleToTheBot() {
        bot = StubBot.start(StubBotConfig.withDemoFixtures().port(0).maxProtocolVersion(3));

        client = clientBuilder(settings(bot.baseUrl()).build(), caps(Capabilities.WHITELIST)).build();
        client.connect();
        Await.until("the client to negotiate v3", () -> client.mode() == ProtocolMode.V3);

        CompletableFuture<Payload> outstanding =
                client.sendAndWait("probe_result", Payload.empty(), 60_000L);
        client.shutdown();

        assertTrue(outstanding.isCompletedExceptionally());
        Await.until("the bot to see the disconnect", () -> bot.ws().connected(GUILD, SERVER_ID) == null);
    }

    // ── The capability/module-id narrowing question ──────────────────────────

    @Test
    @DisplayName("the bot narrows config.push by EXACT capability id — versioned ids match nothing")
    void configNarrowingIsAnExactMatchOnTheCapabilityId() {
        // Pinned rather than asserted-as-correct. The bot files module config under an unversioned
        // module id (`whitelist`) and the client declares a versioned capability (`whitelist@1`);
        // stub-bot's narrowing is exact string equality, so nothing matches and an empty — but
        // perfectly valid — config arrives. Either the bot matches on Capabilities.moduleId(), or
        // the two identifiers are the same string. That is a bot-side decision for phase 1f; this
        // test exists so it is made against a fact rather than an assumption.
        bot = StubBot.start(StubBotConfig.withDemoFixtures()
                .port(0)
                .maxProtocolVersion(3)
                .modules(modules("whitelist", "rolesync")));

        RemoteConfig remoteConfig =
                new RemoteConfig(logger, dataDir.resolve("remote-config.json"), ConfigDocument.empty());
        client = clientBuilder(settings(bot.baseUrl()).build(), caps(Capabilities.WHITELIST))
                .configPushHandler(remoteConfig)
                .build();
        client.onModeChange(remoteConfig);
        client.connect();
        Await.until("the client to negotiate v3", () -> client.mode() == ProtocolMode.V3);
        Await.until("a push to arrive", () -> remoteConfig.version() >= 0);

        assertFalse(remoteConfig.moduleEnabled("whitelist"),
                "the versioned capability id does not match the unversioned module id, so no config "
                        + "for it is pushed at all");
        assertEquals("whitelist", Capabilities.moduleId(Capabilities.WHITELIST));
        assertEquals(1, Capabilities.version(Capabilities.WHITELIST));
    }

    /**
     * How many threads belonging to <em>this plugin's connections</em> are alive.
     *
     * <p>Counted by name rather than with {@link Thread#activeCount()}, which sweeps in JUnit's own
     * threads, the JIT's, the GC's and the stub bot's — so a tolerance wide enough to absorb that
     * noise is also wide enough to absorb the leak being tested for. These four names are the whole
     * population that a connect cycle creates: Heimdall's own pools, and nv-websocket-client's
     * reading, writing and connect threads (their names are the library's, verified against the
     * 2.14 bytecode).
     */
    private static int connectionThreadCount() {
        int count = 0;
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            String name = thread.getName();
            if (name.startsWith("heimdall-")
                    || "ReadingThread".equals(name)
                    || "WritingThread".equals(name)
                    || "ConnectThread".equals(name)) {
                count++;
            }
        }
        return count;
    }
}
