package com.heimdall.module.punishments;

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
import com.heimdall.core.pipeline.CommandPipeline;
import com.heimdall.core.pipeline.LoginAttempt;
import com.heimdall.core.pipeline.LoginPipeline;
import com.heimdall.core.pipeline.Verdict;
import com.heimdall.core.remoteconfig.ConfigDocument;
import com.heimdall.core.remoteconfig.RemoteConfig;
import com.heimdall.core.session.PlayerSessionEvents;
import com.heimdall.core.testing.FakePlatform;
import com.heimdall.core.testing.RecordingTunnelBus;
import java.nio.file.Path;
import java.util.UUID;

final class PunishmentsHarness implements AutoCloseable {

    final RecordingLogger logger = new RecordingLogger(true);
    final HeimdallExecutors executors;
    final LoginPipeline loginPipeline;
    final ChatPipeline chatPipeline;
    final CommandPipeline commandPipeline;
    final RemoteConfig remoteConfig;
    final FakePlatform platform;
    final PlayerSessionEvents sessions;
    final RecordingTunnelBus tunnel = new RecordingTunnelBus();
    final ModuleManager manager;
    final HeimdallPunishmentsModule module;
    final HeimdallApi api;
    private int configVersion;

    PunishmentsHarness(Path dataDir, ServerRole role) {
        this(dataDir, role, ApiSettings.builder().build());
    }

    static PunishmentsHarness withApi(Path dataDir, ServerRole role, String baseUrl) {
        return new PunishmentsHarness(dataDir, role, ApiSettings.builder()
                .baseUrl(baseUrl)
                .guildId("123456789012345678")
                .apiKey("test-secret-key")
                .serverId("survival")
                .timeoutMs(2000)
                .retries(1)
                .retryDelayMs(10)
                .build());
    }

    PunishmentsHarness(Path dataDir, ServerRole role, ApiSettings settings) {
        this.executors = new HeimdallExecutors(logger, 2);
        this.loginPipeline = new LoginPipeline(logger);
        this.chatPipeline = new ChatPipeline(logger);
        this.commandPipeline = new CommandPipeline(logger);
        this.remoteConfig = new RemoteConfig(
                logger, dataDir.resolve("config-cache.json"), ConfigDocument.empty());
        this.platform = new FakePlatform(role, dataDir);
        this.sessions = new PlayerSessionEvents(logger, new java.util.concurrent.Executor() {
            @Override
            public void execute(Runnable command) {
                command.run();
            }
        });
        this.api = new HeimdallApi(new ApiClient(logger, settings, executors.io()));
        this.module = new HeimdallPunishmentsModule();
        this.manager = new ModuleManager(ModuleEnvironment.builder()
                .logger(logger)
                .executors(executors)
                .api(api)
                .tunnel(tunnel)
                .remoteConfig(remoteConfig)
                .loginPipeline(loginPipeline)
                .chatPipeline(chatPipeline)
                .commandPipeline(commandPipeline)
                .platform(platform)
                .playerSessions(sessions)
                .build());
        this.manager.register(module);
    }

    PunishmentsHarness enableReplace() {
        return enableReplace("replace-salt");
    }

    PunishmentsHarness enableReplace(String ipSalt) {
        return enableWith(Payload.builder()
                .put("mode", "replace")
                .put("ipSalt", ipSalt)
                .put("rootAliases", false)
                .put("appealUrl", "https://bans.example/abc")
                .build());
    }

    PunishmentsHarness enableWith(Payload settings) {
        remoteConfig.onConfigPush(Payload.builder()
                .put("version", ++configVersion)
                .put("modules", Payload.builder()
                        .put(HeimdallPunishmentsModule.ID, Payload.builder()
                                .put("enabled", true)
                                .put("settings", settings)
                                .build())
                        .build())
                .build());
        manager.reconcileFromConfig();
        return this;
    }

    /**
     * Turns the module off the way the bot does, with a {@code config.push} that says so.
     *
     * <p>Not {@code manager.shutdown()}: a runtime toggle is the case where a subscription can be
     * left behind, and it is the one the tracked-registration design exists for.
     */
    PunishmentsHarness disableModule() {
        remoteConfig.onConfigPush(Payload.builder()
                .put("version", ++configVersion)
                .put("modules", Payload.builder()
                        .put(HeimdallPunishmentsModule.ID, Payload.builder()
                                .put("enabled", false)
                                .build())
                        .build())
                .build());
        manager.reconcileFromConfig();
        return this;
    }

    Verdict login(UUID uuid, String name, String ip) {
        return loginPipeline.dispatch(LoginAttempt.builder(uuid)
                .username(name)
                .ipAddress(ip)
                .build());
    }

    @Override
    public void close() {
        try {
            manager.shutdown();
        } finally {
            executors.shutdown(2000);
        }
    }
}
