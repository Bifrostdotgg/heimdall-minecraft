package com.heimdall.core.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.config.ServerRole;
import com.heimdall.core.json.Envelope;
import com.heimdall.core.json.Payload;
import com.heimdall.core.log.RecordingLogger;
import com.heimdall.core.platform.PlayerHandle;
import com.heimdall.core.testing.FakePlatform;
import com.heimdall.core.testing.FakePlayer;
import com.heimdall.core.testing.RecordingTunnelBus;
import com.heimdall.core.util.Registration;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The three dashboard requests, and the one property that matters more than any payload field: that
 * every one of them is answered.
 *
 * <p>Every test here drives a real {@link Envelope} through the subscription and asserts on what came
 * back through {@link RecordingTunnelBus} — the type, the echoed id, and the payload. That is the
 * whole wire contract, because the bot forwards the reply to the dashboard untouched.
 *
 * <p>The executor is inline, so no test needs a latch. That the real handlers run on
 * {@code heimdall-io} rather than the socket's reading thread is a property of how {@code install}
 * subscribes, pinned by {@link #handlersAreSubscribedOnTheGivenExecutor()} rather than re-proved by
 * every case.
 */
class RemoteRequestWiringTest {

    private static final Executor INLINE = new Executor() {
        @Override
        public void execute(Runnable command) {
            command.run();
        }
    };

    private final RecordingLogger logger = new RecordingLogger(true);
    private final RecordingTunnelBus tunnel = new RecordingTunnelBus();

    @TempDir
    Path dataDir;

    private FakePlatform platform(ServerRole role) {
        return new FakePlatform(role, dataDir);
    }

    /**
     * A real single-threaded scheduler, so the probe deadline is armed the way production arms it.
     *
     * <p>Named, so a thread assertion reads as the thing it is checking.
     */
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            runnable -> {
                Thread thread = new Thread(runnable, "pretend-heimdall-sched");
                thread.setDaemon(true);
                return thread;
            });

    @AfterEach
    void stopScheduler() {
        scheduler.shutdownNow();
    }

    private Registration install(FakePlatform platform) {
        return RemoteRequestWiring.install(logger, platform, tunnel, INLINE, scheduler);
    }

    /** Waits for a condition rather than sleeping a fixed time, so a loaded runner is not flaky. */
    private static void await(String what, java.util.function.BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000L;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(5L);
        }
    }

    /** The one reply of a type, or a failure that says what actually came back. */
    private RecordingTunnelBus.Sent onlyReply(String type) {
        List<RecordingTunnelBus.Sent> replies = tunnel.sent(type);
        assertEquals(1, replies.size(), "expected exactly one '" + type + "' reply, got " + tunnel.sent());
        return replies.get(0);
    }

    // ── Registration ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("all three request types are subscribed, and unsubscribed together")
    void allThreeAreSubscribedAndUnwound() {
        Registration handle = install(platform(ServerRole.STANDALONE));

        // The falsification this whole class exists for: with any one of these registrations
        // removed, the matching frame reaches nothing and the dashboard sees a 504 rather than an
        // error it can render.
        assertEquals(1, tunnel.subscriberCount("get_players"));
        assertEquals(1, tunnel.subscriberCount("run_command"));
        assertEquals(1, tunnel.subscriberCount("probe_player"));

        handle.close();

        assertEquals(0, tunnel.subscriberCount("get_players"));
        assertEquals(0, tunnel.subscriberCount("run_command"));
        assertEquals(0, tunnel.subscriberCount("probe_player"));
    }

    @Test
    @DisplayName("nothing is subscribed for a type these handlers do not own")
    void unrelatedTypesAreLeftAlone() {
        install(platform(ServerRole.STANDALONE));

        // Installing these must not change what happens to anything else. An unknown type still
        // reaches no handler, which is what the dispatcher writes off with a debug line.
        assertEquals(0, tunnel.push("something_else", Payload.empty()),
                "these handlers must not become a catch-all for types nobody owns");
    }

    @Test
    @DisplayName("every handler names an executor rather than taking the reading thread")
    void handlersAreSubscribedOnTheGivenExecutor() {
        RemoteRequestWiring.install(
                logger, platform(ServerRole.STANDALONE), tunnel, INLINE, scheduler);

        // The two-argument subscribe() would also land on heimdall-io, so this is not about which
        // pool; it is that install() decides at all. A handler left on the socket's reading thread
        // stops the tunnel reading, and the bot's liveness sweep reaps a link that is working
        // (departure D27) — get_players walks the whole online list, run_command waits on the
        // server, and the probe waits on another plugin.
        assertSame(INLINE, tunnel.subscribedExecutor("get_players"));
        assertSame(INLINE, tunnel.subscribedExecutor("run_command"));
        assertSame(INLINE, tunnel.subscribedExecutor("probe_player"));
    }

    @Test
    @DisplayName("the command reply is routed through the named executor, not the completing thread")
    void theCommandReplyDoesNotRideTheCompletingThread() throws Exception {
        final List<String> ranOn = new CopyOnWriteArrayList<String>();
        Executor named = new Executor() {
            @Override
            public void execute(Runnable command) {
                ranOn.add(Thread.currentThread().getName());
                command.run();
            }
        };
        CompletableFuture<String> pending = new CompletableFuture<String>();
        FakePlatform platform = platform(ServerRole.STANDALONE).dispatchAnswering(pending);
        RemoteRequestWiring.install(logger, platform, tunnel, named, scheduler);

        tunnel.push(Envelope.of("req-async", "run_command",
                Payload.builder().put("command", "say hi").build()));
        assertEquals(0, tunnel.sent("command_result").size(),
                "nothing to reply with yet — the server has not finished with the command");

        // A thread that stands in for Bukkit's main thread: this is where the real bridge completes,
        // and replying from here would put a socket write on the tick loop.
        Thread completer = new Thread(new Runnable() {
            @Override
            public void run() {
                pending.complete("dispatched: say hi");
            }
        }, "pretend-main-thread");
        ranOn.clear();
        completer.start();
        completer.join(5000L);

        RecordingTunnelBus.Sent reply = onlyReply("command_result");
        assertEquals("dispatched: say hi", reply.payload().string("output", ""));
        assertEquals(java.util.Collections.singletonList("pretend-main-thread"), ranOn,
                "the continuation has to be handed to the named executor rather than run inline on "
                        + "whichever thread completed the dispatch");
    }

    // ── get_players ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("the roster carries uuid and username, plus whatever the platform adds")
    void theRosterCarriesTheV2Shape() {
        FakePlatform platform = platform(ServerRole.STANDALONE);
        final FakePlayer steve = FakePlayer.named("Steve");
        platform.join(steve);
        platform.describingPlayers(new Function<PlayerHandle, Payload>() {
            @Override
            public Payload apply(PlayerHandle player) {
                return Payload.builder().put("ip", "203.0.113.7").build();
            }
        });
        install(platform);

        tunnel.push(Envelope.of("req-1", "get_players", Payload.empty()));

        RecordingTunnelBus.Sent reply = onlyReply("player_list");
        assertEquals("req-1", reply.requestId(),
                "the echoed id IS the correlation — a fresh one leaves the bot's future waiting");
        List<Payload> players = reply.payload().children("players");
        assertEquals(1, players.size());
        assertEquals(steve.uuid().toString(), players.get(0).string("uuid", ""));
        assertEquals("Steve", players.get(0).string("username", ""));
        assertEquals("203.0.113.7", players.get(0).string("ip", ""),
                "the platform's own column has to survive the merge, or the panel loses a field");
    }

    @Test
    @DisplayName("a platform that adds nothing still produces a well-formed row")
    void aPlatformWithNothingToAddStillAnswers() {
        FakePlatform platform = platform(ServerRole.STANDALONE);
        platform.join(FakePlayer.named("Alex"));
        install(platform);

        tunnel.push(Envelope.of("req-2", "get_players", Payload.empty()));

        List<Payload> players = onlyReply("player_list").payload().children("players");
        assertEquals(1, players.size());
        assertEquals("Alex", players.get(0).string("username", ""));
        assertFalse(players.get(0).has("ip"));
        assertFalse(players.get(0).has("server"));
    }

    @Test
    @DisplayName("an empty server answers with an empty roster, not with an error")
    void anEmptyServerIsASuccessfulAnswer() {
        install(platform(ServerRole.STANDALONE));

        tunnel.push(Envelope.of("req-3", "get_players", Payload.empty()));

        RecordingTunnelBus.Sent reply = onlyReply("player_list");
        assertTrue(reply.payload().hasArray("players"));
        assertEquals(0, reply.payload().children("players").size());
        assertFalse(reply.payload().has("error"),
                "nobody online is the ordinary state of most servers, and a panel that calls that an "
                        + "error is a panel nobody believes when it reports a real one");
    }

    @Test
    @DisplayName("a roster that cannot be read is still answered, with a note")
    void abrokenRosterIsStillAnswered() {
        FakePlatform platform = platform(ServerRole.STANDALONE);
        platform.describingPlayers(new Function<PlayerHandle, Payload>() {
            @Override
            public Payload apply(PlayerHandle player) {
                throw new IllegalStateException("the server is shutting down");
            }
        });
        platform.join(FakePlayer.named("Steve"));
        install(platform);

        tunnel.push(Envelope.of("req-4", "get_players", Payload.empty()));

        RecordingTunnelBus.Sent reply = onlyReply("player_list");
        assertEquals("req-4", reply.requestId());
        assertTrue(reply.payload().string("error", "").contains("shutting down"),
                "a reply the panel can render beats a spinner that ends in a 504");
    }

    // ── run_command ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("a dispatched command is acknowledged under the key v2 used")
    void aCommandIsDispatchedAndAcknowledged() {
        FakePlatform platform = platform(ServerRole.STANDALONE);
        install(platform);

        tunnel.push(Envelope.of("req-5", "run_command",
                Payload.builder().put("command", "say hello").build()));

        assertEquals(java.util.Collections.singletonList("say hello"), platform.dispatchedCommands());
        RecordingTunnelBus.Sent reply = onlyReply("command_result");
        assertEquals("req-5", reply.requestId());
        assertEquals("dispatched: say hello", reply.payload().string("output", ""),
                "the dashboard reads exactly one key off this reply, and it is 'output'");
    }

    @Test
    @DisplayName("a command the server does not have is reported, not acknowledged")
    void anUnknownCommandIsReported() {
        FakePlatform platform = platform(ServerRole.STANDALONE).withoutCommand("tempban");
        install(platform);

        tunnel.push(Envelope.of("req-6", "run_command",
                Payload.builder().put("command", "tempban Steve 1d").build()));

        RecordingTunnelBus.Sent reply = onlyReply("command_result");
        assertEquals("no such command: tempban Steve 1d", reply.payload().string("output", ""),
                "v2 said 'Command dispatched' whether or not the verb existed; the bridge now fails "
                        + "the future and an operator has to be told (departure D72)");
    }

    @Test
    @DisplayName("an empty command is answered immediately rather than left to time out")
    void anEmptyCommandIsAnswered() {
        FakePlatform platform = platform(ServerRole.STANDALONE);
        install(platform);

        tunnel.push(Envelope.of("req-7", "run_command",
                Payload.builder().put("command", "   ").build()));

        assertEquals(0, platform.dispatchedCommands().size());
        assertEquals("no command was given", onlyReply("command_result").payload().string("output", ""),
                "v2 hit a bare break here and replied nothing, burning the bot's whole timeout on a "
                        + "mistake it could have been told about at once");
    }

    @Test
    @DisplayName("a command that blows up is reported to whoever ran it")
    void aFailingCommandIsReported() {
        FakePlatform platform = platform(ServerRole.STANDALONE)
                .failingDispatch(new IllegalStateException("the plugin threw"));
        install(platform);

        tunnel.push(Envelope.of("req-8", "run_command",
                Payload.builder().put("command", "boom").build()));

        assertTrue(onlyReply("command_result").payload().string("output", "").contains("the plugin threw"));
    }

    // ── probe_player ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("a probe result is passed straight back")
    void aProbeResultIsForwarded() {
        FakePlatform platform = platform(ServerRole.STANDALONE).withTraceProbe(
                CompletableFuture.completedFuture(Payload.builder().put("mods", 3).build()));
        install(platform);

        tunnel.push(Envelope.of("req-9", "probe_player", Payload.builder()
                .put("uuid", "11111111-1111-1111-1111-111111111111")
                .build()));

        RecordingTunnelBus.Sent reply = onlyReply("probe_result");
        assertEquals("req-9", reply.requestId());
        assertEquals(3, reply.payload().intValue("mods", -1),
                "the bot forwards this object to the dashboard untouched, so nothing may reshape it");
    }

    @Test
    @DisplayName("a platform that cannot probe answers with an error rather than silence")
    void aPlatformThatCannotProbeStillAnswers() {
        // The FakePlatform default, which stands in for a proxy and for a server with no Trace: the
        // facade already answers with an error payload rather than failing or hanging.
        install(platform(ServerRole.STANDALONE));

        tunnel.push(Envelope.of("req-10", "probe_player", Payload.builder()
                .put("uuid", "11111111-1111-1111-1111-111111111111")
                .build()));

        assertEquals("no platform", onlyReply("probe_result").payload().string("error", ""));
    }

    @Test
    @DisplayName("a uuid that is not a uuid is refused at once, with the value quoted back")
    void aMalformedUuidIsAnswered() {
        install(platform(ServerRole.STANDALONE));

        tunnel.push(Envelope.of("req-11", "probe_player",
                Payload.builder().put("uuid", "not-a-uuid").build()));

        assertEquals("invalid player uuid: 'not-a-uuid'",
                onlyReply("probe_result").payload().string("error", ""),
                "this used to throw out of the switch and dangle for the bot's full probe timeout "
                        + "(#797 / MC-12)");
    }

    @Test
    @DisplayName("a missing uuid is answered too")
    void aMissingUuidIsAnswered() {
        install(platform(ServerRole.STANDALONE));

        tunnel.push(Envelope.of("req-12", "probe_player", Payload.empty()));

        assertEquals("invalid player uuid: ''",
                onlyReply("probe_result").payload().string("error", ""));
    }

    @Test
    @DisplayName("a Trace future that NEVER completes is still answered, on the deadline")
    void aNeverCompletingProbeIsAnswered() throws Exception {
        // The case the class's own premise ("every path ends in a reply") did not actually cover.
        // Trace is another plugin: nothing in this process obliges it to complete the future it
        // handed back, and a Trace blocked on its own network call used to leave this handler silent
        // forever — the same dangling request the whole class exists to abolish, one layer out.
        CompletableFuture<Payload> never = new CompletableFuture<Payload>();
        FakePlatform platform = platform(ServerRole.STANDALONE).withTraceProbe(never);
        RemoteRequestWiring.ProbePlayerHandler handler = new RemoteRequestWiring.ProbePlayerHandler(
                logger, platform, tunnel, INLINE, scheduler, 40L);

        handler.onMessage(Envelope.of("req-hang", "probe_player", Payload.builder()
                .put("uuid", "11111111-1111-1111-1111-111111111111")
                .build()));

        await("the deadline to answer", () -> !tunnel.sent("probe_result").isEmpty());
        RecordingTunnelBus.Sent reply = onlyReply("probe_result");
        assertEquals("req-hang", reply.requestId());
        assertEquals("the client probe timed out", reply.payload().string("error", ""));
        assertFalse(never.isDone(), "the probe itself is left alone — it is not ours to cancel");
    }

    @Test
    @DisplayName("Trace turning up after its deadline does not produce a second reply")
    void aLateProbeDoesNotReplyTwice() throws Exception {
        CompletableFuture<Payload> late = new CompletableFuture<Payload>();
        FakePlatform platform = platform(ServerRole.STANDALONE).withTraceProbe(late);
        RemoteRequestWiring.ProbePlayerHandler handler = new RemoteRequestWiring.ProbePlayerHandler(
                logger, platform, tunnel, INLINE, scheduler, 40L);

        handler.onMessage(Envelope.of("req-late", "probe_player", Payload.builder()
                .put("uuid", "11111111-1111-1111-1111-111111111111")
                .build()));
        await("the deadline to answer", () -> !tunnel.sent("probe_result").isEmpty());

        late.complete(Payload.builder().put("mods", 3).build());
        Thread.sleep(80L);

        // The bot files a second reply on the same id as unsolicited, so a duplicate is not a
        // harmless duplicate — and the dashboard's request is long since resolved either way.
        assertEquals("the client probe timed out",
                onlyReply("probe_result").payload().string("error", ""));
    }

    @Test
    @DisplayName("a probe that answers in time cancels its deadline and replies once")
    void aPromptProbeCancelsItsDeadline() throws Exception {
        FakePlatform platform = platform(ServerRole.STANDALONE).withTraceProbe(
                CompletableFuture.completedFuture(Payload.builder().put("mods", 7).build()));
        RemoteRequestWiring.ProbePlayerHandler handler = new RemoteRequestWiring.ProbePlayerHandler(
                logger, platform, tunnel, INLINE, scheduler, 40L);

        handler.onMessage(Envelope.of("req-prompt", "probe_player", Payload.builder()
                .put("uuid", "11111111-1111-1111-1111-111111111111")
                .build()));
        // Well past the deadline: a timer that was not cancelled would have fired by now.
        Thread.sleep(120L);

        assertEquals(7, onlyReply("probe_result").payload().intValue("mods", -1));
    }

    @Test
    @DisplayName("a probe whose future fails is answered rather than dropped")
    void aFailedProbeFutureIsAnswered() {
        CompletableFuture<Payload> failed = new CompletableFuture<Payload>();
        failed.completeExceptionally(new IllegalStateException("Trace went away"));
        install(platform(ServerRole.STANDALONE).withTraceProbe(failed));

        tunnel.push(Envelope.of("req-13", "probe_player", Payload.builder()
                .put("uuid", "11111111-1111-1111-1111-111111111111")
                .build()));

        assertTrue(onlyReply("probe_result").payload().string("error", "").contains("Trace went away"));
    }
}
