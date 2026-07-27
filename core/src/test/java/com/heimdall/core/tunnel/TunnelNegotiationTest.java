package com.heimdall.core.tunnel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.BuildConstants;
import com.heimdall.core.concurrent.HeimdallExecutors;
import com.heimdall.core.config.ServerRole;
import com.heimdall.core.json.Envelope;
import com.heimdall.core.json.Payload;
import com.heimdall.core.log.LogLevel;
import com.heimdall.core.log.RecordingLogger;
import com.heimdall.core.testing.Await;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The v3 capability handshake, in isolation from any real bot.
 *
 * <p>The behaviour under test is mostly about what happens when the bot says <em>nothing</em>,
 * which is the deployed v2 contract and cannot be produced by pointing at a v3 fixture. The stub
 * covers the affirmative path in the integration suite; this covers silence, refusal, and
 * renegotiation.
 */
class TunnelNegotiationTest {

    private final RecordingLogger logger = new RecordingLogger(true);
    private final FakeTunnelSocketFactory sockets = new FakeTunnelSocketFactory();
    private final List<Payload> pushedConfigs = new CopyOnWriteArrayList<Payload>();

    private HeimdallExecutors executors;
    private TunnelClient client;

    @BeforeEach
    void setUp() {
        executors = new HeimdallExecutors(logger, 2);
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.shutdown();
        }
        executors.shutdown(1000);
    }

    private TunnelSettings.Builder baseSettings() {
        return TunnelSettings.builder()
                .endpoint("http://127.0.0.1:1")
                .guildId("123456789012345678")
                .serverId("survival")
                .apiKey("test-secret-key")
                .reconnectDelayMs(20L)
                .maxReconnectDelayMs(200L)
                .heartbeatIntervalMs(60_000L)
                .negotiationTimeoutMs(60_000L);
    }

    private TunnelClient connect(TunnelSettings settings, Set<String> capabilities) {
        client = TunnelClient.builder(logger, executors)
                .settings(settings)
                .socketFactory(sockets)
                .identitySource(() -> ServerIdentity.builder()
                        .serverName("Survival")
                        .platform("bukkit")
                        .serverSoftware("Paper")
                        .mcVersion("1.20.4")
                        .startedAtMs(1_700_000_000_000L)
                        .role(ServerRole.STANDALONE)
                        .build())
                .capabilitySource(() -> capabilities)
                .configPushHandler(pushedConfigs::add)
                .build();
        client.connect();
        Await.until("the socket to open", () -> client.isConnected());
        return client;
    }

    private static Set<String> caps(String... values) {
        return Collections.unmodifiableSet(new LinkedHashSet<String>(Arrays.asList(values)));
    }

    @Test
    @DisplayName("identify carries the v2 metadata AND the three v3 fields")
    void identifyDeclaresProtocolCapabilitiesAndRole() {
        connect(baseSettings().build(), caps(Capabilities.WHITELIST, Capabilities.CONFIG));

        Envelope identify = sockets.first().firstFrameOfType("identify");
        assertNotNull(identify, "identify must be the first thing sent on a new socket");
        Payload payload = identify.payload();

        // v2 fields, unchanged — a v2 bot has to be able to read every one of them.
        assertEquals("survival", payload.string("serverId", null));
        assertEquals("Survival", payload.string("serverName", null));
        assertEquals(BuildConstants.VERSION, payload.string("pluginVersion", null));
        assertEquals("bukkit", payload.string("platform", null));
        assertEquals("Paper", payload.string("serverSoftware", null));
        assertEquals("1.20.4", payload.string("mcVersion", null));
        assertEquals(1_700_000_000_000L, payload.longValue("startedAt", -1L));

        // v3 additions.
        assertEquals(3, payload.intValue("protocolVersion", -1));
        assertEquals(new LinkedHashSet<String>(
                        Arrays.asList(Capabilities.WHITELIST, Capabilities.CONFIG)),
                new LinkedHashSet<String>(payload.strings("capabilities")),
                "compared as a set: the declaration is a set, and ModuleManager is free to supply "
                        + "one with no defined iteration order");
        assertEquals("standalone", payload.string("role", null),
                "the bot needs the resolved role, not the word 'auto'");
    }

    @Test
    @DisplayName("an accepted identify_ack puts the connection in V3 mode")
    void acceptedAckNegotiatesV3() {
        TunnelClient tunnel = connect(baseSettings().build(), caps(Capabilities.WHITELIST));
        FakeTunnelSocket socket = sockets.first();
        Envelope identify = socket.firstFrameOfType("identify");

        socket.deliver(Envelope.of(identify.id(), "identify_ack",
                Payload.builder().put("accepted", true).put("configVersion", 7).build()));

        assertEquals(ProtocolMode.V3, tunnel.mode());
        assertEquals(7, tunnel.configVersion());
    }

    @Test
    @DisplayName("a refused identify_ack drops to V2 compat, says so loudly, and keeps the socket")
    void refusedAckFallsBackToV2Compat() {
        TunnelClient tunnel = connect(baseSettings().build(), caps(Capabilities.WHITELIST));
        FakeTunnelSocket socket = sockets.first();
        Envelope identify = socket.firstFrameOfType("identify");

        socket.deliver(Envelope.of(identify.id(), "identify_ack", Payload.builder()
                .put("accepted", false)
                .put("configVersion", 1)
                .put("reason", "protocolVersion 3 is newer than the highest this bot speaks (1)")
                .build()));

        assertEquals(ProtocolMode.V2_COMPAT, tunnel.mode());
        assertTrue(tunnel.isConnected(),
                "the bot deliberately keeps the socket open; reconnect-looping on it would be worse "
                        + "than running on cached config");
        assertFalse(logger.at(LogLevel.SEVERE).isEmpty(),
                "every pushed setting is now inert and nothing will fix itself — that deserves "
                        + "severe, whatever the wording ends up being");
    }

    @Test
    @DisplayName("silence within the negotiation window means V2 compat, not an error")
    void silenceMeansV2Compat() {
        TunnelClient tunnel = connect(
                baseSettings().negotiationTimeoutMs(30L).build(), caps(Capabilities.WHITELIST));

        Await.until("the negotiation deadline to expire", () -> tunnel.mode() == ProtocolMode.V2_COMPAT);

        assertTrue(tunnel.isConnected(),
                "a v2 bot answers identify with nothing at all; treating that as a failure would "
                        + "reconnect-loop against every bot that has not been upgraded yet");
        assertTrue(logger.at(LogLevel.SEVERE).isEmpty(),
                "and it is a normal outcome, not an error — a fleet mid-upgrade would otherwise "
                        + "fill its logs with severes about bots that are working fine");
    }

    @Test
    @DisplayName("config.push is applied and acknowledged with the pushed version")
    void configPushIsAppliedAndAcked() {
        connect(baseSettings().build(), caps(Capabilities.WHITELIST));
        FakeTunnelSocket socket = sockets.first();

        Payload document = Payload.builder()
                .put("version", 4)
                .put("modules", Payload.builder()
                        .put("whitelist", Payload.builder().put("enabled", true).build())
                        .build())
                .build();
        socket.deliver(Envelope.fresh("config.push", document));

        assertEquals(1, pushedConfigs.size());
        assertEquals(4, pushedConfigs.get(0).intValue("version", -1));

        Envelope ack = socket.firstFrameOfType("config.ack");
        assertNotNull(ack, "an unacknowledged push is one the bot will send forever");
        assertEquals(4, ack.payload().intValue("version", -1));
    }

    @Test
    @DisplayName("a push the handler cannot apply is STILL acknowledged")
    void aFailedApplyIsStillAcked() {
        client = TunnelClient.builder(logger, executors)
                .settings(baseSettings().build())
                .socketFactory(sockets)
                .capabilitySource(() -> caps(Capabilities.CONFIG))
                .configPushHandler(document -> {
                    throw new IllegalStateException("boom");
                })
                .build();
        client.connect();
        Await.until("the socket to open", () -> client.isConnected());

        FakeTunnelSocket socket = sockets.first();
        socket.deliver(Envelope.fresh("config.push", Payload.builder().put("version", 9).build()));

        Envelope ack = socket.firstFrameOfType("config.ack");
        assertNotNull(ack, "the bot must not be left believing the push was lost");
        assertEquals(9, ack.payload().intValue("version", -1));
        assertFalse(logger.at(LogLevel.SEVERE).isEmpty(),
                "a config the plugin could not apply is worth a severe line");
    }

    @Test
    @DisplayName("every reconnect renegotiates from scratch")
    void reconnectRenegotiates() {
        TunnelClient tunnel = connect(baseSettings().build(), caps(Capabilities.WHITELIST));
        FakeTunnelSocket first = sockets.first();
        Envelope identify = first.firstFrameOfType("identify");
        first.deliver(Envelope.of(identify.id(), "identify_ack",
                Payload.builder().put("accepted", true).put("configVersion", 2).build()));
        assertEquals(ProtocolMode.V3, tunnel.mode());

        first.fireClose(1006, "dropped");

        Await.until("a replacement socket", () -> sockets.createdCount() >= 2);
        Await.until("the tunnel to be back up", () -> tunnel.isConnected());
        assertEquals(ProtocolMode.UNKNOWN, tunnel.mode(),
                "a fleet is upgraded one bot at a time; a reconnect may land on a different one, so "
                        + "caching 'this bot is v3' across connections is how a server ends up on "
                        + "stale config until somebody restarts it");
        assertNotNull(sockets.latest().firstFrameOfType("identify"),
                "the replacement socket has to identify itself all over again");
    }

    @Test
    @DisplayName("mode listeners fire on real changes only")
    void modeListenersFireOnChange() {
        TunnelClient tunnel = connect(baseSettings().build(), caps(Capabilities.WHITELIST));
        final List<String> transitions = new CopyOnWriteArrayList<String>();
        tunnel.onModeChange((previous, current) -> transitions.add(previous + "->" + current));

        FakeTunnelSocket socket = sockets.first();
        Envelope identify = socket.firstFrameOfType("identify");
        Payload accepted = Payload.builder().put("accepted", true).put("configVersion", 1).build();

        socket.deliver(Envelope.of(identify.id(), "identify_ack", accepted));
        socket.deliver(Envelope.of(identify.id(), "identify_ack", accepted));

        assertEquals(Collections.singletonList("UNKNOWN->V3"), new ArrayList<String>(transitions),
                "a repeated ack is not a state change and must not be reported as one");
    }

    @Test
    @DisplayName("an identify_ack echoing a stale handshake id is ignored")
    void staleIdentifyAckIsIgnored() {
        TunnelClient tunnel = connect(baseSettings().build(), caps(Capabilities.WHITELIST));
        FakeTunnelSocket socket = sockets.first();

        AtomicReference<ProtocolMode> observed = new AtomicReference<ProtocolMode>();
        socket.deliver(Envelope.of("not-our-identify-id", "identify_ack",
                Payload.builder().put("accepted", true).put("configVersion", 1).build()));
        observed.set(tunnel.mode());

        assertEquals(ProtocolMode.UNKNOWN, observed.get(),
                "an ack for a handshake from a previous socket must not decide this connection's mode");
    }
}
