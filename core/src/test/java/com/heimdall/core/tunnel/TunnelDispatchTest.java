package com.heimdall.core.tunnel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.concurrent.HeimdallExecutors;
import com.heimdall.core.json.Envelope;
import com.heimdall.core.json.Payload;
import com.heimdall.core.log.RecordingLogger;
import com.heimdall.core.testing.Await;
import com.heimdall.core.testing.MutableClock;
import com.heimdall.core.util.Registration;
import java.util.concurrent.CompletableFuture;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Inbound routing: the order, the executor boundary, and what happens to a frame nobody wants. */
class TunnelDispatchTest {

    private final RecordingLogger logger = new RecordingLogger(true);
    private final MutableClock clock = new MutableClock();
    private final FakeTunnelSocketFactory sockets = new FakeTunnelSocketFactory();

    private HeimdallExecutors executors;
    private TunnelClient client;
    private FakeTunnelSocket socket;

    @BeforeEach
    void setUp() {
        executors = new HeimdallExecutors(logger, 2);
        client = TunnelClient.builder(logger, executors)
                .settings(TunnelSettings.builder()
                        .endpoint("http://127.0.0.1:1")
                        .guildId("123456789012345678")
                        .serverId("survival")
                        .apiKey("test-secret-key")
                        .reconnectDelayMs(60_000L)
                        .heartbeatIntervalMs(60_000L)
                        .negotiationTimeoutMs(60_000L)
                        .build())
                .socketFactory(sockets)
                .clock(clock)
                .build();
        client.connect();
        Await.until("the socket to open", () -> client.isConnected());
        socket = sockets.first();
    }

    @AfterEach
    void tearDown() {
        client.shutdown();
        executors.shutdown(1000);
    }

    @Test
    @DisplayName("a bot ping is answered with a pong echoing the same id, on the socket thread")
    void pingIsAnsweredImmediately() {
        Thread caller = Thread.currentThread();
        socket.deliver(Envelope.of("ping-1", "ping", Payload.empty()));

        Envelope pong = socket.firstFrameOfType("pong");
        assertNotNull(pong, "this is the ONLY keepalive the protocol has — an unanswered ping is a "
                + "connection the bot's sweep will close");
        assertEquals("ping-1", pong.id(), "correlation is by echoed id");
        assertEquals(caller.getName(), Thread.currentThread().getName(),
                "the pong is written synchronously; queueing it behind a module handler on a busy "
                        + "IO pool is how a healthy server gets reaped");
    }

    @Test
    @DisplayName("a pong is consumed silently")
    void pongProducesNoReply() {
        socket.deliver(Envelope.of("x", "pong", Payload.empty()));
        assertEquals(0, socket.countFramesOfType("pong"));
    }

    @Test
    @DisplayName("a reply completes the waiting future and is NOT also delivered to subscribers")
    void correlatedRepliesShortCircuitSubscriptions() throws Exception {
        CopyOnWriteArrayList<Envelope> seenBySubscriber = new CopyOnWriteArrayList<Envelope>();
        client.subscribe("player_list", seenBySubscriber::add);

        CompletableFuture<Payload> request = client.sendAndWait("get_players", Payload.empty(), 5_000L);
        Envelope sent = socket.firstFrameOfType("get_players");
        assertNotNull(sent);

        socket.deliver(Envelope.of(sent.id(), "player_list",
                Payload.builder().putStrings("players", java.util.Arrays.asList("Steve")).build()));

        Payload reply = request.get();
        assertEquals(java.util.Arrays.asList("Steve"), reply.strings("players"));
        assertTrue(seenBySubscriber.isEmpty(),
                "delivering a reply to both its future and a subscriber of the same type means the "
                        + "same event is processed twice");
    }

    @Test
    @DisplayName("an unsolicited message reaches subscribers, on their executor and never on the socket thread")
    void subscribersRunOffTheSocketThread() {
        AtomicReference<String> handlerThread = new AtomicReference<String>();
        AtomicReference<Envelope> received = new AtomicReference<Envelope>();
        client.subscribe("role_sync", envelope -> {
            handlerThread.set(Thread.currentThread().getName());
            received.set(envelope);
        });

        socket.deliver(Envelope.fresh("role_sync",
                Payload.builder().put("username", "Steve").build()));

        Await.until("the handler to run", () -> received.get() != null);
        assertEquals("Steve", received.get().payload().string("username", null));
        assertTrue(handlerThread.get().startsWith("heimdall-io"),
                "a handler that hits the API must not be holding the socket's reading thread — v2 "
                        + "did exactly that and its own heartbeat then aborted the connection");
    }

