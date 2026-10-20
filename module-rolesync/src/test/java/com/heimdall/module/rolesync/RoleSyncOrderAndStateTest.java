package com.heimdall.module.rolesync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.http.model.RoleSyncDirective;
import com.heimdall.core.json.Payload;
import com.heimdall.core.platform.GroupsChangedListener;
import com.heimdall.core.platform.LuckPermsBridge;
import com.heimdall.core.testing.FakePlayer;
import com.heimdall.core.util.Registration;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Ordering and state fixes: pushes applied one at a time per player, the RCON flag forgotten when
 * the config says websocket again, and reverse role sync resubscribing after a LuckPerms reload.
 */
class RoleSyncOrderAndStateTest {

    @TempDir
    Path dataDirectory;

    private RoleSyncHarness harness;

    @AfterEach
    void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    /** A bridge whose writes finish only when the test says so. */
    private static final class HeldBridge implements LuckPermsBridge {
        final List<List<String>> started = new CopyOnWriteArrayList<List<String>>();
        final List<CompletableFuture<Boolean>> writes =
                new CopyOnWriteArrayList<CompletableFuture<Boolean>>();
        /** Thrown by the next {@link #isAvailable} call, once, then cleared. */
        volatile Throwable nextAvailabilityCheckThrows;

        @Override
        public boolean isAvailable() {
            Throwable broken = nextAvailabilityCheckThrows;
            if (broken != null) {
                nextAvailabilityCheckThrows = null;
                if (broken instanceof Error) {
                    throw (Error) broken;
                }
                throw (RuntimeException) broken;
            }
            return true;
        }

        @Override
        public CompletableFuture<List<String>> getPlayerGroups(UUID playerUuid) {
            return CompletableFuture.completedFuture(Collections.<String>emptyList());
        }

        @Override
        public CompletableFuture<Boolean> setPlayerGroups(
                UUID playerUuid, List<String> targetGroups, List<String> managedGroups) {
            started.add(new ArrayList<String>(targetGroups));
            CompletableFuture<Boolean> write = new CompletableFuture<Boolean>();
            writes.add(write);
            return write;
        }

        @Override
        public Registration onGroupsChanged(GroupsChangedListener listener) {
            return Registration.NONE;
        }
    }

    private static List<String> groups(String... names) {
        return Arrays.asList(names);
    }

    private void push(String name, List<String> target) {
        harness.pushRoleSync(RoleSyncHarness.frame(
                RoleSyncHarness.uuidOf(name).toString(), name, target, groups("vip", "mvp")));
    }

