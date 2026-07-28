package com.heimdall.core.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.config.BootstrapConfig;
import com.heimdall.core.config.BootstrapStore;
import com.heimdall.core.config.ServerRole;
import com.heimdall.core.http.ApiError;
import com.heimdall.core.http.ClaimClient;
import com.heimdall.core.http.ClaimRequest;
import com.heimdall.core.http.HeimdallApi;
import com.heimdall.core.http.model.ClaimResult;
import com.heimdall.core.http.model.ConfigImportResult;
import com.heimdall.core.json.Payload;
import com.heimdall.core.log.RecordingLogger;
import com.heimdall.core.module.HeimdallModule;
import com.heimdall.core.module.ModuleContext;
import com.heimdall.core.testing.Await;
import com.heimdall.core.testing.FakePlatform;
import com.heimdall.core.tunnel.Capabilities;
import com.heimdall.stubbot.StubBot;
import com.heimdall.stubbot.StubBotConfig;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code /hd setup}, end to end, against the executable copy of the bot's wire contract.
 *
 * <h2>The one property this file exists for</h2>
 *
 * <p>A server that has never been set up boots with modules registered and running. Those modules
 * captured whatever the API was <em>then</em>. Departure D56 is the observation that, before 1e,
 * what they captured was {@code null} and nothing could ever replace it — so claiming a setup code
 * produced a server with a live tunnel and an {@code /offend} that refused, and the only fix was a
 * restart nobody was told to perform.
 *
 * <p>So {@link Restartless#modulesGoLiveWithoutARestart} is the headline: a module takes its
 * reference before the claim, the claim happens, and <strong>that same reference</strong> works
 * afterwards. Everything else here is the error handling around it.
 *
 * <p>Run against {@code :stub-bot} rather than a mock, because the claim endpoint's edges — a
 * single-use code, the {@code 401 INVALID_CODE} shape, a claim that registers a server id the
 * WebSocket upgrade then has to accept — are precisely the things a mock would be written to agree
 * with.
 */
class SetupFlowTest {

    private final RecordingLogger logger = new RecordingLogger(true);

    private StubBot bot;
    private ExecutorService io;

    @TempDir
    Path dataDir;

    @BeforeEach
    void startBot() {
        bot = StubBot.start(StubBotConfig.withDemoFixtures().bindHost("127.0.0.1").port(0));
        io = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void stopBot() {
        if (io != null) {
            io.shutdownNow();
        }
        if (bot != null) {
            bot.close();
        }
    }

    /** A module that does nothing but remember the gateway it was handed, and count its enables. */
    private static final class Capturing implements HeimdallModule {

        volatile HeimdallApi api;
        volatile int enables;

        @Override
        public String id() {
            return "whitelist";
        }

        @Override
        public Set<String> capabilities() {
            return Collections.singleton(Capabilities.WHITELIST);
        }

        @Override
        public Set<ServerRole> roles() {
            return Collections.emptySet();
        }

        @Override
        public void enable(ModuleContext context) {
            this.api = context.api();
            this.enables++;
        }

        @Override
        public void disable() {
        }
    }

    private HeimdallRuntime unconfiguredRuntime(BootstrapStore store) {
        return HeimdallRuntime.builder(logger, new FakePlatform(ServerRole.STANDALONE, dataDir))
                .bootstrapStore(store)
                .build();
    }

    private ClaimResult claim(String code) throws Exception {
        return new ClaimClient(logger, io)
                .claim(bot.baseUrl(), ClaimRequest.forCode(code)
                        .platform("bukkit")
                        .mcVersion("1.21.8")
                        .role(ServerRole.STANDALONE)
                        .build())
                .get(20, TimeUnit.SECONDS);
    }

    @Nested
    @DisplayName("claiming a code")
    class Claiming {

        @Test
        @DisplayName("returns the credentials, exactly once")
        void succeeds() throws Exception {
            bot.issueClaimCode("ABCD2345", "Survival");

            ClaimResult claimed = claim("ABCD2345");

            assertTrue(claimed.isComplete());
            assertEquals(StubBotConfig.DEFAULT_GUILD_ID, claimed.guildId());
            assertEquals("Survival", claimed.serverName());
            assertFalse(claimed.serverId().isEmpty(), "the claim is what registers the server id");
            assertFalse(claimed.token().isEmpty());
            assertFalse(claimed.toString().contains(claimed.token()),
                    "toString must redact the token: this object is one reflex log line away from "
                            + "putting a bearer credential in a support ticket");
        }

        @Test
        @DisplayName("accepts the code as an operator would type it, dashes and all")
        void normalisesOperatorTyping() throws Exception {
            bot.issueClaimCode("ABCD2345", "Survival");

            // Not normalised client-side, deliberately — the bot upper-cases and strips, and two
            // implementations of one rule is how they end up disagreeing.
            assertTrue(claim("abcd-2345").isComplete());
        }

        @Test
        @DisplayName("an unknown code is INVALID_CODE, and a used one is the same thing")
        void singleUse() throws Exception {
            bot.issueClaimCode("ABCD2345", "Survival");
            assertTrue(claim("ABCD2345").isComplete());

            assertEquals("INVALID_CODE", errorCodeFrom(assertThrows(ExecutionException.class,
                    () -> claim("ABCD2345"))));
            assertEquals("INVALID_CODE", errorCodeFrom(assertThrows(ExecutionException.class,
                    () -> claim("NEVERMINTED"))));
        }

        @Test
        @DisplayName("ten failures earn a 429, whatever is sent afterwards")
        void throttled() {
            for (int attempt = 0; attempt < 10; attempt++) {
                final String wrong = "WRONG" + attempt;
                assertThrows(ExecutionException.class, () -> claim(wrong));
            }
            bot.issueClaimCode("GOODCODE", "Survival");

            assertEquals("TOO_MANY_ATTEMPTS", errorCodeFrom(assertThrows(ExecutionException.class,
                    () -> claim("GOODCODE"))),
                    "the throttle is checked before the body is read, so even a valid code is "
                            + "refused — which is worth knowing before an operator burns three of them");
        }

        private String errorCodeFrom(ExecutionException raised) {
            Throwable cause = raised.getCause();
            assertTrue(cause instanceof ApiError, "expected the bot to answer and refuse, got " + cause);
            return ((ApiError) cause).code();
        }
    }

    @Nested
    @DisplayName("applying the claim")
    class Restartless {

        @Test
        @DisplayName("modules go live without a restart — departure D56")
        void modulesGoLiveWithoutARestart() throws Exception {
            BootstrapStore store = new BootstrapStore(logger, dataDir.resolve("bootstrap.yml"));
            HeimdallRuntime runtime = unconfiguredRuntime(store);
            Capturing module = new Capturing();
            runtime.modules().register(module);
            runtime.start();

            // Enabled by hand, because a genuinely fresh install has no config cache and therefore
            // nothing enabled until the first config.push — and the whole point here is a module
            // that was already running BEFORE the claim. Forcing it is the only way to reach that
            // state without a bot, and it is the state a migrated v2 server (which does have a
            // cache) boots straight into.
            runtime.modules().reconcile(Collections.singleton("whitelist"));

            // The state that used to be a trap: the module is running and holding a gateway that
            // cannot be used. Before 1e it was holding a null, and this is the moment the difference
            // stops being cosmetic.
            assertEquals(1, module.enables);
            assertNotNull(module.api, "the module must be enabled before setup, or this proves nothing");
            assertEquals(HeimdallApi.Availability.NOT_CONFIGURED, module.api.availability());
            HeimdallApi captured = module.api;

            bot.issueClaimCode("ABCD2345", "Survival");
            ClaimResult claimed = claim("ABCD2345");
            runtime.applySetup(runtime.bootstrap().toBuilder()
                    .endpoint(bot.baseUrl())
                    .tokenId(claimed.tokenId())
                    .token(claimed.token())
                    .serverId(claimed.serverId())
                    .guildId(claimed.guildId())
                    .build());

            assertEquals(1, module.enables,
                    "and it was not restarted to get there — a re-enable would be a different fix, "
                    + "and one that drops every subscription and mirror the module was holding");
            assertSame(captured, module.api,
                    "nothing re-handed the module anything — the object is the same one");
            assertTrue(captured.isUsable(),
                    "and it works now, which is the entire point of the gateway");
            assertTrue(runtime.isConfigured());

            // The wire half: the tunnel was built idle at boot and dialled by the setup, on a guild
            // and a server id that did not exist when it was constructed. Asserted from the STUB's
            // side, so it is about a socket the bot accepted rather than about a flag the client
            // set on itself.
            assertNotNull(bot.ws().awaitConnection(claimed.guildId(), claimed.serverId(), 20_000L),
                    "the tunnel must connect without a restart");
            Await.until("the client to agree it is connected",
                    () -> runtime.tunnel().isConnected(), 10_000L);

            runtime.close();
        }

        @Test
        @DisplayName("writes the credentials, and the guild as a cache rather than a setting")
        void persistsWhatItLearned() throws Exception {
            Path file = dataDir.resolve("bootstrap.yml");
            BootstrapStore store = new BootstrapStore(logger, file);
            HeimdallRuntime runtime = unconfiguredRuntime(store);
            runtime.start();

            bot.issueClaimCode("ABCD2345", "Survival");
            ClaimResult claimed = claim("ABCD2345");
            runtime.applySetup(runtime.bootstrap().toBuilder()
                    .endpoint(bot.baseUrl())
                    .tokenId(claimed.tokenId())
                    .token(claimed.token())
                    .serverId(claimed.serverId())
                    .guildId(claimed.guildId())
                    .build());

            BootstrapConfig reloaded = new BootstrapStore(logger, file).load();
            assertEquals(bot.baseUrl(), reloaded.endpoint());
            assertEquals(claimed.token(), reloaded.token());
            assertEquals(claimed.serverId(), reloaded.serverId());
            assertEquals(claimed.guildId(), reloaded.guildId());

            String onDisk = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            assertTrue(onDisk.contains("guildIdCache:"),
                    "the guild is written under a name that says it is not an operator's to set "
                            + "(departure D54); a bare guildId invites the correction that produces "
                            + "a server signing valid requests against somebody else's guild");

            runtime.close();
        }

        @Test
        @DisplayName("refuses a config that would not be able to reach anything")
        void refusesAnIncompleteSetup() {
            BootstrapStore store = new BootstrapStore(logger, dataDir.resolve("bootstrap.yml"));
            HeimdallRuntime runtime = unconfiguredRuntime(store);

            assertThrows(IllegalArgumentException.class,
                    () -> runtime.applySetup(BootstrapConfig.defaults()),
                    "writing a file that looks configured and fails every request afterwards is a "
                            + "much harder thing to diagnose than a refusal here");

            runtime.close();
        }
    }

    @Test
    @DisplayName("a migrated config can be handed to the dashboard once, and only once")
    void configImportIsWriteOnce() throws Exception {
        BootstrapStore store = new BootstrapStore(logger, dataDir.resolve("bootstrap.yml"));
        HeimdallRuntime runtime = unconfiguredRuntime(store);
        runtime.start();

        bot.issueClaimCode("ABCD2345", "Survival");
        ClaimResult claimed = claim("ABCD2345");
        runtime.applySetup(runtime.bootstrap().toBuilder()
                .endpoint(bot.baseUrl())
                .tokenId(claimed.tokenId())
                .token(claimed.token())
                .serverId(claimed.serverId())
                .guildId(claimed.guildId())
                .build());

        Payload modules = Payload.builder()
                .put("whitelist", Payload.builder().put("enabled", true).build())
                .build();

        ConfigImportResult first =
                runtime.api().importConfig(claimed.serverId(), modules).get(20, TimeUnit.SECONDS);
        ConfigImportResult second =
                runtime.api().importConfig(claimed.serverId(), modules).get(20, TimeUnit.SECONDS);

        assertTrue(first.imported());
        assertFalse(second.imported(),
                "write-once is what lets a first-boot migration call this unconditionally without "
                        + "clobbering whatever an operator has since edited in the dashboard");

        runtime.close();
    }
}
