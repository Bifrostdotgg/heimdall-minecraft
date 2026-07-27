package com.heimdall.module.whitelist;

import com.heimdall.core.concurrent.HeimdallExecutors;
import com.heimdall.core.config.ServerRole;
import com.heimdall.core.http.ApiClient;
import com.heimdall.core.http.ApiSettings;
import com.heimdall.core.http.model.RoleSyncDirective;
import com.heimdall.core.json.Payload;
import com.heimdall.core.log.RecordingLogger;
import com.heimdall.core.module.ModuleEnvironment;
import com.heimdall.core.module.ModuleManager;
import com.heimdall.core.pipeline.ChatPipeline;
import com.heimdall.core.pipeline.LoginAttempt;
import com.heimdall.core.pipeline.LoginPipeline;
import com.heimdall.core.pipeline.Verdict;
import com.heimdall.core.remoteconfig.ConfigDocument;
import com.heimdall.core.remoteconfig.RemoteConfig;
import com.heimdall.core.roles.RoleSyncSink;
import com.heimdall.core.session.PlayerSessionEvents;
import com.heimdall.core.testing.FakePlatform;
import com.heimdall.core.testing.RecordingTunnelBus;
import com.heimdall.stubbot.StubBot;
import com.heimdall.stubbot.StubBotConfig;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A whitelist module wired to a real {@link StubBot}, a real {@link ModuleManager}, and fakes.
 *
 * <h2>Why a real manager and a real stub</h2>
 *
 * <p>The manager, because half of what these tests assert is what happens <em>around</em> the module
 * — that a toggle unwinds the interceptor, the session listeners and the command — and a hand-rolled
 * {@code ModuleContext} would be a second implementation of the tracking the assertions depend on.
 *
 * <p>The stub, because the six connection-attempt outcomes are the part most likely to be got wrong
 * and a hand-written fixture would only agree with whatever this repo believes about them. The stub
 * is a transcription of the bot's own handlers, and it verifies the HMAC the way the bot does.
 *
 * <p>Each harness gets its own stub on an ephemeral port and its own temp directory, so fixture
 * mutations cannot leak between tests.
 */
final class WhitelistHarness implements AutoCloseable {

    /** Demo fixtures, from {@code stub-bot/README.md}'s player table. */
    static final String ALLOWED = "11111111-1111-1111-1111-111111111111";
    static final String DENIED = "22222222-2222-2222-2222-222222222222";
    static final String PENDING_AUTH = "33333333-3333-3333-3333-333333333333";
    static final String REVOKED = "44444444-4444-4444-4444-444444444444";
    static final String QUEUED = "55555555-5555-5555-5555-555555555555";
    static final String EXISTING_LINK = "66666666-6666-6666-6666-666666666666";
    static final String SCHEDULED = "77777777-7777-7777-7777-777777777777";

    final RecordingLogger logger = new RecordingLogger(true);
    final StubBot bot;
    final HeimdallExecutors executors;
    final LoginPipeline loginPipeline;
    final ChatPipeline chatPipeline;
    final RemoteConfig remoteConfig;
    final FakePlatform platform;
    final PlayerSessionEvents sessions;
    final RecordingTunnelBus tunnel = new RecordingTunnelBus();
    final ModuleManager manager;
    final HeimdallWhitelistModule module;
    final RecordingRoleSync roleSync = new RecordingRoleSync();

    /**
     * The version each push carries, incremented every time.
     *
     * <p>Not a detail: {@code RemoteConfig} drops a push whose version it has already seen, equal
     * versions included (departure D29). A harness that pushed version 1 twice would silently apply
     * only the first, and every toggle test after the first would be asserting against a module
     * nobody had actually reconfigured.
     */
    private int configVersion;

    private WhitelistHarness(Path dataDir, ServerRole role, boolean withApi) {
        this.bot = StubBot.start(StubBotConfig.withDemoFixtures().bindHost("127.0.0.1").port(0));
        this.executors = new HeimdallExecutors(logger, 2);
        this.loginPipeline = new LoginPipeline(logger);
        this.chatPipeline = new ChatPipeline(logger);
        this.remoteConfig = new RemoteConfig(
                logger, dataDir.resolve("config-cache.json"), ConfigDocument.empty());
        this.platform = new FakePlatform(role, dataDir);
        // Same-thread, so a join can be pushed and asserted without a latch. The off-thread dispatch
        // is core's property and is pinned in core's own tests.
        this.sessions = new PlayerSessionEvents(logger, Runnable::run);

        ApiClient api = withApi
                ? new ApiClient(logger, ApiSettings.builder()
                        .baseUrl(bot.baseUrl())
                        .guildId(StubBotConfig.DEFAULT_GUILD_ID)
                        .apiKey(StubBotConfig.DEFAULT_API_KEY)
                        .serverId("survival")
                        .timeoutMs(4000)
                        .retries(1)
                        .retryDelayMs(20)
                        .build(), executors.io())
                : null;

        this.module = new HeimdallWhitelistModule(api);
        this.module.setRoleSyncSink(roleSync);
        this.manager = new ModuleManager(ModuleEnvironment.builder()
                .logger(logger)
                .executors(executors)
                .tunnel(tunnel)
                .remoteConfig(remoteConfig)
                .loginPipeline(loginPipeline)
                .chatPipeline(chatPipeline)
                .platform(platform)
                .playerSessions(sessions)
                .build());
        this.manager.register(module);
    }

