package com.heimdall.core.tunnel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.concurrent.HeimdallExecutors;
import com.heimdall.core.json.Payload;
import com.heimdall.core.log.RecordingLogger;
import com.heimdall.core.testing.Await;
import com.heimdall.core.testing.MutableClock;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The six v2 invariants, each with its own named test.
 *
 * <p>These are not general coverage. Every one of them corresponds to a production failure v2
 * shipped, and the comments in v2's {@code WebSocketClient} describing those failures are the
 * specification. A test here failing means the plugin has re-acquired an outage.
 *
 * <p>Everything is driven through {@link FakeTunnelSocket}: a real server cannot be made to
 * black-hole a connection, error and close simultaneously, or refuse the next four attempts and
 * then accept.
 */
class TunnelClientInvariantsTest {

    private final RecordingLogger logger = new RecordingLogger(true);
    private final MutableClock clock = new MutableClock();
    private final FakeTunnelSocketFactory sockets = new FakeTunnelSocketFactory();

    private HeimdallExecutors executors;
    private TunnelClient client;

    /** Backoff bounds far apart and long, so nothing reconnects behind a test's back. */
    private TunnelSettings settings(long reconnectDelayMs) {
        return TunnelSettings.builder()
                .endpoint("http://127.0.0.1:1")
                .guildId("123456789012345678")
                .serverId("survival")
                .apiKey("test-secret-key")
                .reconnectDelayMs(reconnectDelayMs)
                .maxReconnectDelayMs(reconnectDelayMs * 8)
                .heartbeatIntervalMs(30_000L)
                .heartbeatTimeoutMs(10_000L)
                .negotiationTimeoutMs(60_000L)
                .build();
    }

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

    private TunnelClient connectedClient(long reconnectDelayMs) {
        client = TunnelClient.builder(logger, executors)
                .settings(settings(reconnectDelayMs))
                .socketFactory(sockets)
                .clock(clock)
                .build();
        client.connect();
        Await.until("the first socket to open", () -> client.isConnected());
        return client;
    }

    // ── (a) abort, never close ───────────────────────────────────────────────

    @Test
    @DisplayName("(a) a heartbeat timeout ABORTS the socket — a graceful close would wedge forever")
    void forceReconnectAbortsRatherThanClosingGracefully() {
        // A long reconnect delay so the replacement socket never appears and confuses the assertion.
        TunnelClient tunnel = connectedClient(60_000L);
        FakeTunnelSocket dead = sockets.first();

        // Past the interval plus the timeout, with nothing arriving.
        clock.advance(45_000L);
        tunnel.heartbeat().tick(tunnel.settings());

        assertTrue(dead.wasAborted(),
                "abort() is the only teardown that does not wait on a close handshake; a peer that "
                        + "has been black-holed never completes one, and v2 wedged forever here");
        assertFalse(dead.wasClosedGracefully(),
                "a close frame asks the peer to reply — the peer is exactly what has stopped replying");
        assertFalse(tunnel.isConnected());
    }

    @Test
    @DisplayName("(a) a heartbeat tick well inside the window sends a ping instead of reconnecting")
    void heartbeatDoesNotReconnectWhileTrafficIsRecent() {
        TunnelClient tunnel = connectedClient(60_000L);
        FakeTunnelSocket socket = sockets.first();

        clock.advance(35_000L); // interval elapsed, but inside interval + timeout
        tunnel.heartbeat().tick(tunnel.settings());

        assertFalse(socket.wasAborted(), "35s of silence is inside the 30s + 10s allowance");
        assertEquals(1, socket.countFramesOfType("ping"));
    }

    // ── (b) one CAS, one reconnect ───────────────────────────────────────────