    @Test
    @DisplayName("a subscriber can name its own executor")
    void subscribersCanNameAnExecutor() {
        ExecutorService named = Executors.newSingleThreadExecutor(
                runnable -> new Thread(runnable, "pretend-main-thread"));
        try {
            AtomicReference<String> handlerThread = new AtomicReference<String>();
            client.subscribe("role_sync", envelope -> handlerThread.set(Thread.currentThread().getName()),
                    (Executor) named);

            socket.deliver(Envelope.fresh("role_sync", Payload.empty()));

            Await.until("the handler to run", () -> handlerThread.get() != null);
            assertEquals("pretend-main-thread", handlerThread.get());
        } finally {
            named.shutdownNow();
        }
    }

    @Test
    @DisplayName("closing a registration really unsubscribes it, leaving its siblings alone")
    void registrationsUnsubscribeIndependently() {
        CopyOnWriteArrayList<String> hits = new CopyOnWriteArrayList<String>();
        Registration first = client.subscribe("role_sync", envelope -> hits.add("first"));
        client.subscribe("role_sync", envelope -> hits.add("second"));

        first.close();
        first.close(); // idempotent

        socket.deliver(Envelope.fresh("role_sync", Payload.empty()));

        Await.until("the surviving handler to run", () -> hits.contains("second"));
        assertFalse(hits.contains("first"));
    }

    @Test
    @DisplayName("(S3) a handler already queued when its registration closed does NOT run")
    void aClosedRegistrationCancelsWorkAlreadyQueued() {
        // Dispatch takes a copy-on-write snapshot and then hands each handler to an executor, so
        // there is a window in which a registration is closed while its task is already in flight.
        // Holding the task here reproduces that window exactly, rather than hoping to hit it.
        final List<Runnable> queued = new CopyOnWriteArrayList<Runnable>();
        final AtomicReference<String> ran = new AtomicReference<String>();

        Registration registration = client.subscribe(
                "role_sync", envelope -> ran.set("handler ran"), (Executor) queued::add);

        socket.deliver(Envelope.fresh("role_sync", Payload.empty()));
        assertEquals(1, queued.size(), "the task should be waiting on the executor");

        registration.close();
        queued.get(0).run();

        assertNull(ran.get(),
                "a disabled module still reacting to events is exactly what the registration design "
                        + "exists to prevent, and removal from the list only affects future "
                        + "dispatches");
    }

    @Test
    @DisplayName("a still-open registration's queued handler runs normally")
    void anOpenRegistrationStillRuns() {
        final List<Runnable> queued = new CopyOnWriteArrayList<Runnable>();
        final AtomicReference<String> ran = new AtomicReference<String>();
        client.subscribe("role_sync", envelope -> ran.set("handler ran"), (Executor) queued::add);

        socket.deliver(Envelope.fresh("role_sync", Payload.empty()));
        queued.get(0).run();

        assertEquals("handler ran", ran.get());
    }

    @Test
    @DisplayName("a message nothing subscribed to goes to the fallback hook")
    void unhandledMessagesReachTheHook() {
        AtomicReference<Envelope> seen = new AtomicReference<Envelope>();
        client.setUnhandledHandler(seen::set);

        socket.deliver(Envelope.fresh("some_future_message", Payload.empty()));

        Await.until("the fallback hook to run", () -> seen.get() != null);
        assertEquals("some_future_message", seen.get().type());
    }

    @Test
    @DisplayName("a malformed frame is dropped without disturbing the connection")
    void malformedFramesAreIgnored() {
        socket.deliverRaw("this is not json");
        socket.deliverRaw("{\"id\":\"\",\"type\":\"ping\"}");
        socket.deliverRaw("[]");

        assertTrue(client.isConnected());
        assertEquals(0, socket.countFramesOfType("pong"),
                "an empty id is falsy on the bot's side too, so this is not a ping to answer");
    }

    @Test
    @DisplayName("every parseable inbound frame refreshes the liveness clock, not just pings")
    void anyInboundTrafficCountsAsLiveness() {
        clock.advance(20_000L);
        assertTrue(client.millisSinceLastInbound() >= 20_000L);

        socket.deliver(Envelope.fresh("role_sync", Payload.empty()));

        assertEquals(0L, client.millisSinceLastInbound(),
                "a link delivering role syncs is demonstrably alive; v2 only counted pings and could "
                        + "abort a busy connection because the bot was too busy to sweep");
    }

    @Test
    @DisplayName("a handler that throws does not stop the others or the tunnel")
    void aThrowingHandlerIsContained() {
        CopyOnWriteArrayList<String> hits = new CopyOnWriteArrayList<String>();
        client.subscribe("role_sync", envelope -> {
            throw new IllegalStateException("bad handler");
        });
        client.subscribe("role_sync", envelope -> hits.add("survivor"));

        socket.deliver(Envelope.fresh("role_sync", Payload.empty()));

        Await.until("the well-behaved handler to run", () -> hits.contains("survivor"));
        assertTrue(client.isConnected());
        assertNull(socket.firstFrameOfType("nonsense"));
    }
}
