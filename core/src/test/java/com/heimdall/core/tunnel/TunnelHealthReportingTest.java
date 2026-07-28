package com.heimdall.core.tunnel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.concurrent.HeimdallExecutors;
import com.heimdall.core.json.Payload;
import com.heimdall.core.log.RecordingLogger;
import com.heimdall.core.testing.Await;
import com.heimdall.core.testing.MutableClock;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The heartbeat's health payload is optional; its ping is not. Departure D69.
 *
 * <p>Health reporting became a module an operator can switch off from the dashboard, and the hazard
 * that creates is specific: the bot's 90-second sweep refreshes a connection's last-seen on
 * {@code pong} <strong>and</strong> on {@code health}, so an implementation that gated the whole tick
 * would have every server with health switched off reaped as dead a minute and a half later. Each
 * test below therefore asserts what did <em>not</em> stop as well as what did.
 *
 * <p>Everything is driven through {@link FakeTunnelSocket} and a hand-driven tick — no real time
 * passes, so "the next tick" is a method call rather than thirty seconds.
 */
class TunnelHealthReportingTest {

    private final RecordingLogger logger = new RecordingLogger(true);
    private final MutableClock clock = new MutableClock();
    private final FakeTunnelSocketFactory sockets = new FakeTunnelSocketFactory();
    private final CapturingScheduler ws = new CapturingScheduler();

    /** Counts how often the platform was actually asked, not just how many frames went out. */
    private final AtomicInteger snapshotsTaken = new AtomicInteger();

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

    private TunnelSettings settings() {
        return TunnelSettings.builder()
                .endpoint("http://127.0.0.1:1")
                .guildId("123456789012345678")
                .serverId("survival")
                .apiKey("test-secret-key")
                .reconnectDelayMs(60_000L)
                .maxReconnectDelayMs(240_000L)
                .heartbeatIntervalMs(30_000L)
                .heartbeatTimeoutMs(10_000L)
                .negotiationTimeoutMs(60_000L)
                .build();
    }

    /** A connected client with a platform health source that always has something to say. */
    private TunnelClient connectedClient() {
        client = TunnelClient.builder(logger, executors)
                .settings(settings())
                .socketFactory(sockets)
                .clock(clock)
                .wsScheduler(ws)
                .healthSource(new HealthSnapshotSource() {
                    @Override
                    public Payload snapshot() {
                        snapshotsTaken.incrementAndGet();
                        return Payload.builder().put("tps", 19.9).put("onlinePlayers", 3).build();
                    }
                })
                .build();
        client.connect();
        Await.until("the first socket to open", () -> client.isConnected());
        return client;
    }

    /** One heartbeat tick, well inside the timeout window so nothing reconnects behind the test. */
    private void tick(TunnelClient tunnel) {
        clock.advance(1_000L);
        tunnel.heartbeat().tick(tunnel.settings());
    }

    @Test
    @DisplayName("with nothing ever toggling it, health is reported — the pre-D69 behaviour")
    void healthReportingDefaultsToOn() {
        TunnelClient tunnel = connectedClient();
        FakeTunnelSocket socket = sockets.first();

        assertTrue(tunnel.isHealthReportingEnabled(),
                "the flag must default to on: a tunnel built without a module manager — offline, "
                        + "in v2-compat, or in any of the tunnel's own tests — has to behave exactly "
                        + "as it did before health became a module");

        tick(tunnel);

        assertEquals(1, socket.countFramesOfType("health"));
        assertEquals(1, socket.countFramesOfType("ping"));
    }

    @Test
    @DisplayName("disabled: health stops, and the ping does NOT — liveness is not a preference")
    void disablingHealthLeavesTheKeepaliveAlone() {
        TunnelClient tunnel = connectedClient();
        FakeTunnelSocket socket = sockets.first();

        tunnel.setHealthReportingEnabled(false);
        tick(tunnel);
        tick(tunnel);

        assertEquals(0, socket.countFramesOfType("health"),
                "an operator who switched the health module off must stop publishing TPS");
        assertEquals(2, socket.countFramesOfType("ping"),
                "…and must still answer the bot, or its 90-second sweep reaps the connection");
        assertEquals(0, snapshotsTaken.get(),
                "the platform must not even be asked — the point is that nothing is collected");
    }

    @Test
    @DisplayName("the bot's ping is still answered with a pong while health is off")
    void pongsContinueWhileHealthIsOff() {
        TunnelClient tunnel = connectedClient();
        FakeTunnelSocket socket = sockets.first();

        tunnel.setHealthReportingEnabled(false);
        socket.deliver(com.heimdall.core.json.Envelope.fresh("ping", Payload.empty()));

        Await.until("the pong to be written", () -> socket.countFramesOfType("pong") == 1);
        assertEquals(0, socket.countFramesOfType("health"));
    }

    @Test
    @DisplayName("re-enabling resumes on the NEXT TICK, on the same socket — no reconnect")
    void reEnablingResumesWithoutReconnecting() {
        TunnelClient tunnel = connectedClient();
        FakeTunnelSocket socket = sockets.first();

        tunnel.setHealthReportingEnabled(false);
        tick(tunnel);
        assertEquals(0, socket.countFramesOfType("health"));

        tunnel.setHealthReportingEnabled(true);
        tick(tunnel);

        assertEquals(1, socket.countFramesOfType("health"),
                "a dashboard toggle has to take effect on the next tick; waiting for a reconnect "
                        + "would mean up to a whole backoff ceiling of the wrong behaviour");
        assertEquals(1, sockets.createdCount(),
                "and it must do so WITHOUT dropping the link — the same socket throughout");
        assertTrue(tunnel.isConnected());
    }
}
