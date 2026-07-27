package com.heimdall.module.rolesync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.http.model.RoleSyncDirective;
import com.heimdall.core.log.LogLevel;
import com.heimdall.core.testing.FakeLuckPerms;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The login path: the {@code roleSync} tri-state, the managed-list guard, and the two-second defer.
 *
 * <p>Departure D2 is the subject of the first three tests. Absent and disabled both mean "change
 * nothing" and are separately asserted, including that they say different things in the log —
 * because the whole reason the tri-state exists is that an operator asking why nothing happened gets
 * a different answer in each case.
 */
class RoleSyncOnJoinTest {

    private static final List<String> TARGET = Arrays.asList("vip", "builder");
    private static final List<String> MANAGED = Arrays.asList("vip", "builder", "mod");

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
    @DisplayName("an absent directive changes nothing and says why")
    void absentDirectiveChangesNothing() {
        harness.enable();

        harness.module.applyOnJoin(RoleSyncHarness.uuidOf("Steve"), "Steve", RoleSyncDirective.absent());

        assertTrue(harness.syncs().isEmpty(), "an absent snapshot must not touch LuckPerms");
        assertTrue(harness.logger.logged(LogLevel.DEBUG, "no role-sync snapshot for Steve"),
                "the reason should name the absent case: " + harness.logger.records());
    }

    @Test
    @DisplayName("a disabled directive changes nothing, and for a different stated reason")
    void disabledDirectiveChangesNothing() {
        harness.enable();

        harness.module.applyOnJoin(RoleSyncHarness.uuidOf("Steve"), "Steve", RoleSyncDirective.disabled());

        assertTrue(harness.syncs().isEmpty(),
                "the bot drives LuckPerms itself when roleSync is disabled; the plugin must keep out");
        assertTrue(harness.logger.logged(LogLevel.DEBUG, "role sync is disabled for Steve"),
                "the reason should name the disabled case: " + harness.logger.records());
    }

    @Test
    @DisplayName("absent and disabled are distinguishable in the log, not one collapsed 'not enabled'")
    void absentAndDisabledAreDistinguishable() {
        harness.enable();
        UUID uuid = RoleSyncHarness.uuidOf("Steve");

        harness.module.applyOnJoin(uuid, "Steve", RoleSyncDirective.absent());
        harness.module.applyOnJoin(uuid, "Steve", RoleSyncDirective.disabled());

        List<String> aboutSteve = new java.util.ArrayList<String>();
        for (String message : harness.logger.messagesAt(LogLevel.DEBUG)) {
            if (message != null && message.contains("Steve")) {
                aboutSteve.add(message);
            }
        }
        assertEquals(2, aboutSteve.size(), "one line per state: " + aboutSteve);
        assertNotEquals(aboutSteve.get(0), aboutSteve.get(1),
                "collapsing the two states into one message is departure D2's failure mode");
    }

    @Test
    @DisplayName("an enabled directive syncs, but only after the defer is released")
    void enabledDirectiveSyncsAfterTheDefer() {
        harness.deferring().enable();

        harness.module.applyOnJoin(RoleSyncHarness.uuidOf("Steve"), "Steve",
                RoleSyncDirective.enabled(TARGET, MANAGED));

        assertEquals(1, harness.platform.deferredCount(),
                "the sync must be scheduled, not run inline — the player is not connected yet");
        assertTrue(harness.syncs().isEmpty(), "nothing may reach LuckPerms before the defer fires");

        assertEquals(1, harness.platform.runDeferred());

        List<FakeLuckPerms.Sync> syncs = harness.syncs();
        assertEquals(1, syncs.size(), "exactly one sync: " + syncs);
        assertEquals(RoleSyncHarness.uuidOf("Steve"), syncs.get(0).uuid());
        assertEquals(TARGET, syncs.get(0).targetGroups());
        assertEquals(MANAGED, syncs.get(0).managedGroups());
    }

    @Test
    @DisplayName("an empty managed list changes nothing — it never means 'manage everything'")
    void emptyManagedListChangesNothing() {
        harness.deferring().enable();

        harness.module.applyOnJoin(RoleSyncHarness.uuidOf("Steve"), "Steve",
                RoleSyncDirective.enabled(TARGET, Collections.<String>emptyList()));

        assertEquals(0, harness.platform.deferredCount(), "nothing should even be scheduled");
        assertTrue(harness.syncs().isEmpty(),
                "departure D46: an empty managed list would strip every group on the server");
    }

    @Test
    @DisplayName("a null managed list changes nothing either")
    void nullManagedListChangesNothing() {
        harness.deferring().enable();

        harness.module.applyOnJoin(RoleSyncHarness.uuidOf("Steve"), "Steve",
                RoleSyncDirective.enabled(TARGET, null));

        assertEquals(0, harness.platform.deferredCount());
        assertTrue(harness.syncs().isEmpty());
    }

    @Test
    @DisplayName("a join with no UUID is a warning, not an exception")
    void missingUuidIsAWarning() {
        harness.enable();

        harness.module.applyOnJoin(null, "Steve", RoleSyncDirective.enabled(TARGET, MANAGED));

        assertTrue(harness.syncs().isEmpty());
        assertEquals(1, harness.countLogged(LogLevel.WARN, "has no UUID"));
    }

    @Test
    @DisplayName("two joins produce two independent deferred syncs")
    void twoJoinsDeferIndependently() {
        harness.deferring().enable();

        harness.module.applyOnJoin(RoleSyncHarness.uuidOf("Steve"), "Steve",
                RoleSyncDirective.enabled(TARGET, MANAGED));
        harness.module.applyOnJoin(RoleSyncHarness.uuidOf("Alex"), "Alex",
                RoleSyncDirective.enabled(Collections.<String>emptyList(), MANAGED));

        assertEquals(2, harness.platform.deferredCount());
        harness.platform.runDeferred();

        List<FakeLuckPerms.Sync> syncs = harness.syncs();
        assertEquals(2, syncs.size());
        assertEquals(RoleSyncHarness.uuidOf("Steve"), syncs.get(0).uuid());
        assertEquals(TARGET, syncs.get(0).targetGroups());
        assertEquals(RoleSyncHarness.uuidOf("Alex"), syncs.get(1).uuid());
        assertEquals(Collections.<String>emptyList(), syncs.get(1).targetGroups());
    }
}
