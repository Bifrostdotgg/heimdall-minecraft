package com.heimdall.core.tunnel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
    @DisplayName("a bot ping is answered with a pong written on the DELIVERING thread")
    void pingIsAnsweredImmediately() throws Exception {
        // Delivered from a thread of our own with a name nothing else uses, so "the pong left on
        // the thread that delivered the ping" is a real assertion. Reading Thread.currentThread()
        // in the test proves nothing: it is the delivering thread either way, so the test would
        // stay green if the pong were moved onto heimdall-io — which is the regression it exists
        // to catch, because a pong queued behind module handlers on a busy pool is how a healthy
        // server gets reaped by the bot's sweep.
        Thread deliverer = new Thread(
                () -> socket.deliver(Envelope.of("ping-1", "ping", Payload.empty())),
                "pretend-socket-reader");
        deliverer.start();
        deliverer.join(5_000L);

        // Awaited rather than read straight away, so that PRESENCE is never what fails: the
        // property under test is which thread wrote it, and a pong that merely arrived late should
        // fail on the thread assertion below with a message that says so.
        Await.until("a pong to be written", () -> socket.firstFrameOfType("pong") != null);
        Envelope pong = socket.firstFrameOfType("pong");
        assertNotNull(pong, "this is the ONLY keepalive the protocol has — an unanswered ping is a "
                + "connection the bot's sweep will close");
        assertEquals("ping-1", pong.id(), "correlation is by echoed id");
        assertEquals("pretend-socket-reader", socket.threadThatSentFirst("pong"),
                "the pong must be written synchronously on the reading thread, not handed to an "
                        + "executor");
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

        String deliveringThread = Thread.currentThread().getName();
        socket.deliver(Envelope.fresh("role_sync",
                Payload.builder().put("username", "Steve").build()));

        Await.until("the handler to run", () -> received.get() != null);
        assertEquals("Steve", received.get().payload().string("username", null));
        assertNotEquals(deliveringThread, handlerThread.get(),
                "a handler that hits the API must not be holding the socket's reading thread — v2 "
                        + "did exactly that and its own heartbeat then aborted the connection. The "
                        + "claim is that it ran somewhere ELSE, not that the pool is called "
                        + "anything in particular");
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
    @DisplayName("a notification carrying an id the plugin never issued is dispatched, not mistaken for a reply")
    void aNotificationWithAnUnknownIdReachesItsSubscriber() {
        // The shape of the bot's whitelist_changed push: a real nanoid, an empty payload, and no
        // reply wanted. The id is there because v2's client dropped any frame without one, so the
        // bot puts one on every frame whether or not anybody is correlating on it.
        //
        // That matters here because route() consults the pending map BEFORE the subscription
        // registry, so a notification's id takes the correlation path first. An id the plugin never
        // issued has to miss it silently and fall through — not consume the frame, and not report an
        // unmatched reply.
        AtomicReference<Envelope> seen = new AtomicReference<Envelope>();
        client.subscribe("whitelist_changed", seen::set);
        // The identify frame is already on the wire from setUp, so the assertion is that nothing
        // NEW goes out rather than that nothing ever has.
        int before = socket.sentFrames().size();

        socket.deliver(Envelope.of("V1StGXR8_Z5jdHi6B-myT", "whitelist_changed", Payload.empty()));

        Await.until("the notification handler to run", () -> seen.get() != null);
        assertEquals("V1StGXR8_Z5jdHi6B-myT", seen.get().id());
        assertTrue(seen.get().payload().isEmpty());
        assertEquals(before, socket.sentFrames().size(),
                "a notification is answered with nothing at all — the bot has no future waiting, so "
                        + "a reply would be filed as unsolicited and is pure noise");
        assertTrue(logger.at(com.heimdall.core.log.LogLevel.WARN).isEmpty(),
                "an id nobody is waiting on is the normal shape of a notification, not a fault");
    }

    @Test
    @DisplayName("a notification nobody subscribed to is written off, still without a reply")
    void anUnsubscribedNotificationIsSilent() {
        // The module-disabled case at the dispatcher level: with the whitelist module off nothing is
        // subscribed, and the frame has to go quiet rather than produce an error reply the bot would
        // have nowhere to put.
        int before = socket.sentFrames().size();

        socket.deliver(Envelope.of("V1StGXR8_Z5jdHi6B-myT", "whitelist_changed", Payload.empty()));

        assertTrue(client.isConnected());
        assertEquals(before, socket.sentFrames().size());
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
        assertTrue(client.isConnected(), "a throwing handler must not tear the connection down");
        assertFalse(socket.wasAborted(), "nor abort the socket");
        assertEquals(1, sockets.createdCount(), "nor trigger a reconnect");
    }
}