    @Test
    @DisplayName("(b) close + error + heartbeat timeout on one dead link schedule exactly ONE reconnect")
    void allReconnectTriggersCollapseIntoOneSchedule() {
        // Long enough that the scheduled reconnect cannot fire during the test — what is being
        // asserted is how many were SCHEDULED, not how many completed.
        TunnelClient tunnel = connectedClient(60_000L);
        FakeTunnelSocket dead = sockets.first();
        assertEquals(1, sockets.createdCount());

        // All three in-band triggers, for the same dead connection, as they really do arrive.
        dead.fireError(new IllegalStateException("connection reset"));
        dead.fireClose(1006, "abnormal closure");
        clock.advance(45_000L);
        tunnel.heartbeat().tick(tunnel.settings());
        dead.fireClose(1006, "abnormal closure again");

        assertTrue(tunnel.reconnectPolicy().isClaimed(),
                "the single-flight gate should be held by whichever trigger won");
        assertEquals(1, sockets.createdCount(),
                "four triggers must not each start their own doConnect chain — that is how a server "
                        + "ends up with several live sockets to the same bot, and commands run twice");

        // The sharp assertion, and the reason it is not just the socket count: with a long backoff
        // the extra reconnects would not have FIRED yet either way, so counting sockets alone
        // cannot tell one schedule from four. The backoff advances exactly once per schedule, so
        // the delay is a direct count of them — one doubling, not four.
        assertEquals(120_000L, tunnel.reconnectPolicy().peekDelayMs(),
                "exactly one reconnect was scheduled, so the backoff doubled exactly once; four "
                        + "would leave it at the 480s ceiling");
    }

    @Test
    @DisplayName("(b) the fourth trigger — a socket that cannot be created — also goes through the gate")
    void aFailureToCreateASocketAlsoSchedulesExactlyOneReconnect() {
        client = TunnelClient.builder(logger, executors)
                .settings(settings(60_000L))
                .socketFactory(sockets.throwOnCreate(true))
                .clock(clock)
                .build();

        client.connect();

        assertEquals(0, sockets.createdCount());
        assertTrue(client.reconnectPolicy().isClaimed(),
                "a create failure reaches scheduleReconnect by a different path from the callbacks, "
                        + "and must still be single-flighted");
    }

    // ── (c) exponential backoff ──────────────────────────────────────────────

    @Test
    @DisplayName("(c) backoff doubles up to the ceiling and resets on a successful open")
    void backoffDoublesAndResets() {
        ReconnectPolicy policy = new ReconnectPolicy(100L, 800L);

        assertEquals(100L, policy.nextDelayMs());
        assertEquals(200L, policy.nextDelayMs());
        assertEquals(400L, policy.nextDelayMs());
        assertEquals(800L, policy.nextDelayMs());
        assertEquals(800L, policy.nextDelayMs(), "the ceiling holds rather than overflowing past it");

        policy.reset();
        assertEquals(100L, policy.nextDelayMs(), "a working connection earns a fresh schedule");
    }

    @Test
    @DisplayName("(c) a real reconnect cycle doubles the delay, and a successful open resets it")
    void backoffIsDrivenByTheClientItself() {
        TunnelClient tunnel = connectedClient(20L);
        assertEquals(20L, tunnel.reconnectPolicy().peekDelayMs());

        sockets.failNextConnects(2);
        sockets.first().fireClose(1006, "dropped");

        // Two failed attempts, then one that opens.
        Await.until("three sockets to have been attempted", () -> sockets.createdCount() >= 3);
        Await.until("the tunnel to be back up", () -> tunnel.isConnected());
        assertEquals(20L, tunnel.reconnectPolicy().peekDelayMs(),
                "a successful open must clear the backoff, or a flaky link degrades permanently");
    }

    // ── (d) the factory is reused ────────────────────────────────────────────

    @Test
    @DisplayName("(d) reconnects reuse the one socket factory and leak nothing per attempt")
    void theSocketFactoryIsReusedAcrossReconnects() {
        TunnelClient tunnel = connectedClient(20L);

        for (int cycle = 0; cycle < 5; cycle++) {
            final int expected = cycle + 2;
            sockets.latest().fireClose(1006, "dropped");
            Await.until("reconnect " + expected, () -> sockets.createdCount() >= expected);
        }

        assertTrue(tunnel.isConnected());
        assertEquals(6, sockets.createdCount(),
                "exactly one socket per attempt — v2 built a whole new HTTP client each time, and "
                        + "each one carried a selector thread that never went away");
        assertNotSame(sockets.first(), sockets.latest());
    }

    // ── (e) three distinct teardowns ─────────────────────────────────────────

