package com.heimdall.module.punishments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.config.ServerRole;
import com.heimdall.core.json.Payload;
import com.heimdall.core.log.LogLevel;
import com.heimdall.core.log.RecordingLogger;
import com.heimdall.core.testing.FakePlatform;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A revoke from the bot reaching LiteBans, in hook mode.
 *
 * <p>Like the issue side in {@link LiteBansHookTest}, this only runs on a server with LiteBans
 * installed, so a mistake here is found by a customer whose unbanned player still cannot join.
 */
class LiteBansRevokeLiftTest {

    private static final String UUID_STRING = "069a79f4-44e9-4726-a5be-fca90e38aaf5";
    private static final UUID PLAYER = UUID.fromString(UUID_STRING);
    private static final Executor INLINE = new Executor() {
        @Override
        public void execute(Runnable command) {
            command.run();
        }
    };

    @TempDir
    Path dir;

    @AfterEach
    void unhook() {
        LiteBansSupport.activeCheckForTest(null);
    }

    /** A frame about a row LiteBans' bridge reported, which is the case that lifts. */
    private static LiteBansRevokeLift.Request req(String type, String uuid, String cause) {
        return LiteBansRevokeLift.requestFor(type, uuid, cause, "hook", null);
    }

    @Test
    @DisplayName("a manual revoke of a ban, an IP ban or a mute maps onto LiteBans' own verb")
    void manualRevokesMapOntoTheVerb() {
        assertEquals("litebans:unban " + UUID_STRING + " -s",
                req("ban", UUID_STRING, "manual").command(true));
        assertEquals("unban " + UUID_STRING + " -s",
                req("ipban", UUID_STRING, "manual").command(false));
        assertEquals("litebans:unmute " + UUID_STRING + " -s",
                req("mute", UUID_STRING, " MANUAL ").command(true));
        assertNull(req("ban", UUID_STRING, null),
                "a frame that carries a source always carries a cause; one without is malformed");
    }

    @Test
    @DisplayName("only a row LiteBans holds is lifted there")
    void onlyLiteBansRowsAreLifted() {
        assertNotNull(LiteBansRevokeLift.requestFor("ban", UUID_STRING, "manual", "hook", null));
        assertNotNull(LiteBansRevokeLift.requestFor("ban", UUID_STRING, "manual", "import", "ban:42"));
        assertNotNull(LiteBansRevokeLift.requestFor("ipban", UUID_STRING, "manual", "import", "ipban:7"));
        assertNull(LiteBansRevokeLift.requestFor("ban", UUID_STRING, "manual", "dashboard", null),
                "LiteBans lifts the player, not a row, so this would lift an unrelated ban");
        assertNotNull(LiteBansRevokeLift.requestFor("ban", UUID_STRING, "manual", "command", null),
                "the bot stored every bridged row as command until it kept the hook source, and "
                        + "those are the bans in force people appeal");
        assertNull(LiteBansRevokeLift.requestFor("ban", UUID_STRING, "manual", "offend", null));
        assertNull(LiteBansRevokeLift.requestFor("ban", UUID_STRING, "manual", "discord", null));
        assertNull(LiteBansRevokeLift.requestFor("ban", UUID_STRING, "manual", "", null),
                "an older bot sends no source, and a lift that cannot tell is not made");
        assertNull(LiteBansRevokeLift.requestFor("ban", UUID_STRING, "manual", "import",
                "advancedban:ban:" + UUID_STRING + ":1700000000000"),
                "every import is stamped litebans; only the id shape says which plugin it was");
        assertNull(LiteBansRevokeLift.requestFor("ban", UUID_STRING, "manual", "import",
                "vanilla:player:" + UUID_STRING));
        assertNull(LiteBansRevokeLift.requestFor("ban", UUID_STRING, "manual", "import",
                "banmanager:ban:" + UUID_STRING));
    }

    @Test
    @DisplayName("an expiry or an override never lifts anything in LiteBans")
    void bookkeepingRevokesLiftNothing() {
        assertNull(req("ban", UUID_STRING, "expiry"),
                "LiteBans has already ended its own tempban");
        assertNull(req("ban", UUID_STRING, "override"),
                "the row being retired was replaced by a newer ban, which may be LiteBans' own");
        assertNull(req("ban", UUID_STRING, "something-new"),
                "an unknown cause is not a reason to lift a ban somebody else's plugin holds");
    }

    @Test
    @DisplayName("a warn, a kick or an address-only IP ban names nothing LiteBans can lift")
    void unliftableFramesLiftNothing() {
        assertNull(req("warn", UUID_STRING, "manual"));
        assertNull(req("kick", UUID_STRING, "manual"));
        assertNull(req("ipban", "", "manual"));
        assertNull(req("ipban", null, "manual"));
    }

