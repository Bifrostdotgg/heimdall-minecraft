package com.heimdall.core.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.command.CommandRegistrar;
import com.heimdall.core.concurrent.HeimdallExecutors;
import com.heimdall.core.config.ServerRole;
import com.heimdall.core.http.ApiClient;
import com.heimdall.core.http.ApiSettings;
import com.heimdall.core.http.HeimdallApi;
import com.heimdall.core.json.Envelope;
import com.heimdall.core.json.Payload;
import com.heimdall.core.link.LinkCodePrompt;
import com.heimdall.core.link.LinkPrompter;
import com.heimdall.core.log.RecordingLogger;
import com.heimdall.core.platform.ConsoleBridge;
import com.heimdall.core.platform.Integrations;
import com.heimdall.core.platform.LinkDialogs;
import com.heimdall.core.platform.PlatformFacade;
import com.heimdall.core.platform.PlayerDirectory;
import com.heimdall.core.platform.PlayerHandle;
import com.heimdall.core.platform.SchedulerBridge;
import com.heimdall.core.session.PlayerSessionEvents;
import com.heimdall.core.testing.FakePlatform;
import com.heimdall.core.testing.FakePlayer;
import com.heimdall.core.testing.RecordingTunnelBus;
import com.heimdall.core.util.Registration;
import com.heimdall.stubbot.StubBot;
import com.heimdall.stubbot.StubBotConfig;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A pending link code reaching a player: on join through the bot's {@code pending-link-code}, and on
 * the bot's {@code show_link_code} push, which must be answered on every path.
 *
 * <p>The join half runs against the stub bot, so the request is the real signed HTTP call and the
 * answer is parsed by the real client. The push half drives envelopes through
 * {@link RecordingTunnelBus}, inline, the way {@code RemoteRequestWiringTest} does.
 */
class LinkPromptWiringTest {

    private final RecordingLogger logger = new RecordingLogger(true);
    private final RecordingTunnelBus tunnel = new RecordingTunnelBus();

    @TempDir
    Path dataDir;

    private StubBot bot;
    private HeimdallExecutors executors;
    private ScheduledExecutorService scheduler;

    /**
     * Runs inline, and counts what it has finished: the join continuation is the only thing handed to
     * it, so "one task ran" means "the bot's answer has been fully dealt with", which is what a test
     * asserting that nothing was shown has to wait for.
     */
    private final CountingExecutor io = new CountingExecutor();

    static final class CountingExecutor implements Executor {

        final AtomicInteger finished = new AtomicInteger();

        @Override
        public void execute(Runnable command) {
            try {
                command.run();
            } finally {
                finished.incrementAndGet();
            }
        }
    }