    static WhitelistHarness standalone(Path dataDir) {
        return new WhitelistHarness(dataDir, ServerRole.STANDALONE, true);
    }

    static WhitelistHarness withRole(Path dataDir, ServerRole role) {
        return new WhitelistHarness(dataDir, role, true);
    }

    /** A harness whose API client is {@code null} — a server that was never set up. */
    static WhitelistHarness unconfigured(Path dataDir) {
        return new WhitelistHarness(dataDir, ServerRole.STANDALONE, false);
    }

    /** Enables the module with the given settings, as a {@code config.push} would. */
    WhitelistHarness enableWith(Payload settings) {
        remoteConfig.onConfigPush(Payload.builder()
                .put("version", ++configVersion)
                .put("modules", Payload.builder()
                        .put(HeimdallWhitelistModule.ID, Payload.builder()
                                .put("enabled", true)
                                .put("settings", settings)
                                .build())
                        .build())
                .build());
        manager.reconcileFromConfig();
        return this;
    }

    /** Switches the module off, as a dashboard toggle would. */
    void disableModule() {
        remoteConfig.onConfigPush(Payload.builder()
                .put("version", ++configVersion)
                .put("modules", Payload.builder()
                        .put(HeimdallWhitelistModule.ID, Payload.builder()
                                .put("enabled", false)
                                .build())
                        .build())
                .build());
        manager.reconcileFromConfig();
    }

    /**
     * Waits for the mirror to hold {@code expected} entries.
     *
     * <p>The pre-warm poll is scheduled with a zero initial delay, so it runs on
     * {@code heimdall-sched} rather than inline on {@code enable()} — which is correct (a module's
     * enable blocks a config push and must be quick) and means a test asserting on its result has to
     * wait for it. A bounded poll rather than a sleep: on a loaded CI runner a fixed sleep is either
     * flaky or slow, and this is neither.
     *
     * @return the mirror's stats line, so a failure message can say what it actually held
     */
    String awaitMirrorEntries(int expected) {
        long deadline = System.currentTimeMillis() + 15_000L;
        String stats = module.mirrorStats();
        while (!stats.startsWith(expected + " entries") && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(20L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
            stats = module.mirrorStats();
        }
        return stats;
    }

    /**
     * Puts an entry in the mirror without going through the bot.
     *
     * <p>For the states a bot cannot produce because there is not one — a server that restarted
     * during an outage still holding a warm mirror, which is the case the whole pre-warm design
     * exists for and the one an unconfigured harness cannot otherwise reach.
     */
    void seedMirror(String uuid, String username) {
        module.recordForTest(UUID.fromString(uuid), username);
    }

    /** Runs a login through the pipeline, exactly as a platform listener would. */
    Verdict login(String uuid, String username) {
        return loginPipeline.dispatch(LoginAttempt.builder(UUID.fromString(uuid))
                .username(username)
                .ipAddress("203.0.113.7")
                .build());
    }

    /** Points the client at a port nothing is listening on, without stopping the stub. */
    void breakTheBot() {
        bot.close();
    }

    @Override
    public void close() {
        try {
            manager.shutdown();
        } finally {
            executors.shutdown(2000);
            bot.close();
        }
    }

    /** Records what the whitelist module handed to role sync, and nothing else. */
    static final class RecordingRoleSync implements RoleSyncSink {

        private final List<String> applied = new CopyOnWriteArrayList<String>();

        @Override
        public void applyOnJoin(UUID playerUuid, String username, RoleSyncDirective directive) {
            applied.add(username + ":" + describe(directive));
        }

        List<String> applied() {
            return Collections.unmodifiableList(new ArrayList<String>(applied));
        }

        void clear() {
            applied.clear();
        }

        private static String describe(RoleSyncDirective directive) {
            if (directive == null || !directive.isPresent()) {
                return "absent";
            }
            if (!directive.isEnabled()) {
                return "disabled";
            }
            return "enabled" + directive.targetGroups();
        }
    }
}
