package com.heimdall.platform.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code rolesync.resync} over a real tunnel: the plugin declares {@code rolesyncresync@1}, the stub
 * accepts it, and a resync sent with the capability-filtered notify comes back as a
 * {@code rolesync.groups} frame. A second server declaring only {@code rolesync@1} is never sent it.
 */
class RoleSyncResyncStubTest {

    private static final String GUILD = StubBotConfig.DEFAULT_GUILD_ID;

    @TempDir
    Path dataDir;

    private final RecordingLogger logger = new RecordingLogger(true);
    private final List<AutoCloseable> closing = new ArrayList<AutoCloseable>();
    private StubBot bot;
    private HeimdallExecutors executors;

    @AfterEach
    void tearDown() throws Exception {
        try {
            for (int i = closing.size() - 1; i >= 0; i--) {
                closing.get(i).close();
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

    /** One server with the rolesync module enabled by the stub's push, declaring {@code caps}. */
    private FakePlatform server(String serverId, Set<String> caps) {
        RemoteConfig remoteConfig = new RemoteConfig(
                logger, dataDir.resolve(serverId + "-remote-config.json"), ConfigDocument.empty());
        final TunnelClient client = TunnelClient.builder(logger, executors)
                .settings(TunnelSettings.builder()
                        .endpoint(bot.baseUrl())
                        .guildId(GUILD)
                        .serverId(serverId)
                        .apiKey(StubBotConfig.DEFAULT_API_KEY)
                        .reconnectDelayMs(20L)
                        .maxReconnectDelayMs(160L)
                        .heartbeatIntervalMs(60_000L)
                        .negotiationTimeoutMs(2_000L)
                        .build())
                .identitySource(() -> ServerIdentity.builder()
                        .serverName(serverId)
                        .platform("bukkit")
                        .serverSoftware("Paper")
                        .mcVersion("1.20.4")
                        .startedAtMs(1_700_000_000_000L)
                        .role(ServerRole.STANDALONE)
                        .build())
                .capabilitySource(() -> caps)
                .configPushHandler(remoteConfig)
                .build();
        client.onModeChange(remoteConfig);
        FakePlatform platform = new FakePlatform(ServerRole.STANDALONE, dataDir.resolve(serverId));
        FakeLuckPerms luckPerms = new FakeLuckPerms();
        platform.withLuckPerms(luckPerms);
        final ModuleManager manager = new ModuleManager(ModuleEnvironment.builder()
                .logger(logger)
                .executors(executors)
                .tunnel(client)
                .remoteConfig(remoteConfig)
                .loginPipeline(new LoginPipeline(logger))
                .chatPipeline(new ChatPipeline(logger))
                .platform(platform)
                .playerSessions(new PlayerSessionEvents(logger, Runnable::run))
                .build());
        manager.register(new HeimdallRoleSyncModule());
        final Registration following = manager.followRemoteConfig();
        closing.add(client::shutdown);
        closing.add(manager::shutdown);
        closing.add(following::close);
        client.connect();
        return platform;
    }

    @Test
    @DisplayName("the stub accepts rolesyncresync@1, sends resync only there, and gets the full list back")
    void resyncOverTheWire() throws Exception {
        JsonObject roleSync = new JsonObject();
        roleSync.addProperty("enabled", true);
        JsonArray watched = new JsonArray();
        watched.add("zeal");
        roleSync.add("watchedGroups", watched);
        JsonObject modules = new JsonObject();
        modules.add(Capabilities.moduleId(Capabilities.ROLE_SYNC), roleSync);
        bot = StubBot.start(StubBotConfig.withDemoFixtures()
                .bindHost("127.0.0.1")
                .port(0)
                .configVersion(1)
                .modules(modules));
        executors = new HeimdallExecutors(logger, 2);

        FakePlatform capable = server("survival", new LinkedHashSet<String>(
                Arrays.asList(Capabilities.ROLE_SYNC, Capabilities.ROLE_SYNC_RESYNC)));
        FakePlatform older = server("lobby", Collections.singleton(Capabilities.ROLE_SYNC));
        await(() -> accepted("survival").contains("rolesyncresync@1")
                        && bot.ws().connected(GUILD, "lobby") != null
                        && !accepted("lobby").isEmpty(),
                "both connected and negotiated: " + logger.records());
        assertEquals(Collections.singletonList("rolesync@1"), accepted("lobby"));

        FakePlayer pio = capable.join(FakePlayer.named("Pio"));
        ((FakeLuckPerms) capable.integrations().luckPerms().get()).holding(pio.uuid(), "default", "zeal");
        older.join(FakePlayer.named("Pio"));
        ((FakeLuckPerms) older.integrations().luckPerms().get()).holding(pio.uuid(), "default", "zeal");

        assertEquals(1, bot.ws().sendRoleSyncResync(GUILD, pio.uuid().toString(), "Pio"),
                "only the server that accepted rolesyncresync@1 is sent it");

        ConnectedServer survival = bot.ws().connected(GUILD, "survival");
        await(() -> survival.roleSyncGroupReports().size() == 1,
                "the capable server answers with rolesync.groups: " + logger.records());
        JsonObject report = survival.roleSyncGroupReports().get(0);
        assertEquals(pio.uuid().toString(), report.get("uuid").getAsString());
        assertEquals("Pio", report.get("username").getAsString());
        List<String> groups = new ArrayList<String>();
        for (JsonElement group : report.getAsJsonArray("groups")) {
            groups.add(group.getAsString());
        }
        assertEquals(Arrays.asList("default", "zeal"), groups);
        assertTrue(report.get("at").getAsLong() > 0L);

        Thread.sleep(300L);
        assertEquals(0, bot.ws().connected(GUILD, "lobby").roleSyncGroupReports().size());
    }

    private List<String> accepted(String serverId) {
        ConnectedServer server = bot.ws().connected(GUILD, serverId);
        List<String> accepted = server == null ? null : server.acceptedCapabilities();
        return accepted == null ? Collections.<String>emptyList() : accepted;
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
