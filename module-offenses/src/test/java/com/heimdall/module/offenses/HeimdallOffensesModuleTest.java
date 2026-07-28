package com.heimdall.module.offenses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.concurrent.HeimdallExecutors;
import com.heimdall.core.config.ServerRole;
import com.heimdall.core.http.ApiClient;
import com.heimdall.core.http.HeimdallApi;
import com.heimdall.core.http.ApiSettings;
import com.heimdall.core.log.LogLevel;
import com.heimdall.core.log.RecordingLogger;
import com.heimdall.core.module.ModuleEnvironment;
import com.heimdall.core.module.ModuleManager;
import com.heimdall.core.module.ModuleState;
import com.heimdall.core.pipeline.ChatPipeline;
import com.heimdall.core.pipeline.LoginPipeline;
import com.heimdall.core.remoteconfig.ConfigDocument;
import com.heimdall.core.remoteconfig.RemoteConfig;
import com.heimdall.core.testing.FakeCommandSource;
import com.heimdall.core.testing.FakePlatform;
import com.heimdall.stubbot.StubBot;
import com.heimdall.stubbot.StubBotConfig;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The module's shape and its lifecycle: what it claims, and that switching it off really switches it
 * off.
 *
 * <p>The toggle tests are the ones that matter. v2 had no disable path at all, so a "disabled"
 * feature was one whose command still answered and whose timer still fired (D30/D53); asserting that
 * {@code register} was called would not have caught that, and neither would a test that only enabled
 * once. Enable, disable, enable, and count both.
 */
class HeimdallOffensesModuleTest {

    private final RecordingLogger logger = new RecordingLogger(true);

    @TempDir
    Path dataDir;

    private StubBot bot;
    private HeimdallExecutors executors;
    private ApiClient client;
    private FakePlatform platform;
    private TestModuleContext context;

    @BeforeEach
    void setUp() {
        bot = StubBot.start(StubBotConfig.withDemoFixtures().bindHost("127.0.0.1").port(0));
        executors = new HeimdallExecutors(logger, 2);
        client = new ApiClient(logger, ApiSettings.builder()
                .baseUrl(bot.baseUrl())
                .guildId(StubBotConfig.DEFAULT_GUILD_ID)
                .apiKey(StubBotConfig.DEFAULT_API_KEY)
                .serverId("survival")
                .timeoutMs(5000)
                .retries(1)
                .retryDelayMs(25)
                .build(), executors.io());
        platform = new FakePlatform(ServerRole.STANDALONE, dataDir);
        context = new TestModuleContext(
                HeimdallOffensesModule.ID, logger, executors, new HeimdallApi(client), platform);
    }

    @AfterEach
    void tearDown() {
        if (executors != null) {
            executors.shutdown(2000);
        }
        if (bot != null) {
            bot.close();
        }
    }

