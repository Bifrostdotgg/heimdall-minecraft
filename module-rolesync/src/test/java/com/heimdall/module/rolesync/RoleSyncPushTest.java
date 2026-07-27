package com.heimdall.module.rolesync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.http.model.RoleSyncDirective;
import com.heimdall.core.json.Payload;
import com.heimdall.core.log.LogLevel;
import com.heimdall.core.platform.LuckPermsBridge;
import com.heimdall.core.testing.FakeLuckPerms;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The {@code role_sync} frame: resolving who it names, and containing everything that can go wrong.
 *
 * <p>The frame is broadcast to every server in a guild, so "this is about somebody who is not here"
 * is the ordinary case rather than a fault — which is why the unresolvable-player test asserts a
 * single warning and no exception rather than anything louder.
 */
class RoleSyncPushTest {

    private static final List<String> TARGET = Arrays.asList("vip");
    private static final List<String> MANAGED = Arrays.asList("vip", "mod");

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

    @Test
    @DisplayName("a push carrying a UUID syncs that player, with no defer")
    void pushWithUuidSyncs() {
        harness.deferring().enable();
        UUID uuid = RoleSyncHarness.uuidOf("Steve");

        assertEquals(1, harness.pushRoleSync(
                RoleSyncHarness.frame(uuid.toString(), "Steve", TARGET, MANAGED)));

        assertEquals(0, harness.platform.deferredCount(),
                "a push is about somebody already on (or not on) the server; there is no join to wait for");
        List<FakeLuckPerms.Sync> syncs = harness.syncs();
        assertEquals(1, syncs.size(), "" + syncs);
        assertEquals(uuid, syncs.get(0).uuid());
        assertEquals(TARGET, syncs.get(0).targetGroups());
        assertEquals(MANAGED, syncs.get(0).managedGroups());
    }

    @Test
    @DisplayName("a push with no UUID falls back to the online username")
    void pushWithoutUuidResolvesTheUsername() {
        harness.enable();
        UUID uuid = harness.online("Steve").uuid();

        harness.pushRoleSync(RoleSyncHarness.frame(null, "Steve", TARGET, MANAGED));

        assertEquals(1, harness.syncs().size());
        assertEquals(uuid, harness.syncs().get(0).uuid());
    }

    @Test
    @DisplayName("the username lookup is case-insensitive, as PlayerDirectory promises")
    void usernameFallbackIgnoresCase() {
        harness.enable();
        UUID uuid = harness.online("Steve").uuid();

        harness.pushRoleSync(RoleSyncHarness.frame(null, "sTeVe", TARGET, MANAGED));

        assertEquals(1, harness.syncs().size());
        assertEquals(uuid, harness.syncs().get(0).uuid());
    }

    @Test
    @DisplayName("a malformed UUID falls through to the username rather than failing the frame")
    void malformedUuidFallsBackToTheUsername() {
        harness.enable();
        UUID uuid = harness.online("Steve").uuid();

        assertDoesNotThrow(() -> harness.pushRoleSync(
                RoleSyncHarness.frame("not-a-uuid", "Steve", TARGET, MANAGED)));

        assertEquals(1, harness.syncs().size());
        assertEquals(uuid, harness.syncs().get(0).uuid());
    }

    @Test
    @DisplayName("a push for a player nobody can resolve is one warning and no exception")
    void pushForAnUnknownPlayerIsIgnored() {
        harness.enable();

        assertDoesNotThrow(() -> harness.pushRoleSync(
                RoleSyncHarness.frame(null, "Nobody", TARGET, MANAGED)));

        assertTrue(harness.syncs().isEmpty());
        assertEquals(1, harness.countLogged(LogLevel.WARN, "no player to apply it to"));
    }

    @Test
    @DisplayName("a push with an empty managed list changes nothing")
    void pushWithEmptyManagedListChangesNothing() {
        harness.enable();

        harness.pushRoleSync(RoleSyncHarness.frame(
                RoleSyncHarness.uuidOf("Steve").toString(), "Steve", TARGET,
                Collections.<String>emptyList()));

        assertTrue(harness.syncs().isEmpty());
    }

