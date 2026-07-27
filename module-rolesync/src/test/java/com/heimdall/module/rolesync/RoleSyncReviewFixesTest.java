package com.heimdall.module.rolesync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.http.model.RoleSyncDirective;
import com.heimdall.core.json.Payload;
import com.heimdall.core.log.LogLevel;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The four things the phase-1d review found, each pinned by the behaviour it protects.
 *
 * <p>Kept in its own file rather than scattered through the existing ones because every case here is
 * a <em>near miss</em> — a frame that is almost valid, a second join two seconds after the first, a
 * bot in a mode it does not advertise on the wire. They read as a set.
 */
class RoleSyncReviewFixesTest {

    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path dataDirectory;

    private RoleSyncHarness harness;

    @AfterEach
    void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    private static List<String> groups(String... values) {
        return Arrays.asList(values);
    }

    @Nested
    @DisplayName("R1 — a disabled directive keeps the plugin out of pushes too")
    class DisabledDirectiveGatesPushes {

        @Test
        @DisplayName("after a disabled directive, a pushed frame is ignored")
        void pushIsIgnoredWhileTheBotOwnsLuckPerms() {
            harness = new RoleSyncHarness(dataDirectory).enable();

            // The bot has told us, on a login, that IT drives LuckPerms over RCON for this guild.
            harness.module.applyOnJoin(
                    RoleSyncHarness.uuidOf("Steve"), "Steve", RoleSyncDirective.disabled());
            assertTrue(harness.syncs().isEmpty(), "the join path already honours this");

            // A role_sync FRAME carries no enabled flag — only a login response does. So a stale
            // broadcast during a mode switch would be obeyed by a plugin that only checked the
            // directive on the join path, and the plugin and the bot would fight over the same
            // groups: exactly what departure D2's tri-state exists to prevent.
            harness.pushRoleSync(RoleSyncHarness.frame(
                    RoleSyncHarness.uuidOf("Steve").toString(), "Steve",
                    groups("vip"), groups("vip")));

            assertTrue(harness.syncs().isEmpty(),
                    "the wire frame cannot say the bot is in RCON mode, so the last directive has "
                            + "to: " + harness.syncs());
            // Asserted on the push path's own wording, not the shared phrase: the join path logs
            // "the bot owns LuckPerms" too, and a test that could not tell the two apart would pass
            // just as happily if the push had never been gated at all.
            assertEquals(1, harness.countLogged(LogLevel.DEBUG, "the last directive said"));
        }

        @Test
        @DisplayName("an enabled directive lets pushes through again")
        void pushResumesWhenTheDirectiveSaysSo() {
            harness = new RoleSyncHarness(dataDirectory).enable();
            harness.module.applyOnJoin(
                    RoleSyncHarness.uuidOf("Steve"), "Steve", RoleSyncDirective.disabled());
            harness.module.applyOnJoin(RoleSyncHarness.uuidOf("Steve"), "Steve",
                    RoleSyncDirective.enabled(groups("vip"), groups("vip")));
            harness.luckPerms.syncs();

            harness.pushRoleSync(RoleSyncHarness.frame(
                    RoleSyncHarness.uuidOf("Alex").toString(), "Alex",
                    groups("member"), groups("member")));

            assertTrue(harness.syncs().size() >= 1,
                    "the gate has to reopen, or a guild that switches off RCON mode never syncs "
                            + "again until it restarts");
        }

        @Test
        @DisplayName("with no directive ever seen, a push is honoured")
        void pushIsHonouredBeforeAnyDirective() {
            harness = new RoleSyncHarness(dataDirectory).enable();

            // The ordinary state after a restart: config and pushes arrive before anybody logs in.
            // Refusing here would break most servers most of the time.
            harness.pushRoleSync(RoleSyncHarness.frame(
                    RoleSyncHarness.uuidOf("Steve").toString(), "Steve",
                    groups("vip"), groups("vip")));

            assertEquals(1, harness.syncs().size(), harness.syncs().toString());
        }
    }

    @Nested
    @DisplayName("R2 — one outstanding defer per player")
    class DeferDeduplication {

        @Test
        @DisplayName("a second join replaces the first defer instead of queueing beside it")
        void aSecondJoinSupersedesTheFirst() {
            harness = new RoleSyncHarness(dataDirectory).deferring().enable();
            UUID steve = RoleSyncHarness.uuidOf("Steve");

            harness.module.applyOnJoin(steve, "Steve",
                    RoleSyncDirective.enabled(groups("vip"), groups("vip")));
            harness.module.applyOnJoin(steve, "Steve",
                    RoleSyncDirective.enabled(groups("vip", "boosted"), groups("vip", "boosted")));

            assertEquals(1, harness.platform.deferredCount(),
                    "two deferred syncs two seconds apart both load-modify-save against LuckPerms "
                            + "storage, so they interleave and the later save can be computed from "
                            + "a user read before the earlier one wrote");

            harness.platform.runDeferred();

            assertEquals(1, harness.syncs().size(), harness.syncs().toString());
            assertEquals(groups("vip", "boosted"), harness.syncs().get(0).targetGroups(),
                    "last snapshot wins — the bot's most recent word is the truth");
        }