    @Test
    @DisplayName("only a well-formed uuid ever reaches the console")
    void onlyAUuidReachesTheConsole() {
        assertNull(req("ban", "Steve", "manual"));
        assertNull(req("ban", UUID_STRING + " --sender=x", "manual"));
        assertNull(req("ban", "1-2-3-4-5", "manual"),
                "UUID.fromString accepts this, and it would print as a different player");
    }

    @Test
    @DisplayName("nothing is run when LiteBans has nothing in force")
    void nothingInForceRunsNothing() {
        FakePlatform platform = new FakePlatform(ServerRole.STANDALONE, dir);
        LiteBansRevokeLift.lift(req("ban", UUID_STRING, "manual"),
                (family, uuid) -> false, INLINE, platform.console(), new RecordingLogger(), false);

        assertEquals(Collections.emptyList(), platform.dispatchedCommands(),
                "every server in a network gets the frame, and a dashboard ban was never in "
                        + "LiteBans at all");
    }

    @Test
    @DisplayName("the namespaced label is tried first, and the bare verb only where it is unknown")
    void fallsBackToTheBareVerb() {
        FakePlatform proxy = new FakePlatform(ServerRole.GATEKEEPER, dir).withoutCommand("litebans:unban");
        AtomicInteger asked = new AtomicInteger();
        LiteBansRevokeLift.lift(req("ban", UUID_STRING, "manual"),
                (family, uuid) -> {
                    asked.incrementAndGet();
                    assertEquals(LiteBansRevokeLift.Family.BAN, family);
                    assertEquals(PLAYER, uuid);
                    return true;
                }, INLINE, proxy.console(), new RecordingLogger(), true);

        assertEquals(1, asked.get());
        assertEquals(Arrays.asList("litebans:unban " + UUID_STRING + " -s",
                "unban " + UUID_STRING + " -s"), proxy.dispatchedCommands());

        FakePlatform bukkit = new FakePlatform(ServerRole.STANDALONE, dir);
        LiteBansRevokeLift.lift(req("mute", UUID_STRING, "manual"),
                (family, uuid) -> true, INLINE, bukkit.console(), new RecordingLogger(), false);
        assertEquals(Collections.singletonList("litebans:unmute " + UUID_STRING + " -s"),
                bukkit.dispatchedCommands());

        FakePlatform noNamespace = new FakePlatform(ServerRole.STANDALONE, dir)
                .withoutCommand("litebans:unban");
        LiteBansRevokeLift.lift(req("ban", UUID_STRING, "manual"),
                (family, uuid) -> true, INLINE, noNamespace.console(), new RecordingLogger(), false);
        assertEquals(Collections.singletonList("litebans:unban " + UUID_STRING + " -s"),
                noNamespace.dispatchedCommands(),
                "on Bukkit a bare unban can belong to Essentials or vanilla");
    }

    @Test
    @DisplayName("a failed LiteBans lookup lifts anyway, and a failed unban is logged for staff")
    void failuresAreReportedNotSwallowed() {
        CompletableFuture<String> refused = new CompletableFuture<String>();
        refused.completeExceptionally(new IllegalStateException("console unavailable"));
        FakePlatform platform = new FakePlatform(ServerRole.GATEKEEPER, dir)
                .dispatchAnswering(refused);
        RecordingLogger logger = new RecordingLogger();
        LiteBansRevokeLift.lift(req("ban", UUID_STRING, "manual"),
                (family, uuid) -> {
                    throw new IllegalStateException("pool exhausted");
                }, INLINE, platform.console(), logger, true);

        assertTrue(logger.logged(LogLevel.WARN, "Lift it in LiteBans by hand"),
                logger.records().toString());
        assertEquals(Collections.singletonList("litebans:unban " + UUID_STRING + " -s"),
                platform.dispatchedCommands(),
                "only an unknown command falls back; a failure is not retried as another plugin's");
        assertFalse(LiteBansRevokeLift.isOwnEcho("ban", UUID_STRING),
                "a lift that failed has no echo coming, and must not hide a real unban");
    }

    @Test
    @DisplayName("the removal our own unban causes is not posted back to the bot")
    void ownEchoIsRecognised() {
        FakePlatform platform = new FakePlatform(ServerRole.STANDALONE, dir);
        assertFalse(LiteBansRevokeLift.isOwnEcho("ban", UUID_STRING));
        LiteBansRevokeLift.lift(req("ban", UUID_STRING, "manual"),
                (family, uuid) -> true, INLINE, platform.console(), new RecordingLogger(), false);

        assertFalse(LiteBansRevokeLift.isOwnEcho("ipban", UUID_STRING),
                "an IP ban the same unban took is news to the bot, which revoked only the ban");
        assertFalse(LiteBansRevokeLift.isOwnEcho("mute", UUID_STRING));
        assertFalse(LiteBansRevokeLift.isOwnEcho("ban", "1f0c0d0e-0000-4000-8000-000000000000"));
        assertTrue(LiteBansRevokeLift.isOwnEcho("ban", UUID_STRING),
                "posted back, it would sweep every other ban row on the player, hidden ones too");
        assertFalse(LiteBansRevokeLift.isOwnEcho("ban", UUID_STRING),
                "consumed: a later removal on the same player is somebody's real unban");
    }