    @Test
    @DisplayName("with no LuckPerms installed, the absence is logged once across many events")
    void luckPermsAbsenceIsLoggedOnce() {
        harness.withLuckPerms(null).enable();
        UUID uuid = RoleSyncHarness.uuidOf("Steve");

        assertDoesNotThrow(() -> {
            harness.pushRoleSync(RoleSyncHarness.frame(uuid.toString(), "Steve", TARGET, MANAGED));
            harness.pushRoleSync(RoleSyncHarness.frame(uuid.toString(), "Steve", TARGET, MANAGED));
            harness.pushRoleSync(RoleSyncHarness.frame(uuid.toString(), "Steve", TARGET, MANAGED));
            harness.module.applyOnJoin(uuid, "Steve", RoleSyncDirective.enabled(TARGET, MANAGED));
        });

        assertEquals(1, harness.countLogged(LogLevel.WARN, "LuckPerms is not available"),
                "four events, one line — a broadcast frame per Discord role change would otherwise "
                        + "fill the log of every server without LuckPerms");
    }

    @Test
    @DisplayName("a LuckPerms that is present but has not registered its service is the same case")
    void unavailableBridgeIsAlsoLoggedOnce() {
        harness.withLuckPerms(new FakeLuckPerms().unavailable()).enable();
        UUID uuid = RoleSyncHarness.uuidOf("Steve");

        harness.pushRoleSync(RoleSyncHarness.frame(uuid.toString(), "Steve", TARGET, MANAGED));
        harness.pushRoleSync(RoleSyncHarness.frame(uuid.toString(), "Steve", TARGET, MANAGED));

        assertEquals(1, harness.countLogged(LogLevel.WARN, "LuckPerms is not available"));
    }

    @Test
    @DisplayName("a bridge whose future fails is contained and reported, and nothing throws at the bus")
    void aFailingFutureIsContained() {
        harness.withLuckPerms(new FakeLuckPerms().failing(new IllegalStateException("storage down")))
                .enable();

        assertDoesNotThrow(() -> harness.pushRoleSync(RoleSyncHarness.frame(
                RoleSyncHarness.uuidOf("Steve").toString(), "Steve", TARGET, MANAGED)));

        assertEquals(1, harness.countLogged(LogLevel.SEVERE, "failed inside LuckPerms"));
    }

    @Test
    @DisplayName("a bridge that throws outright is contained too")
    void aThrowingBridgeIsContained() {
        harness.withLuckPerms(new ThrowingLuckPerms()).enable();

        assertDoesNotThrow(() -> harness.pushRoleSync(RoleSyncHarness.frame(
                RoleSyncHarness.uuidOf("Steve").toString(), "Steve", TARGET, MANAGED)));

        assertEquals(1, harness.countLogged(LogLevel.SEVERE, "before it reached LuckPerms"));
    }

    @Test
    @DisplayName("a frame with neither a uuid nor a username is ignored without throwing")
    void anEmptyFrameIsIgnored() {
        harness.enable();

        assertDoesNotThrow(() -> harness.pushRoleSync(Payload.empty()));

        assertTrue(harness.syncs().isEmpty());
        assertEquals(1, harness.countLogged(LogLevel.WARN, "no player to apply it to"));
    }

    /** A bridge that reports itself available and then throws — the badly-behaved implementation. */
    private static final class ThrowingLuckPerms implements LuckPermsBridge {

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public CompletableFuture<List<String>> getPlayerGroups(UUID playerUuid) {
            throw new IllegalStateException("boom");
        }

        @Override
        public CompletableFuture<Boolean> setPlayerGroups(
                UUID playerUuid, List<String> targetGroups, List<String> managedGroups) {
            throw new IllegalStateException("boom");
        }
    }
}
