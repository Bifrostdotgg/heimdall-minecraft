package com.heimdall.module.rolesync;

import com.heimdall.core.concurrent.HeimdallExecutors;
import com.heimdall.core.config.ServerRole;
import com.heimdall.core.json.Payload;
import com.heimdall.core.log.LogLevel;
import com.heimdall.core.log.RecordingLogger;
import com.heimdall.core.module.ModuleEnvironment;
import com.heimdall.core.module.ModuleManager;
import com.heimdall.core.pipeline.ChatPipeline;
import com.heimdall.core.pipeline.LoginPipeline;
import com.heimdall.core.platform.LuckPermsBridge;
import com.heimdall.core.remoteconfig.ConfigDocument;
import com.heimdall.core.remoteconfig.RemoteConfig;
import com.heimdall.core.session.PlayerSessionEvents;
import com.heimdall.core.testing.FakeLuckPerms;
import com.heimdall.core.testing.FakePlatform;
import com.heimdall.core.testing.FakePlayer;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * The module, wired the way {@code ModuleManager} wires it, with everything else faked.
 *
 * <p>Deliberately goes through the <em>real</em> {@link ModuleManager} rather than calling
 * {@code enable(context)} with a hand-rolled context. Two reasons: {@code ModuleContextImpl} is
 * package-private in core, so there is no hand-rolled context to build; and the tracked-registration
 * unwinding that makes disable reliable is the manager's, so a test that skipped it would prove
 * nothing about the case it most needs to — enable, disable, enable.
 *
 * <p>One instance per test. {@link #close()} shuts the executors down.
 */
final class RoleSyncHarness implements AutoCloseable {

    final RecordingLogger logger = new RecordingLogger(true);
    final RecordingTunnelBus bus = new RecordingTunnelBus();
    final FakeLuckPerms luckPerms = new FakeLuckPerms();
    final FakePlatform platform;
    final HeimdallRoleSyncModule module = new HeimdallRoleSyncModule();

    private final HeimdallExecutors executors;
    private final ModuleManager manager;

    RoleSyncHarness(Path dataDirectory) {
        this.executors = new HeimdallExecutors(logger, 1);
        this.platform = new FakePlatform(ServerRole.STANDALONE, dataDirectory);
        this.platform.withLuckPerms(luckPerms);
        RemoteConfig remoteConfig = new RemoteConfig(
                logger, dataDirectory.resolve("remote-config.json"), ConfigDocument.empty());
        this.manager = new ModuleManager(ModuleEnvironment.builder()
                .logger(logger)
                .executors(executors)
                .tunnel(bus)
                .remoteConfig(remoteConfig)
                .loginPipeline(new LoginPipeline(logger))
                .chatPipeline(new ChatPipeline(logger))
                .platform(platform)
                // Same-thread, so a test needs no latch. The real dispatcher's off-the-event-thread
                // behaviour is core's to prove, not this module's.
                .playerSessions(new PlayerSessionEvents(logger, Runnable::run))
                .build());
        this.manager.register(module);
    }

    /** Hands the platform a different bridge — or, with {@code null}, no LuckPerms at all. */
    RoleSyncHarness withLuckPerms(LuckPermsBridge bridge) {
        platform.withLuckPerms(bridge);
        return this;
    }

    /** Makes the server scheduler queue rather than run inline, so the defer is observable. */
    RoleSyncHarness deferring() {
        platform.deferringLaterTasks();
        return this;
    }

    /** Puts a player online so the username fallback can find them. */
    FakePlayer online(String name) {
        return platform.join(FakePlayer.named(name));
    }

    /** Turns the module on, as a config push would. */
    RoleSyncHarness enable() {
        manager.reconcile(Collections.singleton(HeimdallRoleSyncModule.ID));
        return this;
    }

    /** Turns it off again. */
    RoleSyncHarness disable() {
        manager.reconcile(Collections.<String>emptySet());
        return this;
    }

    ModuleManager manager() {
        return manager;
    }

    /** Pushes a {@code role_sync} frame; returns how many handlers saw it. */
    int pushRoleSync(Payload payload) {
        return bus.push(RoleSyncPushHandler.MESSAGE_TYPE, payload);
    }

    /** How many handlers are listening for {@code role_sync}. */
    int roleSyncSubscribers() {
        return bus.subscriberCount(RoleSyncPushHandler.MESSAGE_TYPE);
    }

    /** Every sync LuckPerms was asked for, oldest first. */
    List<FakeLuckPerms.Sync> syncs() {
        return luckPerms.syncs();
    }

    /** How many log lines at {@code level} contain {@code needle}. */
    int countLogged(LogLevel level, String needle) {
        int found = 0;
        for (String message : logger.messagesAt(level)) {
            if (message != null && message.contains(needle)) {
                found++;
            }
        }
        return found;
    }

    /** A {@code role_sync} frame body. */
    static Payload frame(String uuid, String username, List<String> target, List<String> managed) {
        Payload.Builder builder = Payload.builder();
        if (uuid != null) {
            builder.put("uuid", uuid);
        }
        if (username != null) {
            builder.put("username", username);
        }
        return builder
                .putStrings("targetGroups", target)
                .putStrings("managedGroups", managed)
                .putStrings("groupsAdded", Collections.<String>emptyList())
                .putStrings("groupsRemoved", Collections.<String>emptyList())
                .build();
    }

    /** A UUID derived from a name, matching what {@link FakePlayer#named} produces. */
    static UUID uuidOf(String name) {
        return FakePlatform.uuidFor(name);
    }

    @Override
    public void close() {
        executors.shutdown(1000);
    }
}
