package com.heimdall.platform.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.heimdall.core.concurrent.HeimdallExecutors;
import com.heimdall.core.config.ServerRole;
import com.heimdall.core.json.Payload;
import com.heimdall.core.log.RecordingLogger;
import com.heimdall.core.module.ModuleEnvironment;
import com.heimdall.core.module.ModuleManager;
import com.heimdall.core.pipeline.ChatPipeline;
import com.heimdall.core.pipeline.LoginPipeline;
import com.heimdall.core.remoteconfig.ConfigDocument;
import com.heimdall.core.remoteconfig.RemoteConfig;
import com.heimdall.core.session.PlayerSessionEvents;
import com.heimdall.core.testing.FakePlatform;
import com.heimdall.core.testing.FakePlayer;
import com.heimdall.core.testing.RecordingTunnelBus;
import com.heimdall.module.rolesync.HeimdallRoleSyncModule;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.event.EventBus;
import net.luckperms.api.event.EventSubscription;
import net.luckperms.api.event.node.NodeRemoveEvent;
import net.luckperms.api.event.user.UserDataRecalculateEvent;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.types.InheritanceNode;
import net.luckperms.api.query.QueryOptions;
import net.luckperms.api.query.QueryOptionsRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A {@code rolesync.groups} frame's {@code at}, through the real bridge's coalesced async read.
 *
 * <p>{@code RoleSyncReadTimeTest} in {@code :module-rolesync} drives the reporter with a fake bridge
 * that calls back inline, so there the event, the read and the callback are one moment. The real
 * {@link LuckPermsIntegration} queues the read behind the LuckPerms event, coalesces a burst into one
 * read, and calls back only after it ran. That module cannot see this class (it is platform-free), so
 * the case is here: the real module and reporter, over the real bridge, over the mocked LuckPerms API,
 * with the bridge's read queue held until the test lets it run.
 */
class RoleSyncBridgeReadTimeTest {

    private static final String FRAME_TYPE = "rolesync.groups";

    @TempDir
    Path dataDirectory;

    private final RecordingLogger logger = new RecordingLogger(true);
    private final ArrayDeque<Runnable> queued = new ArrayDeque<Runnable>();
    /** The bridge's read queue: runs nothing until {@link #drain}. */
    private final Executor manual = new Executor() {
        @Override
        public synchronized void execute(Runnable command) {
            queued.add(command);
        }
    };
    private final Map<Class<?>, Consumer<Object>> handlers = new HashMap<Class<?>, Consumer<Object>>();
    private final RecordingTunnelBus bus = new RecordingTunnelBus();
    private final HeimdallRoleSyncModule module = new HeimdallRoleSyncModule();
    private HeimdallExecutors executors;
    private FakePlatform platform;
    private PlayerSessionEvents sessions;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        LuckPerms api = mock(LuckPerms.class);
        EventBus events = mock(EventBus.class);
        when(api.getEventBus()).thenReturn(events);
        when(events.subscribe(any(Class.class), any(Consumer.class))).thenAnswer(invocation -> {
            handlers.put((Class<?>) invocation.getArgument(0),
                    (Consumer<Object>) invocation.getArgument(1));
            return mock(EventSubscription.class);
        });
        QueryOptionsRegistry registry = mock(QueryOptionsRegistry.class);
        when(registry.defaultNonContextualOptions()).thenReturn(mock(QueryOptions.class));
        when(api.getQueryOptionsRegistry()).thenReturn(registry);
        provider("register", api);

