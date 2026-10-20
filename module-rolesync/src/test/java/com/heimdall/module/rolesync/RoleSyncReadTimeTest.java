package com.heimdall.module.rolesync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.json.Payload;
import com.heimdall.core.platform.GroupsChangedListener;
import com.heimdall.core.platform.LuckPermsBridge;
import com.heimdall.core.testing.FakePlayer;
import com.heimdall.core.testing.RecordingTunnelBus;
import com.heimdall.core.util.Registration;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A {@code rolesync.groups} frame's {@code at} is when its list was read from LuckPerms, not when the
 * frame was sent: the bot orders a player's frames by it and weighs them against its own pushes.
 *
 * <p>The fake bridge here calls back inline, so the event and the read are one moment. The real
 * bridge's coalesced, queued read (where they are not) is covered by {@code RoleSyncBridgeReadTimeTest}
 * in {@code :platform-common}, the one project that can see both the module and the bridge.
 */
class RoleSyncReadTimeTest {

    @TempDir
    Path dataDirectory;

    private RoleSyncHarness harness;

    @BeforeEach
    void setUp() {
        harness = new RoleSyncHarness(dataDirectory);
    }

    @AfterEach
    void tearDown() {
        harness.close();
    }

    private List<RecordingTunnelBus.Sent> frames() {
        return harness.bus.sent(GroupChangeReporter.FRAME_TYPE);
    }

    private static void await(BooleanSupplier condition, String what) {
        long deadline = System.currentTimeMillis() + 5_000L;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            sleep(20L);
        }
        assertTrue(condition.getAsBoolean(), what);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("a debounced report's at is the newest read, not the send half a second later")
    void debouncedReportCarriesTheReadTime() {
        harness.configure(RoleSyncHarness.watching("zeal"));
        FakePlayer pio = harness.online("Pio");
        harness.luckPerms.holding(pio.uuid(), "default", "zeal");
        harness.sessions.join(pio, 1L);
        harness.module.groupsDelivered(pio.uuid(), Arrays.asList("default", "zeal"));

        long before = System.currentTimeMillis();
        harness.luckPerms.fireGroupsChanged(pio.uuid(), Arrays.asList("default"));
        long after = System.currentTimeMillis();
        await(() -> frames().size() == 1, "reported: " + harness.logger.records());
        long sentBy = System.currentTimeMillis();

        long at = frames().get(0).payload().longValue("at", 0L);
        assertTrue(at >= before && at <= after,
                "at=" + at + " should be the read, in [" + before + ", " + after + "]");
        assertTrue(sentBy - at >= GroupChangeReporter.DEBOUNCE_MS - 50L,
                "and so well before the send, which waited out the debounce");
    }

    @Test
    @DisplayName("a quit flush carries the read time too, not the quit's")
    void flushCarriesTheReadTime() {
        harness.configure(RoleSyncHarness.watching("zeal"));
        FakePlayer pio = harness.online("Pio");
        harness.luckPerms.holding(pio.uuid(), "default", "zeal");
        harness.sessions.join(pio, 1L);
        harness.module.groupsDelivered(pio.uuid(), Arrays.asList("default", "zeal"));

        long before = System.currentTimeMillis();
        harness.luckPerms.fireGroupsChanged(pio.uuid(), Arrays.asList("default"));
        long after = System.currentTimeMillis();
        sleep(250L);
        harness.platform.leave(pio);
        harness.sessions.quit(pio, 2L);

        assertEquals(1, frames().size());
        long at = frames().get(0).payload().longValue("at", 0L);
        assertTrue(at >= before && at <= after, "at=" + at);
    }

    @Test
    @DisplayName("a resync answer's at is when its LuckPerms read completed")
    void resyncCarriesTheReadTime() {
        final CompletableFuture<List<String>> read = new CompletableFuture<List<String>>();
        harness.withLuckPerms(new LuckPermsBridge() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public CompletableFuture<List<String>> getPlayerGroups(UUID playerUuid) {
                return read;
            }

            @Override
            public CompletableFuture<Boolean> setPlayerGroups(
                    UUID playerUuid, List<String> targetGroups, List<String> managedGroups) {
                return CompletableFuture.completedFuture(Boolean.TRUE);
            }

            @Override
            public Registration onGroupsChanged(GroupsChangedListener listener) {
                return Registration.NONE;
            }
        });
        harness.configure(RoleSyncHarness.watching("zeal"));
        FakePlayer pio = harness.online("Pio");
        harness.sessions.join(pio, 1L);

        long asked = System.currentTimeMillis();
        harness.bus.push(GroupChangeReporter.RESYNC_FRAME_TYPE,
                Payload.builder().put("uuid", pio.uuid().toString()).build());
        sleep(200L);
        long before = System.currentTimeMillis();
        read.complete(Arrays.asList("default", "zeal"));
        long after = System.currentTimeMillis();

        assertEquals(1, frames().size());
        long at = frames().get(0).payload().longValue("at", 0L);
        assertTrue(at >= before && at <= after,
                "at=" + at + " is the read's completion, not the request at " + asked);
    }
}