    @Test
    @DisplayName("an echo is only recognised inside its window")
    void echoWindowExpires() {
        final long[] now = {1_000_000L};
        LiteBansRevokeLift.clock = () -> now[0];
        FakePlatform platform = new FakePlatform(ServerRole.STANDALONE, dir);
        LiteBansRevokeLift.lift(req("ban", UUID_STRING, "manual"),
                (family, uuid) -> true, INLINE, platform.console(), new RecordingLogger(), false);

        now[0] += LiteBansRevokeLift.ECHO_WINDOW_MS + 1L;
        assertFalse(LiteBansRevokeLift.isOwnEcho("ban", UUID_STRING),
                "a removal long after the lift was not caused by it");
    }

    @Test
    @DisplayName("stale lifts that never echoed are dropped, so the table stays bounded")
    void staleLiftsAreDropped() {
        final long[] now = {1_000_000L};
        LiteBansRevokeLift.clock = () -> now[0];
        FakePlatform platform = new FakePlatform(ServerRole.STANDALONE, dir);
        LiteBansRevokeLift.lift(req("ban", UUID_STRING, "manual"),
                (family, uuid) -> true, INLINE, platform.console(), new RecordingLogger(), false);
        now[0] += LiteBansRevokeLift.ECHO_WINDOW_MS + 1L;
        LiteBansRevokeLift.lift(req("mute", UUID_STRING, "manual"),
                (family, uuid) -> true, INLINE, platform.console(), new RecordingLogger(), false);

        assertEquals(1, LiteBansRevokeLift.awaitingEchoForTest());
    }

    @Test
    @DisplayName("the bridge does not post our own unban back to the bot, and posts anyone else's")
    void bridgeDropsOnlyOurEcho() {
        String fakeEntryUuid = "11111111-1111-1111-1111-111111111111";
        LiteBansHookTest.FakeEntry removed = new LiteBansHookTest.FakeEntry("ban", 1L, 0L);
        assertNotNull(LiteBansEventBridge.revokeBody(removed, "salt"),
                "a moderator's unban in LiteBans still reaches the bot");

        FakePlatform platform = new FakePlatform(ServerRole.STANDALONE, dir);
        LiteBansRevokeLift.lift(req("ban", fakeEntryUuid, "manual"),
                (family, uuid) -> true, INLINE, platform.console(), new RecordingLogger(), false);

        assertNull(LiteBansEventBridge.revokeBody(removed, "salt"),
                "posted back, it would sweep every other ban row on the player, hidden ones too");
        assertNotNull(LiteBansEventBridge.revokeBody(removed, "salt"));
    }

    @Test
    @DisplayName("in hook mode, a revoke frame from the bot runs the LiteBans unban")
    void hookModeRevokeFrameLifts() throws Exception {
        try (PunishmentsHarness harness = hookMode()) {
            LiteBansSupport.activeCheckForTest((family, uuid) -> true);
            harness.tunnel.push("punish.revoke", Payload.builder()
                    .put("type", "ban")
                    .put("targetUuid", UUID_STRING)
                    .put("revokeCause", "manual")
                    .put("source", "hook")
                    .put("revokedBy", "Adam")
                    .build());

            assertEquals(Collections.singletonList("litebans:unban " + UUID_STRING + " -s"),
                    awaitDispatched(harness, 1),
                    "the mirror held no row for this player, and LiteBans must still be told");
        }
    }

    @Test
    @DisplayName("in replace mode, a revoke frame leaves LiteBans alone")
    void replaceModeLeavesLiteBansAlone() throws Exception {
        try (PunishmentsHarness harness = new PunishmentsHarness(dir, ServerRole.STANDALONE)
                .enableReplace()) {
            LiteBansSupport.activeCheckForTest((family, uuid) -> true);
            harness.tunnel.push("punish.revoke", Payload.builder()
                    .put("type", "ban")
                    .put("targetUuid", UUID_STRING)
                    .put("revokeCause", "manual")
                    .put("source", "hook")
                    .build());

            Thread.sleep(200);
            assertEquals(Collections.emptyList(), harness.platform.dispatchedCommands(),
                    "in replace mode Heimdall enforces, and a LiteBans left installed holds bans "
                            + "this revoke is not about");
        }
    }

    private PunishmentsHarness hookMode() {
        return new PunishmentsHarness(dir, ServerRole.STANDALONE).enableWith(Payload.builder()
                .put("mode", "hook")
                .put("ipSalt", "hook-salt")
                .put("rootAliases", false)
                .build());
    }

    private static List<String> awaitDispatched(PunishmentsHarness harness, int count)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2_000L;
        while (harness.platform.dispatchedCommands().size() < count
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        return harness.platform.dispatchedCommands();
    }
}
