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
import java.util.Collections;
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

    private final CapturingScheduler ws = new CapturingScheduler();

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
                .wsScheduler(ws)
                .build();
        client.connect();
        Await.until("the first socket to open", () -> client.isConnected());
        return client;
    }

    /**
     * A client whose heartbeat, backoff and negotiation deadline the test drives by hand.
     *
     * <p>Nothing fires until {@link CapturingScheduler#runPending()} is called, so every assertion
     * below is about what the client <em>decided</em> rather than about what happened to have
     * elapsed. The fake socket opens synchronously inside {@code connect()}, so there is nothing to
     * wait for either.
     */
    private TunnelClient manualClient() {
        client = TunnelClient.builder(logger, executors)
                .settings(settings(100L))
                .socketFactory(sockets)
                .clock(clock)
                .wsScheduler(ws)
                .build();
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
    @DisplayName("(b) all four reconnect triggers on one dead link produce exactly ONE new connection")
    void allReconnectTriggersCollapseIntoOneSchedule() {
        // Asserted by counting CONNECTION ATTEMPTS, driving the scheduler by hand. The earlier
        // version inferred the count from the backoff delay, which quietly depended on the ratio
        // between the base delay and the ceiling — make it *2 instead of *8 and the assertion
        // becomes unfalsifiable — and would have died the first time anyone added jitter. Attempts
        // are what the v2 outage was actually about: several live sockets to the same bot, so
        // every command ran twice.
        TunnelClient tunnel = manualClient();
        tunnel.connect();
        FakeTunnelSocket dead = sockets.first();
        assertEquals(1, sockets.createdCount());
        assertTrue(tunnel.isConnected());

        // The heartbeat timeout goes FIRST, while the link is still up. It reaches the gate through
        // forceReconnect, which is a genuinely different path from the callbacks — and once any of
        // the others has run, the socket is already detached and tick() returns early, so a
        // heartbeat trigger placed after them is inert and tests nothing.
        clock.advance(45_000L);
        tunnel.heartbeat().tick(tunnel.settings());
        assertTrue(dead.wasAborted(), "the heartbeat trigger must actually have fired");

        dead.fireError(new IllegalStateException("connection reset"));
        dead.fireClose(1006, "abnormal closure");
        dead.fireClose(1006, "abnormal closure again");

        ws.runPending();

        assertEquals(2, sockets.createdCount(),
                "one replacement, not one per trigger — four doConnect chains is how a server ends "
                        + "up with several live sockets to the same bot");
        assertTrue(tunnel.isConnected());
    }

    @Test
    @DisplayName("(b) the fourth trigger — a socket that cannot be created — also goes through the gate")
    void aFailureToCreateASocketAlsoSchedulesExactlyOneReconnect() {
        client = TunnelClient.builder(logger, executors)
                .settings(settings(100L))
                .socketFactory(sockets.throwOnCreate(true))
                .clock(clock)
                .wsScheduler(ws)
                .build();

        client.connect();

        assertEquals(0, sockets.createdCount());
        assertEquals(1, ws.delaysMs().size(),
                "a create failure reaches scheduleReconnect by a different path from the callbacks, "
                        + "and must still schedule exactly one retry");
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
        // The delays are read off the scheduler the client actually handed them to, so this asserts
        // what was scheduled rather than what an accessor reported.
        TunnelClient tunnel = manualClient();
        tunnel.connect();
        sockets.failNextConnects(2);

        ws.clearCaptured();
        sockets.first().fireClose(1006, "dropped");
        assertEquals(Collections.singletonList(Long.valueOf(100L)), ws.delaysMs(),
                "the first retry waits the base delay, not twice it");

        ws.runPending();
        assertEquals(2, sockets.createdCount());
        assertEquals(Collections.singletonList(Long.valueOf(200L)), ws.delaysMs(),
                "the attempt failed, so the next one waits twice as long");

        ws.runPending();
        assertEquals(3, sockets.createdCount());
        assertEquals(Collections.singletonList(Long.valueOf(400L)), ws.delaysMs());

        ws.runPending();
        assertTrue(tunnel.isConnected(), "the fourth attempt opens");

        ws.clearCaptured();
        sockets.latest().fireClose(1006, "dropped again");
        assertEquals(Collections.singletonList(Long.valueOf(100L)), ws.delaysMs(),
                "a successful open must clear the backoff, or a flaky link degrades permanently");
    }

    // ── (d) the factory is reused ────────────────────────────────────────────

    @Test
    @DisplayName("(d) each reconnect creates exactly one socket, and abandons the previous one")
    void eachReconnectCreatesExactlyOneSocket() {
        // Factory reuse itself is true by construction — the client holds one final reference — so
        // asserting it proves nothing. What can regress, and what the v2 leak actually looked like
        // from outside, is the count: one socket per attempt, with the previous one closed rather
        // than left holding its threads.
        TunnelClient tunnel = manualClient();
        tunnel.connect();

        for (int cycle = 0; cycle < 5; cycle++) {
            FakeTunnelSocket previous = sockets.latest();
            previous.fireClose(1006, "dropped");
            ws.runPending();
            assertEquals(cycle + 2, sockets.createdCount(),
                    "cycle " + cycle + " should have produced exactly one new socket");
            assertFalse(previous.isOpen(), "and the one it replaced must not still be open");
        }

        assertTrue(tunnel.isConnected());
        assertNotSame(sockets.first(), sockets.latest());
    }

    // ── (e) three distinct teardowns ─────────────────────────────────────────

    @Test
    @DisplayName("(e) disconnect() closes gracefully, does NOT reconnect, and leaves the client reusable")
    void disconnectIsReusableAndDoesNotReconnect() {
        TunnelClient tunnel = manualClient();
        tunnel.connect();
        FakeTunnelSocket first = sockets.first();

        tunnel.disconnect();

        assertTrue(first.wasClosedGracefully(), "a live peer being switched off deserves a close frame");
        assertFalse(first.wasAborted());
        assertFalse(tunnel.isConnected());

        // The graceful close fires onClose exactly like an unexpected drop would. Without the
        // generation guard the client would treat it as one and reconnect. Draining the scheduler
        // is what makes this conclusive: any reconnect that HAD been scheduled runs here, rather
        // than the assertion merely arriving before it could.
        first.fireClose(1000, "Heimdall tunnel disabled");
        ws.runPending();
        assertEquals(1, sockets.createdCount(),
                "a deliberate disconnect must not reconnect itself");

        tunnel.connect();
        assertTrue(tunnel.isConnected(), "and the client is still usable afterwards");
        assertEquals(2, sockets.createdCount());
    }

    @Test
    @DisplayName("(e) reconnect() rebuilds in place, resetting the backoff")
    void reconnectRebuildsInPlace() {
        TunnelClient tunnel = manualClient();
        tunnel.connect();
        FakeTunnelSocket first = sockets.first();
        ws.clearCaptured();

        tunnel.reconnect("987654321098765432");

        assertTrue(first.wasAborted(), "the old socket goes the same way a dead one does");
        assertEquals(Collections.singletonList(Long.valueOf(100L)), ws.delaysMs(),
                "an operator who just fixed the endpoint should not wait out a backoff that grew "
                        + "while it was wrong");

        ws.runPending();
        assertTrue(tunnel.isConnected());
        assertTrue(sockets.latest().url().contains("987654321098765432"),
                "the new guild id has to reach the URL, or the reload did nothing");
    }

    @Test
    @DisplayName("(e) shutdown() latches, is idempotent, and never reconnects again")
    void shutdownLatches() {
        TunnelClient tunnel = manualClient();
        tunnel.connect();

        tunnel.shutdown();
        tunnel.shutdown();

        assertFalse(tunnel.isConnected());
        assertTrue(sockets.first().wasClosedGracefully());

        tunnel.connect();
        sockets.first().fireClose(1006, "late");
        ws.runPending();
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
        ws.runPending();
        assertEquals(2, sockets.createdCount(),
                "and exactly one replacement is made, not one per error");
    }

    @Test
    @DisplayName("the terminal close that follows an abort does not schedule a second reconnect")
    void anAbortsOwnCloseCallbackIsCollapsed() {
        TunnelClient tunnel = connectedClient(60_000L);
        FakeTunnelSocket live = sockets.first();

        live.fireError(new IllegalStateException("frame could not be sent"));
        // nv follows the abort with onDisconnected for the same socket.
        live.fireClose(1001, "Heimdall aborting a dead connection");

        ws.runPending();
        assertEquals(2, sockets.createdCount(),
                "the error and the close it caused are one failure, not two");
    }

    // ── (S1) a manual connect must disarm the backoff ──────────────────────

    @Test
    @DisplayName("(S1) connect() during a backoff cancels the armed reconnect instead of racing it")
    void connectDisarmsAPendingReconnect() {
        TunnelClient tunnel = manualClient();
        tunnel.connect();
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
        TunnelClient tunnel = manualClient();
        tunnel.connect();
        sockets.first().fireClose(1006, "dropped");
        assertTrue(tunnel.hasArmedReconnect());

        tunnel.reconnect(null);

        ws.runPending();
        assertEquals(2, sockets.createdCount(),
                "one replacement, from the reload - not one from the reload and one from the "
                        + "backoff that was already ticking");
    }

    // ── (S2) a socket that fails mid-start is not left installed ─────────────

    @Test
    @DisplayName("(S2) a socket whose connect() throws is aborted, not silently overwritten")
    void aSocketThatFailsToStartIsAborted() {
        client = TunnelClient.builder(logger, executors)
                .settings(settings(100L))
                .socketFactory(sockets.throwOnConnect(true))
                .clock(clock)
                .wsScheduler(ws)
                .build();

        client.connect();

        FakeTunnelSocket halfStarted = sockets.first();
        assertNotNull(halfStarted, "the socket was created before connect() threw");
        assertTrue(halfStarted.wasAborted(),
                "publishing the socket before connect() is deliberate - onOpen must not fire before "
                        + "the field is set - but it means a throw leaves a half-started socket "
                        + "installed that the next attempt would just overwrite");
        assertFalse(client.isConnected());
        assertEquals(1, ws.delaysMs().size(), "and a retry is scheduled");
    }

    // ── Stale callbacks ──────────────────────────────────────────────────────

    @Test
    @DisplayName("a late callback from a superseded socket cannot kill the connection that replaced it")
    void staleCallbacksAreIgnored() {
        TunnelClient tunnel = manualClient();
        tunnel.connect();
        FakeTunnelSocket first = sockets.first();

        first.fireClose(1006, "dropped");
        ws.runPending();
        assertTrue(tunnel.isConnected());
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