        LuckPermsIntegration bridge = LuckPermsIntegration.tryCreate(logger, manual);
        assertNotNull(bridge);
        executors = new HeimdallExecutors(logger, 1);
        platform = new FakePlatform(ServerRole.STANDALONE, dataDirectory);
        platform.withLuckPerms(bridge);
        sessions = new PlayerSessionEvents(logger, Runnable::run);
        RemoteConfig remoteConfig = new RemoteConfig(
                logger, dataDirectory.resolve("remote-config.json"), ConfigDocument.empty());
        ModuleManager manager = new ModuleManager(ModuleEnvironment.builder()
                .logger(logger)
                .executors(executors)
                .tunnel(bus)
                .remoteConfig(remoteConfig)
                .loginPipeline(new LoginPipeline(logger))
                .chatPipeline(new ChatPipeline(logger))
                .platform(platform)
                .playerSessions(sessions)
                .build());
        manager.register(module);
        remoteConfig.onConfigPush(Payload.builder()
                .put("version", 1)
                .put("modules", Payload.builder()
                        .put(HeimdallRoleSyncModule.ID, Payload.builder()
                                .put("enabled", true)
                                .put("settings", Payload.builder()
                                        .putStrings("watchedGroups", Arrays.asList("zeal"))
                                        .build())
                                .build())
                        .build())
                .build());
        manager.reconcileFromConfig();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (executors != null) {
            executors.shutdown(1000);
        }
        provider("unregister", null);
    }

    private static void provider(String name, LuckPerms instance) throws Exception {
        Method method = instance == null
                ? LuckPermsProvider.class.getDeclaredMethod(name)
                : LuckPermsProvider.class.getDeclaredMethod(name, LuckPerms.class);
        method.setAccessible(true);
        if (instance == null) {
            method.invoke(null);
        } else {
            method.invoke(null, instance);
        }
    }

    private void drain() {
        while (true) {
            Runnable next;
            synchronized (manual) {
                next = queued.poll();
            }
            if (next == null) {
                return;
            }
            next.run();
        }
    }

    private static User user(UUID uuid, String name, String... groups) {
        User user = mock(User.class);
        when(user.getUniqueId()).thenReturn(uuid);
        when(user.getUsername()).thenReturn(name);
        List<Group> held = new ArrayList<Group>();
        for (String group : groups) {
            Group g = mock(Group.class);
            when(g.getName()).thenReturn(group);
            held.add(g);
        }
        when(user.getInheritedGroups(any())).thenReturn(held);
        return user;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("at is when the bridge's queued read ran, not when the LuckPerms event fired")
    void atIsTheCoalescedRead() {
        assertTrue(handlers.containsKey(NodeRemoveEvent.class),
                "the reporter subscribed through the real bridge: " + logger.records());
        FakePlayer pio = platform.join(FakePlayer.named("Pio"));
        sessions.join(pio, 1L);
        module.groupsDelivered(pio.uuid(), Arrays.asList("default", "zeal"));

        // zeal is removed: the node event, then the recalculation LuckPerms fires behind it.
        User after = user(pio.uuid(), "pio", "default");
        NodeRemoveEvent removed = mock(NodeRemoveEvent.class);
        InheritanceNode zeal = mock(InheritanceNode.class);
        when(zeal.getGroupName()).thenReturn("zeal");
        when(removed.getNode()).thenReturn(zeal);
        when(removed.isUser()).thenReturn(true);
        when(removed.getTarget()).thenReturn(after);
        UserDataRecalculateEvent recalculated = mock(UserDataRecalculateEvent.class);
        when(recalculated.getUser()).thenReturn(after);

        long firedAt = System.currentTimeMillis();
        handlers.get(NodeRemoveEvent.class).accept(removed);
        handlers.get(UserDataRecalculateEvent.class).accept(recalculated);
        assertEquals(1, queued.size(), "one read queued for the burst, not one per event");

        sleep(300L);
        long readFrom = System.currentTimeMillis();
        drain();
        long readTo = System.currentTimeMillis();

        long deadline = System.currentTimeMillis() + 5_000L;
        while (bus.sent(FRAME_TYPE).isEmpty() && System.currentTimeMillis() < deadline) {
            sleep(20L);
        }
        assertEquals(1, bus.sent(FRAME_TYPE).size(), logger.records().toString());
        Payload frame = bus.sent(FRAME_TYPE).get(0).payload();
        assertEquals(Arrays.asList("default"), frame.strings("groups"));
        assertEquals("Pio", frame.string("username", null), "the online name wins");
        long at = frame.longValue("at", 0L);
        assertTrue(at >= readFrom && at <= readTo,
                "at=" + at + " is the queued read, in [" + readFrom + ", " + readTo + "]");
        assertTrue(at - firedAt >= 300L, "and not the event, " + (at - firedAt) + "ms earlier");
    }
}