        @Test
        @DisplayName("two different players keep their own defers")
        void differentPlayersAreIndependent() {
            harness = new RoleSyncHarness(dataDirectory).deferring().enable();

            harness.module.applyOnJoin(RoleSyncHarness.uuidOf("Steve"), "Steve",
                    RoleSyncDirective.enabled(groups("vip"), groups("vip")));
            harness.module.applyOnJoin(RoleSyncHarness.uuidOf("Alex"), "Alex",
                    RoleSyncDirective.enabled(groups("member"), groups("member")));

            assertEquals(2, harness.platform.deferredCount(),
                    "deduplication is per player, not global");
        }
    }

    @Nested
    @DisplayName("R3 — an absent group list is not an empty one")
    class AbsentVersusEmpty {

        @Test
        @DisplayName("a frame with no targetGroups field is ignored, not read as a revocation")
        void missingTargetGroupsIsIgnored() {
            harness = new RoleSyncHarness(dataDirectory).enable();

            // `targetGroups: []` means "hold none of the managed groups" and strips them. A field
            // that never arrived means the frame is not what this build expects — and revoking a
            // player's groups on the strength of a parse failure is the worst reading available.
            harness.pushRoleSync(Payload.builder()
                    .put("uuid", RoleSyncHarness.uuidOf("Steve").toString())
                    .put("username", "Steve")
                    .putStrings("managedGroups", groups("vip"))
                    .build());

            assertTrue(harness.syncs().isEmpty(), harness.syncs().toString());
            assertEquals(1, harness.countLogged(LogLevel.WARN, "no readable targetGroups"));
        }

        @Test
        @DisplayName("a frame whose targetGroups is not an array is ignored too")
        void malformedTargetGroupsIsIgnored() {
            harness = new RoleSyncHarness(dataDirectory).enable();

            harness.pushRoleSync(Payload.builder()
                    .put("uuid", RoleSyncHarness.uuidOf("Steve").toString())
                    .put("username", "Steve")
                    .put("targetGroups", "vip")
                    .putStrings("managedGroups", groups("vip"))
                    .build());

            assertTrue(harness.syncs().isEmpty(),
                    "Payload.strings answers an empty list for this too, which is why hasArray "
                            + "exists: " + harness.syncs());
            assertEquals(1, harness.countLogged(LogLevel.WARN, "no readable targetGroups"));
        }

        @Test
        @DisplayName("a genuinely empty targetGroups IS applied — it is a revocation")
        void emptyTargetGroupsRevokes() {
            harness = new RoleSyncHarness(dataDirectory).enable();

            harness.pushRoleSync(RoleSyncHarness.frame(
                    RoleSyncHarness.uuidOf("Steve").toString(), "Steve",
                    Collections.<String>emptyList(), groups("vip")));

            assertEquals(1, harness.syncs().size(),
                    "an empty target list with a managed list is the bot saying 'take vip away', "
                            + "and it has to reach LuckPerms: " + harness.syncs());
            assertTrue(harness.syncs().get(0).targetGroups().isEmpty());
            assertEquals(groups("vip"), harness.syncs().get(0).managedGroups());
        }

        @Test
        @DisplayName("a frame with no managedGroups field is ignored")
        void missingManagedGroupsIsIgnored() {
            harness = new RoleSyncHarness(dataDirectory).enable();

            harness.pushRoleSync(Payload.builder()
                    .put("uuid", RoleSyncHarness.uuidOf("Steve").toString())
                    .put("username", "Steve")
                    .putStrings("targetGroups", groups("vip"))
                    .build());

            assertTrue(harness.syncs().isEmpty());
            assertEquals(1, harness.countLogged(LogLevel.WARN, "no readable managedGroups"));
        }
    }

    @Nested
    @DisplayName("R4 — the defer is two seconds, and that number is the point")
    class TheDeferIsTwoSeconds {

        @Test
        @DisplayName("a join sync is scheduled for 2000ms, not merely 'later'")
        void theJoinDeferIsTwoSeconds() {
            harness = new RoleSyncHarness(dataDirectory).deferring().enable();

            harness.module.applyOnJoin(RoleSyncHarness.uuidOf("Steve"), "Steve",
                    RoleSyncDirective.enabled(groups("vip"), groups("vip")));

            // v2 used runTaskLater(..., 40L) — forty ticks, two seconds — so the player is fully
            // connected before their groups change underneath them. Without asserting the number, a
            // fifty-millisecond defer and this one are the same observation, and the test could only
            // prove that something was deferred rather than that it was deferred long enough.
            assertEquals(Collections.singletonList(Long.valueOf(2000L)),
                    harness.platform.deferredDelays(),
                    "this is what FakePlatform.deferredDelays() exists for");
        }
    }
}