    @BeforeEach
    void start() {
        bot = StubBot.start(StubBotConfig.withDemoFixtures().bindHost("127.0.0.1").port(0));
        executors = new HeimdallExecutors(logger, 2);
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "pretend-heimdall-sched");
            thread.setDaemon(true);
            return thread;
        });
    }

    @AfterEach
    void stop() {
        scheduler.shutdownNow();
        executors.shutdown(2000);
        bot.close();
    }

    private HeimdallApi api(boolean configured) {
        ApiSettings settings = configured
                ? ApiSettings.builder()
                        .baseUrl(bot.baseUrl())
                        .guildId(StubBotConfig.DEFAULT_GUILD_ID)
                        .apiKey(StubBotConfig.DEFAULT_API_KEY)
                        .serverId("survival")
                        .timeoutMs(4000)
                        .retries(1)
                        .retryDelayMs(20)
                        .build()
                : ApiSettings.builder().build();
        return new HeimdallApi(new ApiClient(logger, settings, executors.io()));
    }

    /** Sessions dispatched inline, so a join reaches the listener before {@code join} returns. */
    private final PlayerSessionEvents sessions = new PlayerSessionEvents(logger, Runnable::run);

    private Registration install(PlatformFacade platform, HeimdallApi api, LinkPrompter prompter,
            long joinDelayMs) {
        return LinkPromptWiring.install(logger, platform, api, tunnel, sessions, io, scheduler,
                prompter, joinDelayMs);
    }

    private Registration install(PlatformFacade platform) {
        return install(platform, api(true), new LinkPrompter(logger, platform, System::currentTimeMillis),
                0L);
    }

    private static String inFiveMinutes() {
        return Instant.now().plusSeconds(300).toString();
    }

    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000L;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(10L);
        }
    }

    private Payload push(String id, Payload payload) {
        tunnel.push(Envelope.of(id, "show_link_code", payload));
        List<RecordingTunnelBus.Sent> replies = tunnel.sent("show_link_code_result");
        assertEquals(1, replies.size(), "exactly one reply per request, got " + tunnel.sent());
        assertEquals(id, replies.get(0).requestId(), "the echoed id is the correlation");
        tunnel.clearSent();
        return replies.get(0).payload();
    }

    private static Payload request(UUID uuid, String code, String expiresAt) {
        Payload.Builder builder = Payload.builder().put("username", "Steve").put("code", code);
        if (uuid != null) {
            builder.put("uuid", uuid.toString());
        }
        if (expiresAt != null) {
            builder.put("expiresAt", expiresAt);
        }
        return builder.build();
    }

    // ── Registration ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("the join listener and the push handler are installed together and removed together")
    void installsAndUnwinds() {
        Registration handle = install(new FakePlatform(ServerRole.STANDALONE, dataDir));

        assertEquals(1, sessions.joinListenerCount());
        assertEquals(1, tunnel.subscriberCount("show_link_code"));
        assertSame(io, tunnel.subscribedExecutor("show_link_code"),
                "handled on heimdall-io, never the socket's reading thread (departure D27)");

        handle.close();

        assertEquals(0, sessions.joinListenerCount());
        assertEquals(0, tunnel.subscriberCount("show_link_code"));
    }

    // ── On join ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a player who joins with a code pending is shown it, after the join delay")
    void joinWithAPendingCodeShowsIt() throws Exception {
        FakePlatform platform = new FakePlatform(ServerRole.STANDALONE, dataDir);
        final FakePlayer steve = platform.join(FakePlayer.named("Steve"));
        bot.pendLinkCode(steve.uuid().toString(), "246810", inFiveMinutes());
        install(platform, api(true), new LinkPrompter(logger, platform, System::currentTimeMillis), 20L);

        sessions.join(steve, System.currentTimeMillis());

        await("the code in chat", () -> steve.messageText().size() == 2);
        assertTrue(steve.messageText().get(0).contains("246810"), steve.messageText().toString());
        assertTrue(steve.messageText().get(1).contains("Confirm Code"));
        assertEquals("Steve", bot.lastPendingLinkCodeRequest().get("username").getAsString());
    }

    @Test
    @DisplayName("a dialog-capable server shows the joining player a dialog instead")
    void joinPrefersTheDialog() throws Exception {
        final List<String> dialogs = new CopyOnWriteArrayList<String>();
        FakePlatform platform = new FakePlatform(ServerRole.STANDALONE, dataDir)
                .withLinkDialogs(new LinkDialogs() {
                    @Override
                    public boolean show(PlayerHandle player, LinkCodePrompt prompt, Runnable fallback) {
                        dialogs.add(prompt.code());
                        return true;
                    }
                });
        FakePlayer steve = platform.join(FakePlayer.named("Steve"));
        bot.pendLinkCode(steve.uuid().toString(), "246810", inFiveMinutes());
        install(platform);

        sessions.join(steve, System.currentTimeMillis());

        await("the dialog", () -> dialogs.size() == 1);
        assertEquals("246810", dialogs.get(0));
        assertTrue(steve.messageText().isEmpty());
    }

    @Test
    @DisplayName("a player who joins with nothing pending is shown nothing")
    void joinWithNothingPendingShowsNothing() throws Exception {
        FakePlatform platform = new FakePlatform(ServerRole.STANDALONE, dataDir);
        FakePlayer steve = platform.join(FakePlayer.named("Steve"));
        install(platform);

        sessions.join(steve, System.currentTimeMillis());

        await("the bot's answer to be handled", () -> io.finished.get() == 1);
        assertEquals(1, bot.requestCount("POST pending-link-code"));
        assertTrue(steve.messageText().isEmpty(), steve.messageText().toString());
    }

    @Test
    @DisplayName("a player who left before the answer came back is not looked for")
    void joinThenLeave() throws Exception {
        FakePlatform platform = new FakePlatform(ServerRole.STANDALONE, dataDir);
        FakePlayer steve = FakePlayer.named("Steve");
        bot.pendLinkCode(steve.uuid().toString(), "246810", inFiveMinutes());
        install(platform);

        // Never put online: the join event fired, then they were gone by the time the bot answered.
        sessions.join(steve, System.currentTimeMillis());

        await("the bot's answer to be handled", () -> io.finished.get() == 1);
        assertTrue(steve.messageText().isEmpty());
    }

    @Test
    @DisplayName("a server that is not set up does not ask at all")
    void unusableApiDoesNotAsk() {
        FakePlatform platform = new FakePlatform(ServerRole.STANDALONE, dataDir);
        FakePlayer steve = platform.join(FakePlayer.named("Steve"));
        install(platform, api(false), new LinkPrompter(logger, platform, System::currentTimeMillis), 0L);

        sessions.join(steve, System.currentTimeMillis());

        assertEquals(0, bot.requestCount("POST pending-link-code"));
        assertEquals(0, io.finished.get());
        assertTrue(steve.messageText().isEmpty());
    }

    // ── On push ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a push for a player online here shows the code and answers shown: true")
    void pushToAnOnlinePlayer() {
        FakePlatform platform = new FakePlatform(ServerRole.STANDALONE, dataDir);
        FakePlayer steve = platform.join(FakePlayer.named("Steve"));
        install(platform);

        Payload reply = push("req-1", request(steve.uuid(), "135790", inFiveMinutes()));

        assertTrue(reply.bool("shown", false), reply.toJson());
        assertFalse(reply.has("reason"));
        assertEquals(2, steve.messageText().size());
        assertTrue(steve.messageText().get(0).contains("135790"));
    }

    @Test
    @DisplayName("a push for a player who is not here answers shown: false, not_online")
    void pushToAnAbsentPlayer() {
        FakePlatform platform = new FakePlatform(ServerRole.STANDALONE, dataDir);
        install(platform);

        Payload reply = push("req-2", request(UUID.randomUUID(), "135790", inFiveMinutes()));

        assertFalse(reply.bool("shown", true));
        assertEquals("not_online", reply.string("reason", ""));
    }

    @Test
    @DisplayName("every malformed or refused push is still answered, with a reason")
    void everyPathReplies() {
        FakePlatform platform = new FakePlatform(ServerRole.STANDALONE, dataDir);
        FakePlayer steve = platform.join(FakePlayer.named("Steve"));
        install(platform);

        assertEquals("invalid_uuid",
                push("a", request(null, "135790", null)).string("reason", ""));
        assertEquals("invalid_uuid",
                push("b", Payload.builder().put("uuid", "not-a-uuid").put("code", "1").build())
                        .string("reason", ""));
        assertEquals("missing_code",
                push("c", request(steve.uuid(), "  ", null)).string("reason", ""));
        assertEquals("expired",
                push("d", request(steve.uuid(), "135790", Instant.now().minusSeconds(5).toString()))
                        .string("reason", ""));
        assertTrue(steve.messageText().isEmpty(), "none of those showed anything");
    }

    @Test
    @DisplayName("a push whose roster read throws is answered with shown: false, error")
    void aBrokenServerStillReplies() {
        PlatformFacade broken = new BrokenRoster(new FakePlatform(ServerRole.STANDALONE, dataDir));
        install(broken);

        Payload reply = push("req-3", request(UUID.randomUUID(), "135790", inFiveMinutes()));

        assertFalse(reply.bool("shown", true));
        assertEquals("error", reply.string("reason", ""));
    }

    // ── The race ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the join check and a push for the same code show it once, and the push still says shown")
    void joinAndPushRace() throws Exception {
        FakePlatform platform = new FakePlatform(ServerRole.STANDALONE, dataDir);
        final FakePlayer steve = platform.join(FakePlayer.named("Steve"));
        String expiry = inFiveMinutes();
        bot.pendLinkCode(steve.uuid().toString(), "246810", expiry);
        install(platform);

        sessions.join(steve, System.currentTimeMillis());
        await("the join prompt", () -> steve.messageText().size() == 2);

        Payload reply = push("req-4", request(steve.uuid(), "246810", expiry));

        assertTrue(reply.bool("shown", false), "the player has the code in front of them");
        assertTrue(reply.bool("alreadyShown", false));
        assertEquals(2, steve.messageText().size(), "not shown a second time: " + steve.messageText());
    }

    /** A platform whose player lookup throws, as a server part-way through stopping might. */
    private static final class BrokenRoster implements PlatformFacade {

        private final FakePlatform delegate;

        BrokenRoster(FakePlatform delegate) {
            this.delegate = delegate;
        }

        @Override
        public ServerRole role() {
            return delegate.role();
        }

        @Override
        public Path dataDirectory() {
            return delegate.dataDirectory();
        }

        @Override
        public Executor mainThread() {
            return delegate.mainThread();
        }

        @Override
        public PlayerDirectory players() {
            return new PlayerDirectory() {
                @Override
                public Optional<PlayerHandle> byUuid(UUID uuid) {
                    throw new IllegalStateException("the server is stopping");
                }

                @Override
                public Optional<PlayerHandle> byName(String username) {
                    throw new IllegalStateException("the server is stopping");
                }

                @Override
                public Collection<PlayerHandle> onlinePlayers() {
                    throw new IllegalStateException("the server is stopping");
                }

                @Override
                public Payload describe(PlayerHandle player) {
                    return Payload.empty();
                }
            };
        }

        @Override
        public SchedulerBridge scheduler() {
            return delegate.scheduler();
        }

        @Override
        public ConsoleBridge console() {
            return delegate.console();
        }

        @Override
        public CommandRegistrar commands() {
            return delegate.commands();
        }

        @Override
        public Integrations integrations() {
            return delegate.integrations();
        }
    }
}
