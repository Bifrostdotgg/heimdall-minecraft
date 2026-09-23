package com.heimdall.platform.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.concurrent.HeimdallExecutors;
import com.heimdall.core.config.ServerRole;
import com.heimdall.core.http.ApiClient;
import com.heimdall.core.http.ApiSettings;
import com.heimdall.core.http.HeimdallApi;
import com.heimdall.core.json.Payload;
import com.heimdall.core.log.RecordingLogger;
import com.heimdall.core.module.ModuleEnvironment;
import com.heimdall.core.module.ModuleManager;
import com.heimdall.core.pipeline.ChatPipeline;
import com.heimdall.core.pipeline.LoginAttempt;
import com.heimdall.core.pipeline.LoginPipeline;
import com.heimdall.core.remoteconfig.ConfigDocument;
import com.heimdall.core.remoteconfig.RemoteConfig;
import com.heimdall.core.session.PlayerSessionEvents;
import com.heimdall.core.testing.FakeLuckPerms;
import com.heimdall.core.testing.FakePlatform;
import com.heimdall.core.testing.FakePlayer;
import com.heimdall.core.testing.RecordingTunnelBus;
import com.heimdall.module.rolesync.HeimdallRoleSyncModule;
import com.heimdall.module.whitelist.HeimdallWhitelistModule;
import com.heimdall.stubbot.Outcome;
import com.heimdall.stubbot.PlayerFixture;
import com.heimdall.stubbot.StubBot;
import com.heimdall.stubbot.StubBotConfig;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The real whitelist and role-sync modules, wired the way production wires them, against the stub
 * bot, driven by pushed config.
 *
 * <p>The two module suites each prove their own half: role sync that it asks when told nobody else
 * will, whitelist that it says which logins it sends to the bot. This proves the halves meet, which
 * neither suite can: that {@link HeimdallModules#wireRoleSync} makes both edges, and that a login
 * costs exactly one bot call whichever module answers it.
 */
class RoleSyncWithoutWhitelistTest {

    private static final List<String> TARGET = Arrays.asList("vip");
    private static final List<String> MANAGED = Arrays.asList("vip", "member");
    private static final String SNAPSHOT = "POST role-sync/snapshot";
    private static final String CONNECTION_ATTEMPT = "POST connection-attempt";

    @TempDir
    Path dataDir;

    private final RecordingLogger logger = new RecordingLogger(true);
    private final FakeLuckPerms luckPerms = new FakeLuckPerms();
    private StubBot bot;
    private HeimdallExecutors executors;
    private RemoteConfig remoteConfig;
    private LoginPipeline logins;
    private PlayerSessionEvents sessions;
    private ModuleManager manager;
    private int configVersion;

    @BeforeEach
    void setUp() {
        bot = StubBot.start(StubBotConfig.withDemoFixtures().bindHost("127.0.0.1").port(0));
        executors = new HeimdallExecutors(logger, 2);
        build(ServerRole.STANDALONE);
    }

    /** Builds the manager and both modules on a server of {@code role}, replacing any previous. */
    private void build(ServerRole role) {
        if (manager != null) {
            manager.shutdown();
        }
        configVersion = 0;
        remoteConfig = new RemoteConfig(
                logger, dataDir.resolve(role.name() + "-config-cache.json"), ConfigDocument.empty());
        logins = new LoginPipeline(logger);
        sessions = new PlayerSessionEvents(logger, Runnable::run);
        Path platformDir = dataDir.resolve(role.name());
        try {
            java.nio.file.Files.createDirectories(platformDir);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        FakePlatform platform = new FakePlatform(role, platformDir);
        platform.withLuckPerms(luckPerms);
        ApiClient client = new ApiClient(logger, ApiSettings.builder()
                .baseUrl(bot.baseUrl())
                .guildId(StubBotConfig.DEFAULT_GUILD_ID)
                .apiKey(StubBotConfig.DEFAULT_API_KEY)
                .serverId("survival")
                .timeoutMs(4000)
                .retries(1)
                .retryDelayMs(20)
                .build(), executors.io());

        HeimdallWhitelistModule whitelist = new HeimdallWhitelistModule();
        HeimdallRoleSyncModule roleSync = new HeimdallRoleSyncModule();
        HeimdallModules.wireRoleSync(whitelist, roleSync);
        manager = new ModuleManager(ModuleEnvironment.builder()
                .logger(logger)
                .executors(executors)
                .api(new HeimdallApi(client))
                .tunnel(new RecordingTunnelBus())
                .remoteConfig(remoteConfig)
                .loginPipeline(logins)
                .chatPipeline(new ChatPipeline(logger))
                .platform(platform)
                .playerSessions(sessions)
                .build());
        manager.register(whitelist);
        manager.register(roleSync);
    }

    @AfterEach
    void tearDown() {
        try {
            manager.shutdown();
        } finally {
            executors.shutdown(2000);
            bot.close();
        }
    }

    /** Pushes a config with both modules' toggles and whitelist settings, as the dashboard would. */
    private void push(boolean whitelistEnabled, Payload whitelistSettings) {
        remoteConfig.onConfigPush(Payload.builder()
                .put("version", ++configVersion)
                .put("modules", Payload.builder()
                        .put(HeimdallWhitelistModule.ID, Payload.builder()
                                .put("enabled", whitelistEnabled)
                                .put("settings", whitelistSettings)
                                .build())
                        .put(HeimdallRoleSyncModule.ID, Payload.builder()
                                .put("enabled", true)
                                .build())
                        .build())
                .build());
        manager.reconcileFromConfig();
    }

    private FakePlayer linked(String name) {
        FakePlayer player = FakePlayer.named(name);
        bot.fixtures().put(PlayerFixture.of(player.uuid().toString(), name, Outcome.ALLOW)
                .withGroups(TARGET, MANAGED));
        return player;
    }

    private void login(FakePlayer player) {
        logins.dispatch(LoginAttempt.builder(player.uuid())
                .username(player.name())
                .ipAddress("203.0.113.7")
                .build());
    }

    private static void await(BooleanSupplier condition, String what) {
        long deadline = System.currentTimeMillis() + 15_000L;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(20L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertTrue(condition.getAsBoolean(), what);
    }

    @Test
    @DisplayName("whitelist off in the pushed config: a join fetches the snapshot and syncs groups")
    void whitelistOffStillSyncsOnJoin() {
        push(false, Payload.empty());
        FakePlayer steve = linked("Steve");

        login(steve);
        sessions.join(steve, 1L);

        await(() -> !luckPerms.syncs().isEmpty(), "role sync never ran: " + logger.records());
        assertEquals(steve.uuid(), luckPerms.syncs().get(0).uuid());
        assertEquals(TARGET, luckPerms.syncs().get(0).targetGroups());
        assertEquals(1, bot.requestCount(SNAPSHOT));
        assertEquals(0, bot.requestCount(CONNECTION_ATTEMPT),
                "with the whitelist off nothing asks connection-attempt, which is why a snapshot "
                        + "is needed at all");
    }

    @Test
    @DisplayName("a backend with enforceOnBackend off makes no call at all: its proxy makes the network's")
    void deferringBackendMakesNoCall() {
        build(ServerRole.ENFORCER);
        push(true, Payload.builder()
                .put("prewarmEnabled", false)
                .put("enforceOnBackend", false)
                .build());
        FakePlayer steve = linked("Steve");

        login(steve);
        sessions.join(steve, 1L);

        // The decision is made synchronously on join and logged, so asserting the line makes the
        // zero below mean "decided not to ask" rather than "has not arrived yet".
        assertTrue(logger.logged(com.heimdall.core.log.LogLevel.DEBUG,
                "leaves logins to its gatekeeper"), logger.records().toString());
        assertEquals(0, bot.requestCount(CONNECTION_ATTEMPT));
        assertEquals(0, bot.requestCount(SNAPSHOT),
                "every backend join and server switch would otherwise be a bot call");
    }

    @Test
    @DisplayName("a backend with the whitelist off makes no call either: the role says it is behind a proxy")
    void backendWithWhitelistOffMakesNoCall() {
        build(ServerRole.ENFORCER);
        push(false, Payload.empty());
        FakePlayer steve = linked("Steve");

        login(steve);
        sessions.join(steve, 1L);

        assertTrue(logger.logged(com.heimdall.core.log.LogLevel.DEBUG,
                "this is a backend behind a gatekeeper"), logger.records().toString());
        assertEquals(0, bot.requestCount(SNAPSHOT));
    }

    @Test
    @DisplayName("the proxy with the whitelist off makes the network's one call")
    void gatekeeperMakesOneCall() {
        build(ServerRole.GATEKEEPER);
        push(false, Payload.empty());
        FakePlayer steve = linked("Steve");

        login(steve);
        sessions.join(steve, 1L);

        await(() -> !luckPerms.syncs().isEmpty(), "the proxy should sync: " + logger.records());
        assertEquals(1, bot.requestCount(SNAPSHOT));
        assertEquals(0, bot.requestCount(CONNECTION_ATTEMPT));
    }

    @Test
    @DisplayName("whitelist on: the login answer carries the directive and no snapshot is asked for")
    void whitelistOnMakesOneCallPerLogin() {
        FakePlayer bypassed = linked("Bypassed");
        push(true, Payload.builder()
                .put("prewarmEnabled", false)
                .putStrings("bypassUuids", Collections.singletonList(bypassed.uuid().toString()))
                .build());
        FakePlayer steve = linked("Steve");

        login(steve);
        sessions.join(steve, 1L);
        // The positive control, and a real case of its own: a bypassed player is admitted without a
        // connection-attempt, so their join is the one that has to ask. Without it, "zero
        // snapshot requests" below could just mean "not arrived yet".
        login(bypassed);
        sessions.join(bypassed, 2L);

        await(() -> luckPerms.syncs().size() == 2, "both players should sync: " + logger.records());
        assertEquals(1, bot.requestCount(CONNECTION_ATTEMPT), "Steve's login, and only his");
        assertEquals(1, bot.requestCount(SNAPSHOT),
                "the bypassed player's join, and only that: Steve's login already carried his");
    }
}