    @Test
    @DisplayName("(e) disconnect() closes gracefully, does NOT reconnect, and leaves the client reusable")
    void disconnectIsReusableAndDoesNotReconnect() {
        TunnelClient tunnel = connectedClient(20L);
        FakeTunnelSocket first = sockets.first();

        tunnel.disconnect();

        assertTrue(first.wasClosedGracefully(), "a live peer being switched off deserves a close frame");
        assertFalse(first.wasAborted());
        assertFalse(tunnel.isConnected());

        // The graceful close fires onClose exactly like an unexpected drop would. Without the
        // generation guard the client would treat it as one and immediately reconnect.
        first.fireClose(1000, "Heimdall tunnel disabled");
        assertEquals(1, sockets.createdCount(),
                "a deliberate disconnect must not reconnect itself");

        tunnel.connect();
        Await.until("the client to come back up after disconnect()", () -> tunnel.isConnected());
        assertEquals(2, sockets.createdCount());
    }

    @Test
    @DisplayName("(e) reconnect() rebuilds in place, resetting the backoff")
    void reconnectRebuildsInPlace() {
        TunnelClient tunnel = connectedClient(20L);
        FakeTunnelSocket first = sockets.first();

        tunnel.reconnect("987654321098765432");

        assertTrue(first.wasAborted(), "the old socket goes the same way a dead one does");
        Await.until("a replacement socket", () -> sockets.createdCount() >= 2);
        Await.until("the tunnel to be back up", () -> tunnel.isConnected());
        assertEquals(20L, tunnel.reconnectPolicy().peekDelayMs());
        assertTrue(sockets.latest().url().contains("987654321098765432"),
                "the new guild id has to reach the URL, or the reload did nothing");
    }

    @Test
    @DisplayName("(e) shutdown() latches, is idempotent, and never reconnects again")
    void shutdownLatches() {
        TunnelClient tunnel = connectedClient(20L);

        tunnel.shutdown();
        tunnel.shutdown();

        assertFalse(tunnel.isConnected());
        assertTrue(sockets.first().wasClosedGracefully());

        tunnel.connect();
        sockets.first().fireClose(1006, "late");
        assertEquals(1, sockets.createdCount(), "nothing brings a shut-down client back");
    }

    // ── (f) no pending future outlives its connection ────────────────────────

    @Test
    @DisplayName("(f) shutdown fails every outstanding request rather than leaving it to hang")
    void shutdownFailsPendingRequests() {
        TunnelClient tunnel = connectedClient(20L);

        CompletableFuture<Payload> request =
                tunnel.sendAndWait("get_players", Payload.empty(), 60_000L);
        assertFalse(request.isDone());
        assertEquals(1, tunnel.pendingRequests().size());

        tunnel.shutdown();

        assertTrue(request.isCompletedExceptionally());
        assertEquals(0, tunnel.pendingRequests().size());
        ExecutionException failure =
                assertThrows(ExecutionException.class, () -> request.get(1, TimeUnit.SECONDS));
        assertTrue(failure.getCause().getMessage().contains("shutting down"));
    }

    @Test
    @DisplayName("(f) a reconnect fails outstanding requests — their correlation ids died with the socket")
    void reconnectFailsPendingRequests() {
        TunnelClient tunnel = connectedClient(60_000L);

        CompletableFuture<Payload> request =
                tunnel.sendAndWait("run_command", Payload.empty(), 60_000L);
        sockets.first().fireClose(1006, "dropped");

        assertTrue(request.isCompletedExceptionally(),
                "the bot forgot this id when the socket died; waiting out the deadline for an answer "
                        + "that is already impossible is the hang this invariant exists to prevent");
    }

    @Test
    @DisplayName("(f) a request made while disconnected fails immediately")
    void sendAndWaitFailsFastWhenDisconnected() {
        client = TunnelClient.builder(logger, executors)
                .settings(settings(60_000L))
                .socketFactory(sockets)
                .clock(clock)
                .build();

        CompletableFuture<Payload> request = client.sendAndWait("get_players", Payload.empty(), 5_000L);

        assertTrue(request.isCompletedExceptionally());
        assertEquals(0, client.pendingRequests().size());
    }

    // ── (B2) a non-terminal error must not orphan a live socket ──────────────

    @Test
    @DisplayName("an error on a socket that is STILL OPEN aborts it rather than orphaning it")
    void aNonTerminalErrorAbortsTheLiveSocket() {
        // nv-websocket-client's onError is a catch-all that fires before every specific error
        // callback, including recoverable ones like onSendError - the terminal callback is
        // onDisconnected. So this is a real shape: an error arrives while the socket is open, with
        // its reading and writing threads alive. Detaching without aborting leaks exactly that,
        // and opens a second connection beside it.
        TunnelClient tunnel = connectedClient(60_000L);
        FakeTunnelSocket live = sockets.first();
        assertTrue(live.isOpen(), "the fixture must model a socket that is still up");

        live.fireError(new IllegalStateException("frame could not be sent"));

        assertTrue(live.wasAborted(),
                "an orphaned live socket keeps its reader and writer threads forever, and nothing "
                        + "holds a reference to close it");
        assertFalse(live.isOpen());
        assertFalse(tunnel.isConnected());
        assertEquals(120_000L, tunnel.reconnectPolicy().peekDelayMs(),
                "and exactly one replacement is scheduled, not one per error");
    }

