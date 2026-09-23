package com.heimdall.platform.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.heimdall.core.concurrent.HeimdallExecutors;
import com.heimdall.core.config.ServerRole;
import com.heimdall.core.log.RecordingLogger;
import com.heimdall.core.module.ModuleEnvironment;
import com.heimdall.core.module.ModuleManager;
import com.heimdall.core.pipeline.ChatPipeline;
import com.heimdall.core.pipeline.LoginPipeline;
import com.heimdall.core.remoteconfig.ConfigDocument;
import com.heimdall.core.remoteconfig.RemoteConfig;
import com.heimdall.core.session.PlayerSessionEvents;
import com.heimdall.core.testing.FakeLuckPerms;
import com.heimdall.core.testing.FakePlatform;
import com.heimdall.core.testing.FakePlayer;
import com.heimdall.core.tunnel.Capabilities;
import com.heimdall.core.tunnel.ServerIdentity;
import com.heimdall.core.tunnel.TunnelClient;
import com.heimdall.core.tunnel.TunnelSettings;
import com.heimdall.core.util.Registration;
import com.heimdall.module.rolesync.HeimdallRoleSyncModule;
import com.heimdall.stubbot.ConnectedServer;
import com.heimdall.stubbot.StubBot;
import com.heimdall.stubbot.StubBotConfig;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Reverse role sync end to end: config pushed by the stub bot over a real tunnel, a LuckPerms change,
 * and the {@code rolesync.groups} frame the stub receives.
 *
 * <p>The module suite proves the reporter's decisions against a recording bus. This proves the parts
 * it cannot: that {@code watchedGroups} survives the real config push and parse (the stub sends
 * settings flat beside {@code enabled}, which is the shape the parser has to tolerate), that the
 * module manager follows the push, and that the frame arrives on the wire under the type and in the
 * shape the bot's handler reads.
 */
class ReverseRoleSyncStubTest {

    private static final String GUILD = StubBotConfig.DEFAULT_GUILD_ID;
    private static final String SERVER_ID = "survival";

    @TempDir
    Path dataDir;

    private final RecordingLogger logger = new RecordingLogger(true);
    private final FakeLuckPerms luckPerms = new FakeLuckPerms();
    private StubBot bot;
    private HeimdallExecutors executors;
    private TunnelClient client;
    private ModuleManager manager;
    private Registration following;

    @AfterEach
    void tearDown() {
        try {
            if (following != null) {
                following.close();
            }
            if (manager != null) {
                manager.shutdown();
            }
            if (client != null) {
                client.shutdown();
            }
        } finally {
            if (executors != null) {
                executors.shutdown(2000);
            }
            if (bot != null) {
                bot.close();
            }
        }
    }

    @Test
    @DisplayName("watchedGroups pushed by the bot: a LuckPerms change reaches the stub as one rolesync.groups frame")
    void rankChangeReachesTheBot() throws Exception {
        JsonObject roleSync = new JsonObject();
        roleSync.addProperty("enabled", true);
        JsonArray watched = new JsonArray();
        watched.add("vip");
        roleSync.add("watchedGroups", watched);
        JsonObject modules = new JsonObject();
        modules.add(Capabilities.moduleId(Capabilities.ROLE_SYNC), roleSync);
        bot = StubBot.start(StubBotConfig.withDemoFixtures()
                .bindHost("127.0.0.1")
                .port(0)
                .configVersion(1)
                .modules(modules));

        executors = new HeimdallExecutors(logger, 2);
        RemoteConfig remoteConfig = new RemoteConfig(
                logger, dataDir.resolve("remote-config.json"), ConfigDocument.empty());
        client = TunnelClient.builder(logger, executors)
                .settings(TunnelSettings.builder()
                        .endpoint(bot.baseUrl())
                        .guildId(GUILD)
                        .serverId(SERVER_ID)
                        .apiKey(StubBotConfig.DEFAULT_API_KEY)
                        .reconnectDelayMs(20L)
                        .maxReconnectDelayMs(160L)
                        .heartbeatIntervalMs(60_000L)
                        .negotiationTimeoutMs(2_000L)
                        .build())
                .identitySource(() -> ServerIdentity.builder()
                        .serverName("Survival")
                        .platform("bukkit")
                        .serverSoftware("Paper")
                        .mcVersion("1.20.4")
                        .startedAtMs(1_700_000_000_000L)
                        .role(ServerRole.STANDALONE)
                        .build())
                .capabilitySource(() -> Collections.singleton(Capabilities.ROLE_SYNC))
                .configPushHandler(remoteConfig)
                .build();
        client.onModeChange(remoteConfig);

        FakePlatform platform = new FakePlatform(ServerRole.STANDALONE, dataDir);
        platform.withLuckPerms(luckPerms);
        PlayerSessionEvents sessions = new PlayerSessionEvents(logger, Runnable::run);
        manager = new ModuleManager(ModuleEnvironment.builder()
                .logger(logger)
                .executors(executors)
                .tunnel(client)
                .remoteConfig(remoteConfig)
                .loginPipeline(new LoginPipeline(logger))
                .chatPipeline(new ChatPipeline(logger))
                .platform(platform)
                .playerSessions(sessions)
                .build());
        manager.register(new HeimdallRoleSyncModule());
        following = manager.followRemoteConfig();

        client.connect();
        await(() -> luckPerms.groupListenerCount() == 1,
                "the pushed config should enable the module and, with vip watched, subscribe: "
                        + logger.records());
        ConnectedServer connected = bot.ws().connected(GUILD, SERVER_ID);

        FakePlayer steve = platform.join(FakePlayer.named("Steve"));
        luckPerms.holding(steve.uuid(), "default");
        sessions.join(steve, 1L);

        luckPerms.fireGroupsChanged(steve.uuid(), Arrays.asList("default", "vip"));

        await(() -> connected.roleSyncGroupReports().size() == 1,
                "the stub should receive one rolesync.groups frame: " + logger.records());
        JsonObject report = connected.roleSyncGroupReports().get(0);
        assertEquals(steve.uuid().toString(), report.get("uuid").getAsString());
        assertEquals("Steve", report.get("username").getAsString());
        List<String> groups = new ArrayList<String>();
        for (com.google.gson.JsonElement group : report.getAsJsonArray("groups")) {
            groups.add(group.getAsString());
        }
        assertEquals(Arrays.asList("default", "vip"), groups);
        assertTrue(report.get("at").getAsLong() > 0L);

        Thread.sleep(900L);
        assertEquals(1, connected.roleSyncGroupReports().size(), "one change, one frame");
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
}
