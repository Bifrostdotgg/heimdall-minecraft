package com.heimdall.module.rolesync;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.config.ServerRole;
import com.heimdall.core.http.model.RoleSyncDirective;
import com.heimdall.core.module.ModuleState;
import com.heimdall.core.tunnel.Capabilities;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Identity, eligibility, and the thing hot-toggling actually has to get right.
 *
 * <p>The subscription-count assertions are the point of the file. A module that leaks one listener
 * per enable looks perfectly healthy — it just applies every role change twice, then three times,
 * and the symptom shows up weeks later as "the plugin fights my manual group edits". Counting the
 * subscribers before and after a toggle is the only cheap way to catch it.
 */
class RoleSyncLifecycleTest {

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
    @DisplayName("it identifies as 'rolesync' and claims the role-sync capability")
    void identity() {
        assertEquals("rolesync", harness.module.id());
        assertEquals(Collections.singleton(Capabilities.ROLE_SYNC), harness.module.capabilities());
    }

    @Test
    @DisplayName("it runs under any role, because v2 synced on the proxy as well as on backends")
    void runsUnderAnyRole() {
        Set<ServerRole> roles = harness.module.roles();
        assertTrue(roles.isEmpty(),
                "an empty set means 'any role'; v2 shipped a VelocityLuckPermsManager, so restricting "
                        + "this to backends would silently break every network whose LuckPerms is on "
                        + "the proxy");
    }

    @Test
    @DisplayName("enable, disable, enable leaves exactly one subscription and no stale listener")
    void enableDisableEnableLeavesOneSubscription() {
        UUID uuid = RoleSyncHarness.uuidOf("Steve");

        harness.enable();
        assertEquals(1, harness.roleSyncSubscribers(), "one subscription after the first enable");

        harness.disable();
        assertEquals(0, harness.roleSyncSubscribers(),
                "ModuleManager must have unwound the tracked subscription");
        assertEquals(0, harness.pushRoleSync(
                RoleSyncHarness.frame(uuid.toString(), "Steve", TARGET, MANAGED)),
                "a disabled module must not still be listening");
        assertTrue(harness.syncs().isEmpty(), "and must not still be writing groups");

        harness.enable();
        assertEquals(1, harness.roleSyncSubscribers(),
                "re-enabling must not stack a second handler on top of the first");
        assertEquals(1, harness.pushRoleSync(
                RoleSyncHarness.frame(uuid.toString(), "Steve", TARGET, MANAGED)),
                "exactly one handler sees the frame");
        assertEquals(1, harness.syncs().size(), "and it produces exactly one sync: " + harness.syncs());
    }

    @Test
    @DisplayName("three toggles still leave one subscription")
    void repeatedTogglesDoNotAccumulate() {
        for (int i = 0; i < 3; i++) {
            harness.enable().disable();
        }
        assertEquals(0, harness.roleSyncSubscribers());
        harness.enable();
        assertEquals(1, harness.roleSyncSubscribers());
    }

    @Test
    @DisplayName("disabling cancels a join sync that is still waiting out its defer")
    void disableCancelsAPendingJoinSync() {
        harness.deferring().enable();

        harness.module.applyOnJoin(RoleSyncHarness.uuidOf("Steve"), "Steve",
                RoleSyncDirective.enabled(TARGET, MANAGED));
        assertEquals(1, harness.platform.deferredCount());

        harness.disable();

        assertEquals(0, harness.platform.deferredCount(),
                "a task sitting in the SERVER's scheduler is the one thing ModuleContext does not "
                        + "track, so the module has to cancel it itself");
        assertEquals(0, harness.platform.runDeferred());
        assertTrue(harness.syncs().isEmpty(), "a module switched off must not write groups two seconds later");
    }

    @Test
    @DisplayName("applyOnJoin while disabled is a no-op, not an error")
    void applyOnJoinWhileDisabledDoesNothing() {
        assertNull(harness.module.applier(), "nothing is live before the first enable");

        assertDoesNotThrow(() -> harness.module.applyOnJoin(RoleSyncHarness.uuidOf("Steve"), "Steve",
                RoleSyncDirective.enabled(TARGET, MANAGED)));

        assertTrue(harness.syncs().isEmpty());

        harness.enable().disable();
        assertNull(harness.module.applier(), "the applier is dropped on disable");
        assertDoesNotThrow(() -> harness.module.applyOnJoin(RoleSyncHarness.uuidOf("Steve"), "Steve",
                RoleSyncDirective.enabled(TARGET, MANAGED)));
        assertTrue(harness.syncs().isEmpty());
    }

    @Test
    @DisplayName("disable is safe on a module that was never enabled")
    void disableWithoutEnableIsSafe() {
        assertDoesNotThrow(() -> new HeimdallRoleSyncModule().disable());
    }

    @Test
    @DisplayName("the manager reports it enabled, and drops the capability again when it is off")
    void capabilitiesFollowTheLifecycle() {
        harness.enable();
        assertEquals(ModuleState.ENABLED, harness.manager().state(HeimdallRoleSyncModule.ID));
        assertTrue(harness.manager().capabilities().contains(Capabilities.ROLE_SYNC));

        harness.disable();
        assertEquals(ModuleState.STOPPED, harness.manager().state(HeimdallRoleSyncModule.ID));
        assertTrue(harness.manager().capabilities().isEmpty());
    }

    @Test
    @DisplayName("a fresh applier per enable — nothing survives a toggle")
    void eachEnableGetsItsOwnApplier() {
        harness.enable();
        RoleSyncApplier first = harness.module.applier();
        harness.disable().enable();
        RoleSyncApplier second = harness.module.applier();

        assertTrue(first != second, "a reused applier would carry the previous cycle's state");
        assertEquals(0, first.pendingCount());
    }
}
