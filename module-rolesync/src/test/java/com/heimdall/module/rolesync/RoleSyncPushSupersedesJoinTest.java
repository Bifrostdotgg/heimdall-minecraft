package com.heimdall.module.rolesync;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.heimdall.core.http.model.RoleSyncDirective;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A {@code role_sync} push inside the two-second join window supersedes the deferred join sync, so
 * the join's older snapshot cannot land after it and revert it.
 */
class RoleSyncPushSupersedesJoinTest {

    @TempDir
    Path dataDirectory;

    private RoleSyncHarness harness;

    @AfterEach
    void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    private static List<String> groups(String... names) {
        return Arrays.asList(names);
    }

    @Test
    @DisplayName("a push inside the join window cancels the deferred join sync")
    void pushCancelsThePendingJoinSync() {
        harness = new RoleSyncHarness(dataDirectory).deferring().enable();
        UUID steve = RoleSyncHarness.uuidOf("Steve");
        // The join answer says vip; it waits two seconds for the join to settle.
        harness.module.applyOnJoin(steve, "Steve",
                RoleSyncDirective.enabled(groups("vip"), groups("vip")));
        assertEquals(1, harness.module.applier().pendingCount());

        // Inside the window, Discord takes the role away and the bot pushes.
        harness.pushRoleSync(RoleSyncHarness.frame(
                steve.toString(), "Steve", Collections.<String>emptyList(), groups("vip")));
        assertEquals(1, harness.syncs().size());
        assertEquals(0, harness.module.applier().pendingCount(), "the join sync was cancelled");

        harness.platform.runDeferred();
        assertEquals(1, harness.syncs().size(),
                "the join sync firing now would have granted vip back: " + harness.syncs());
        assertEquals(Collections.<String>emptyList(), harness.luckPerms.lastSync().targetGroups());
    }

    @Test
    @DisplayName("a push for somebody else leaves the join sync alone")
    void otherPlayersJoinSyncSurvives() {
        harness = new RoleSyncHarness(dataDirectory).deferring().enable();
        harness.module.applyOnJoin(RoleSyncHarness.uuidOf("Steve"), "Steve",
                RoleSyncDirective.enabled(groups("vip"), groups("vip")));

        harness.pushRoleSync(RoleSyncHarness.frame(RoleSyncHarness.uuidOf("Alex").toString(),
                "Alex", groups("vip"), groups("vip")));
        harness.platform.runDeferred();

        assertEquals(2, harness.syncs().size(), harness.syncs().toString());
    }

    @Test
    @DisplayName("a push that is refused does not cost the player the join sync")
    void refusedPushKeepsTheJoinSync() {
        harness = new RoleSyncHarness(dataDirectory).deferring().enable();
        UUID steve = RoleSyncHarness.uuidOf("Steve");
        harness.module.applyOnJoin(steve, "Steve",
                RoleSyncDirective.enabled(groups("vip"), groups("vip")));

        // No managed groups: changes nothing, so it supersedes nothing either.
        harness.pushRoleSync(RoleSyncHarness.frame(steve.toString(), "Steve",
                Collections.<String>emptyList(), Collections.<String>emptyList()));
        harness.platform.runDeferred();

        assertEquals(1, harness.syncs().size());
        assertEquals(groups("vip"), harness.luckPerms.lastSync().targetGroups());
    }
}