    private static void await(BooleanSupplier condition, String what) {
        long deadline = System.currentTimeMillis() + 5_000L;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(20L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        assertTrue(condition.getAsBoolean(), what);
    }

    @Test
    @DisplayName("a second push for a player waits for the first one's LuckPerms write to finish")
    void pushesForOnePlayerAreSerial() {
        HeldBridge bridge = new HeldBridge();
        harness = new RoleSyncHarness(dataDirectory).withLuckPerms(bridge).enable();

        push("Steve", groups("vip"));
        push("Steve", groups("mvp"));
        push("Alex", groups("vip"));

        assertEquals(Arrays.asList(groups("vip"), groups("vip")), bridge.started,
                "Steve's second write waits; Alex has nothing in front of him");
        bridge.writes.get(0).complete(Boolean.TRUE);
        assertEquals(3, bridge.started.size());
        assertEquals(groups("mvp"), bridge.started.get(2), "then Steve's newer snapshot, last");

        bridge.writes.get(1).complete(Boolean.TRUE);
        bridge.writes.get(2).complete(Boolean.TRUE);
        assertEquals(0, harness.module.applier().outstandingWrites(), "the chain empties");
    }

    @Test
    @DisplayName("a write that fails still lets the next one for that player run")
    void failedWriteReleasesTheChain() {
        HeldBridge bridge = new HeldBridge();
        harness = new RoleSyncHarness(dataDirectory).withLuckPerms(bridge).enable();

        push("Steve", groups("vip"));
        push("Steve", groups("mvp"));
        bridge.writes.get(0).completeExceptionally(new IllegalStateException("storage down"));

        assertEquals(2, bridge.started.size());
        bridge.writes.get(1).complete(Boolean.TRUE);
        assertEquals(0, harness.module.applier().outstandingWrites());
    }

    @Test
    @DisplayName("a bridge whose availability check throws still releases that player's chain")
    void availabilityThrowReleasesTheChain() {
        HeldBridge bridge = new HeldBridge();
        harness = new RoleSyncHarness(dataDirectory).withLuckPerms(bridge).enable();

        bridge.nextAvailabilityCheckThrows = new IllegalStateException("LuckPerms half-loaded");
        push("Steve", groups("vip"));
        assertEquals(0, harness.module.applier().outstandingWrites(),
                "the throw came before setPlayerGroups, and the chain entry must not outlive it");

        push("Steve", groups("mvp"));
        assertEquals(Arrays.asList(groups("mvp")), bridge.started,
                "the next push for Steve is not stuck behind a write that never finished");
        bridge.writes.get(0).complete(Boolean.TRUE);
        assertEquals(0, harness.module.applier().outstandingWrites());
    }

    @Test
    @DisplayName("an Error from the availability check propagates, but the chain is released first")
    void availabilityErrorReleasesTheChain() {
        HeldBridge bridge = new HeldBridge();
        harness = new RoleSyncHarness(dataDirectory).withLuckPerms(bridge).enable();

        bridge.nextAvailabilityCheckThrows = new NoClassDefFoundError("net/luckperms/api/LuckPerms");
        org.junit.jupiter.api.Assertions.assertThrows(NoClassDefFoundError.class,
                () -> push("Steve", groups("vip")),
                "inline here; on the real dispatch path the serial lane catches it");
        assertEquals(0, harness.module.applier().outstandingWrites());

        push("Steve", groups("mvp"));
        assertEquals(Arrays.asList(groups("mvp")), bridge.started);
    }

    @Test
    @DisplayName("a task that throws an Error does not stall the serial lane")
    void serialLaneSurvivesAnError() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            com.heimdall.core.log.RecordingLogger logged = new com.heimdall.core.log.RecordingLogger();
            SerialLane lane = new SerialLane(pool, logged);
            final CountDownLatch behind = new CountDownLatch(1);
            final CountDownLatch later = new CountDownLatch(1);
            lane.execute(() -> {
                throw new StackOverflowError("a task's own failure");
            });
            lane.execute(behind::countDown);
            assertTrue(behind.await(5, TimeUnit.SECONDS), "the task queued behind the Error ran");
            // And a task submitted after the lane went idle starts a new drain: draining was reset.
            lane.execute(later::countDown);
            assertTrue(later.await(5, TimeUnit.SECONDS),
                    "a lane left with draining=true would never run this");
            assertTrue(logged.records().toString().contains("StackOverflowError"), "and it was logged");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("role_sync is dispatched through a serial lane, not straight onto the IO pool")
    void subscribedThroughASerialLane() {
        harness = new RoleSyncHarness(dataDirectory).enable();
        assertTrue(harness.bus.subscribedExecutor(RoleSyncPushHandler.MESSAGE_TYPE)
                instanceof SerialLane);
    }

    @Test
    @DisplayName("a serial lane over a multi-thread pool runs tasks one at a time, in order")
    void serialLaneKeepsOrder() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            SerialLane lane = new SerialLane(pool, new com.heimdall.core.log.RecordingLogger());
            final List<Integer> ran = Collections.synchronizedList(new ArrayList<Integer>());
            final CountDownLatch done = new CountDownLatch(200);
            for (int i = 0; i < 200; i++) {
                final int n = i;
                lane.execute(() -> {
                    ran.add(n);
                    done.countDown();
                });
            }
            assertTrue(done.await(5, TimeUnit.SECONDS));
            List<Integer> expected = new ArrayList<Integer>();
            for (int i = 0; i < 200; i++) {
                expected.add(i);
            }
            assertEquals(expected, ran);
        } finally {
            pool.shutdownNow();
        }
    }

    private static Payload mode(String value) {
        return Payload.builder().put(HeimdallRoleSyncModule.MODE, value).build();
    }

    @Test
    @DisplayName("the config switching mode back to websocket forgets 'the bot uses RCON'")
    void websocketModeResetsTheRconFlag() {
        harness = new RoleSyncHarness(dataDirectory);
        harness.configure(mode("rcon"));
        harness.module.applyOnJoin(
                RoleSyncHarness.uuidOf("Steve"), "Steve", RoleSyncDirective.disabled());
        push("Steve", groups("vip"));
        assertTrue(harness.syncs().isEmpty(), "ignored while the bot drives LuckPerms over RCON");

        harness.configure(mode("websocket"));
        assertFalse(harness.module.applier().ignoringPushes());
        push("Steve", groups("vip"));
        assertEquals(1, harness.syncs().size(), "honoured again without waiting for a login");
    }

    @Test
    @DisplayName("a disabled answer requested before the switch to websocket does not undo the reset")
    void staleDisabledAnswerIgnored() {
        harness = new RoleSyncHarness(dataDirectory);
        UUID steve = RoleSyncHarness.uuidOf("Steve");
        harness.configure(mode("rcon"));
        assertTrue(harness.module.applier().ignoringPushes(),
                "enabled straight into rcon: the bot said so, before any login");

        long asked = harness.module.groupsRequestStarting(steve);
        harness.configure(mode("websocket"));
        // The bot answered while it still drove LuckPerms over RCON.
        harness.module.applyOnJoin(steve, "Steve", RoleSyncDirective.disabled(), asked);
        assertFalse(harness.module.applier().ignoringPushes(),
                "the answer is older than the switch, so it does not put the flag back");
        push("Steve", groups("vip"));
        assertEquals(1, harness.syncs().size());

        // One requested after the switch is the bot's current word, and is remembered.
        long fresh = harness.module.groupsRequestStarting(steve);
        harness.module.applyOnJoin(steve, "Steve", RoleSyncDirective.disabled(), fresh);
        assertTrue(harness.module.applier().ignoringPushes());
    }

    @Test
    @DisplayName("the config switching to rcon sets the flag, and an older enabled answer cannot clear it")
    void rconSwitchSetsTheFlag() {
        harness = new RoleSyncHarness(dataDirectory).deferring();
        UUID steve = RoleSyncHarness.uuidOf("Steve");
        harness.configure(mode("websocket"));
        assertFalse(harness.module.applier().ignoringPushes());

        long asked = harness.module.groupsRequestStarting(steve);
        harness.configure(mode("rcon"));
        assertTrue(harness.module.applier().ignoringPushes(), "without waiting for a login");
        push("Steve", groups("vip"));
        assertTrue(harness.syncs().isEmpty());

        harness.module.applyOnJoin(steve, "Steve",
                RoleSyncDirective.enabled(groups("vip"), groups("vip", "mvp")), asked);
        assertTrue(harness.module.applier().ignoringPushes());
        harness.platform.runDeferred();
        assertTrue(harness.syncs().isEmpty(), "and its groups are not written over the bot's RCON");
    }

    @Test
    @DisplayName("an unrelated config change while already websocket does not override a newer directive")
    void stayingOnWebsocketKeepsTheFlag() {
        harness = new RoleSyncHarness(dataDirectory);
        harness.configure(mode("websocket"));
        harness.module.applyOnJoin(
                RoleSyncHarness.uuidOf("Steve"), "Steve", RoleSyncDirective.disabled());

        harness.configure(Payload.builder()
                .put(HeimdallRoleSyncModule.MODE, "websocket")
                .putStrings(GroupChangeReporter.WATCHED_GROUPS, groups("vip"))
                .build());

        assertTrue(harness.module.applier().ignoringPushes(),
                "only a switch back to websocket is the bot saying so; the directive is newer");
    }

    @Test
    @DisplayName("after a LuckPerms reload, the next join subscribes again and reporting resumes")
    void resubscribesAfterALuckPermsReload() {
        harness = new RoleSyncHarness(dataDirectory);
        harness.configure(RoleSyncHarness.watching("vip"));
        assertEquals(1, harness.luckPerms.groupListenerCount());

        harness.luckPerms.reloaded();
        assertEquals(0, harness.luckPerms.groupListenerCount(), "the old event bus died with it");

        FakePlayer steve = harness.online("Steve");
        harness.luckPerms.holding(steve.uuid(), "default");
        harness.sessions.join(steve, 1L);
        assertEquals(1, harness.luckPerms.groupListenerCount(),
                "refresh used to stop at 'subscription != null' and never look again");

        harness.luckPerms.fireGroupsChanged(steve.uuid(), groups("default", "vip"));
        await(() -> harness.bus.sent(GroupChangeReporter.FRAME_TYPE).size() == 1,
                "reporting works on the new instance: " + harness.logger.records());
    }

    @Test
    @DisplayName("without a reload, a join does not subscribe a second time")
    void noReloadNoResubscribe() {
        harness = new RoleSyncHarness(dataDirectory);
        harness.configure(RoleSyncHarness.watching("vip"));
        FakePlayer steve = harness.online("Steve");
        harness.sessions.join(steve, 1L);
        harness.sessions.join(steve, 2L);
        assertEquals(1, harness.luckPerms.groupListenerCount());
    }
}