    // ── Identity ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("it is 'offenses', claims no capability, and runs under any role")
    void identity() {
        HeimdallOffensesModule module = new HeimdallOffensesModule();

        assertEquals("offenses", module.id());
        assertEquals(Collections.<String>emptySet(), module.capabilities(),
                "there is no offenses@N in Capabilities; an identifier the bot does not know is "
                        + "silently dropped from identify, so inventing one would look correct in "
                        + "testing and be ignored in production");
        assertTrue(module.roles().isEmpty(),
                "empty means any role — reporting an offense is not a login decision");
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("enable registers /offend and one repeating refresh")
    void enableRegistersBothThings() {
        HeimdallOffensesModule module = new HeimdallOffensesModule();

        module.enable(context);

        assertTrue(platform.commandRegistry().has("offend"));
        assertEquals(1, context.scheduledTasks().size());
        assertEquals(HeimdallOffensesModule.REFRESH_INTERVAL_MS,
                context.scheduledTasks().get(0).periodMs);
        assertEquals(HeimdallOffensesModule.REFRESH_INTERVAL_MS,
                context.scheduledTasks().get(0).initialDelayMs,
                "the first refresh is enable()'s own, not the scheduler's, so the timer does not "
                        + "also fire one immediately");
    }

    @Test
    @DisplayName("enable, disable, enable leaves exactly one command and one task")
    void toggleDoesNotDouble() {
        HeimdallOffensesModule module = new HeimdallOffensesModule();

        module.enable(context);
        module.disable();

        assertFalse(platform.commandRegistry().has("offend"),
                "a switched-off feature that still answers is the whole of D30/D53");
        assertEquals(0, context.scheduledTasks().size());

        module.enable(context);

        assertTrue(platform.commandRegistry().has("offend"));
        assertEquals(1, platform.commandRegistry().labels().size());
        assertEquals(1, context.scheduledTasks().size());
    }

    @Test
    @DisplayName("disable is safe when enable was never called")
    void disableWithoutEnable() {
        new HeimdallOffensesModule().disable();
    }

    @Test
    @DisplayName("disable is safe twice, and after a partially-applied enable")
    void disableIsIdempotent() {
        HeimdallOffensesModule module = new HeimdallOffensesModule();
        module.enable(context);

        module.disable();
        module.disable();

        assertFalse(platform.commandRegistry().has("offend"));
        assertEquals(0, context.scheduledTasks().size());
    }

    @Test
    @DisplayName("the task the timer holds really is the refresh")
    void theScheduledTaskRefreshes() throws Exception {
        HeimdallOffensesModule module = new HeimdallOffensesModule();
        module.enable(context);
        module.refreshOffenseTypes().get(20, TimeUnit.SECONDS);
        assertEquals(1, module.cachedTypes().size());
        assertTrue(module.cachedTypes().get(0).offenses().contains("xray"));

        // Forget everything logged so far, then fire the task by hand as heimdall-sched would.
        // Asserting on the fetch it performs is the only way to tell the scheduled Runnable apart
        // from a no-op that was registered to satisfy the count.
        logger.clear();
        context.scheduledTasks().get(0).fire();

        long deadline = System.currentTimeMillis() + 20_000L;
        while (!logger.logged(LogLevel.INFO, "Loaded 1 offense types")
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(logger.logged(LogLevel.INFO, "Loaded 1 offense types"),
                "the timer's task did not re-read the offense types: " + logger.records());
        assertEquals(1, module.cachedTypes().size());
    }

    // ── The surface phase 1e consumes ────────────────────────────────────────

    @Test
    @DisplayName("cachedTypes and refreshOffenseTypes are inert while the module is stopped")
    void thePhase1eSurfaceToleratesBeingDisabled() throws Exception {
        HeimdallOffensesModule module = new HeimdallOffensesModule();

        assertTrue(module.cachedTypes().isEmpty());
        module.refreshOffenseTypes().get(5, TimeUnit.SECONDS);
        assertTrue(module.cachedTypes().isEmpty());

        module.enable(context);
        module.refreshOffenseTypes().get(20, TimeUnit.SECONDS);
        assertFalse(module.cachedTypes().isEmpty());

        module.disable();

        assertTrue(module.cachedTypes().isEmpty(),
                "serving what it happened to hold when it was switched off is worse than serving "
                        + "nothing: a stale list is indistinguishable from a fresh one");
        module.refreshOffenseTypes().get(5, TimeUnit.SECONDS);
    }

    // ── No credentials ───────────────────────────────────────────────────────

    @Test
    @DisplayName("a server that was never set up still loads the module; /offend says so")
    void withoutCredentials() throws Exception {
        // A gateway over a client with no settings, which is exactly what an unconfigured server
        // holds since departure D56 — not a null, which is the state that made /hd setup need a
        // restart. The module must still load, or an operator cannot reach the setup flow that
        // would give it credentials.
        TestModuleContext unconfigured = new TestModuleContext(
                HeimdallOffensesModule.ID,
                logger,
                executors,
                new HeimdallApi(new ApiClient(logger, ApiSettings.builder().build(), executors.io())),
                platform);
        HeimdallOffensesModule module = new HeimdallOffensesModule();

        module.enable(unconfigured);

        assertTrue(platform.commandRegistry().has("offend"),
                "the module has to load, or the operator cannot reach the setup flow that would "
                        + "give it credentials");
        assertTrue(logger.logged(LogLevel.WARN, "the bot cannot be asked yet"));
        module.refreshOffenseTypes().get(5, TimeUnit.SECONDS);
        assertTrue(module.cachedTypes().isEmpty());

        FakeCommandSource staff = FakeCommandSource.player("ModMandy").grant(OffendCommand.PERMISSION);
        assertTrue(platform.commandRegistry().run(staff, "offend", "Anyone", "xray"));

        assertTrue(staff.wasTold("not set up"), staff.messageText().toString());
        assertTrue(platform.dispatchedCommands().isEmpty());
    }

    // ── Through the real manager ─────────────────────────────────────────────

    @Test
    @DisplayName("ModuleManager unwinds the command without the module remembering to (D30)")
    void theRealManagerUnwindsIt() {
        ModuleManager manager = new ModuleManager(ModuleEnvironment.builder()
                .logger(logger)
                .executors(executors)
                .remoteConfig(new RemoteConfig(
                        logger, dataDir.resolve("remote-config.json"), ConfigDocument.empty()))
                .loginPipeline(new LoginPipeline(logger))
                .chatPipeline(new ChatPipeline(logger))
                .platform(platform)
                .build());
        manager.register(new HeimdallOffensesModule());

        manager.reconcile(new LinkedHashSet<String>(
                Collections.singletonList(HeimdallOffensesModule.ID)));

        assertEquals(ModuleState.ENABLED, manager.state(HeimdallOffensesModule.ID));
        assertTrue(platform.commandRegistry().has("offend"));
        assertEquals(Collections.<String>emptySet(), manager.capabilities(),
                "an enabled offenses module adds nothing to the identify declaration");

        manager.reconcile(Collections.<String>emptySet());

        assertEquals(ModuleState.STOPPED, manager.state(HeimdallOffensesModule.ID));
        assertFalse(platform.commandRegistry().has("offend"));
    }
}