    @Test
    @DisplayName("the terminal close that follows an abort does not schedule a second reconnect")
    void anAbortsOwnCloseCallbackIsCollapsed() {
        TunnelClient tunnel = connectedClient(60_000L);
        FakeTunnelSocket live = sockets.first();

        live.fireError(new IllegalStateException("frame could not be sent"));
        // nv follows the abort with onDisconnected for the same socket.
        live.fireClose(1001, "Heimdall aborting a dead connection");

        assertEquals(120_000L, tunnel.reconnectPolicy().peekDelayMs(),
                "the error and the close it caused are one failure, not two");
        assertEquals(1, sockets.createdCount());
    }

    // ── (S1) a manual connect must disarm the backoff ──────────────────────

    @Test
    @DisplayName("(S1) connect() during a backoff cancels the armed reconnect instead of racing it")
    void connectDisarmsAPendingReconnect() {
        TunnelClient tunnel = connectedClient(60_000L);
        sockets.first().fireClose(1006, "dropped");
        assertTrue(tunnel.hasArmedReconnect(), "a reconnect should be armed and waiting");

        tunnel.connect();

        assertFalse(tunnel.hasArmedReconnect(),
                "leaving it armed means it fires later and opens a SECOND socket beside the one "
                        + "connect() just made - and the gate is already released, so nothing "
                        + "downstream collapses them");
        assertEquals(2, sockets.createdCount());
    }

    @Test
    @DisplayName("(S1) reconnect() during a backoff does the same - the likeliest moment for a reload")
    void reconnectDisarmsAPendingReconnect() {
        TunnelClient tunnel = connectedClient(60_000L);
        sockets.first().fireClose(1006, "dropped");
        assertTrue(tunnel.hasArmedReconnect());

        tunnel.reconnect(null);

        assertEquals(2, sockets.createdCount(),
                "one replacement, from the reload - not one from the reload and one from the "
                        + "backoff that was already ticking");
    }

    // ── (S2) a socket that fails mid-start is not left installed ─────────────

    @Test
    @DisplayName("(S2) a socket whose connect() throws is aborted, not silently overwritten")
    void aSocketThatFailsToStartIsAborted() {
        client = TunnelClient.builder(logger, executors)
                .settings(settings(60_000L))
                .socketFactory(sockets.throwOnConnect(true))
                .clock(clock)
                .build();

        client.connect();

        FakeTunnelSocket halfStarted = sockets.first();
        assertNotNull(halfStarted, "the socket was created before connect() threw");
        assertTrue(halfStarted.wasAborted(),
                "publishing the socket before connect() is deliberate - onOpen must not fire before "
                        + "the field is set - but it means a throw leaves a half-started socket "
                        + "installed that the next attempt would just overwrite");
        assertFalse(client.isConnected());
        assertTrue(client.reconnectPolicy().isClaimed());
    }

    // ── Stale callbacks ──────────────────────────────────────────────────────

    @Test
    @DisplayName("a late callback from a superseded socket cannot kill the connection that replaced it")
    void staleCallbacksAreIgnored() {
        TunnelClient tunnel = connectedClient(20L);
        FakeTunnelSocket first = sockets.first();

        first.fireClose(1006, "dropped");
        Await.until("a replacement socket", () -> sockets.createdCount() >= 2);
        Await.until("the tunnel to be back up", () -> tunnel.isConnected());
        FakeTunnelSocket second = sockets.latest();
        assertNotSame(first, second);

        // The old socket's stack finally gets round to reporting what happened to it.
        first.fireClose(1006, "dropped, again, minutes later");
        first.fireError(new IllegalStateException("and an error too"));

        assertTrue(tunnel.isConnected(), "the healthy replacement must survive its predecessor's ghost");
        assertEquals(2, sockets.createdCount());
        assertNotNull(second);
        assertSame(second, sockets.latest());
    }
}
