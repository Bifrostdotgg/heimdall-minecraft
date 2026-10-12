package com.heimdall.module.punishments;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    @DisplayName("a manual revoke of a ban, an IP ban or a mute maps onto LiteBans' own verb")
    void manualRevokesMapOntoTheVerb() {
        assertEquals("litebans:unban " + UUID_STRING + " -s",
                LiteBansRevokeLift.requestFor("ban", UUID_STRING, "manual").command(true));
        assertEquals("unban " + UUID_STRING + " -s",
                LiteBansRevokeLift.requestFor("ipban", UUID_STRING, "manual").command(false));
        assertEquals("litebans:unmute " + UUID_STRING + " -s",
                LiteBansRevokeLift.requestFor("mute", UUID_STRING, " MANUAL ").command(true));
        assertNotNull(LiteBansRevokeLift.requestFor("ban", UUID_STRING, null),
                "an absent cause is an older bot, and every revoke it sends is a lifting");
        assertNotNull(LiteBansRevokeLift.requestFor("ban", UUID_STRING, ""));
    }

    @Test
    @DisplayName("an expiry or an override never lifts anything in LiteBans")
    void bookkeepingRevokesLiftNothing() {
        assertNull(LiteBansRevokeLift.requestFor("ban", UUID_STRING, "expiry"),
                "LiteBans has already ended its own tempban");
        assertNull(LiteBansRevokeLift.requestFor("ban", UUID_STRING, "override"),
                "the row being retired was replaced by a newer ban, which may be LiteBans' own");
        assertNull(LiteBansRevokeLift.requestFor("ban", UUID_STRING, "something-new"),
                "an unknown cause is not a reason to lift a ban somebody else's plugin holds");
    }

    @Test
    @DisplayName("a warn, a kick or an address-only IP ban names nothing LiteBans can lift")
    void unliftableFramesLiftNothing() {
        assertNull(LiteBansRevokeLift.requestFor("warn", UUID_STRING, "manual"));
        assertNull(LiteBansRevokeLift.requestFor("kick", UUID_STRING, "manual"));
        assertNull(LiteBansRevokeLift.requestFor("ipban", "", "manual"));
        assertNull(LiteBansRevokeLift.requestFor("ipban", null, "manual"));
    }

    @Test
    @DisplayName("only a well-formed uuid ever reaches the console")
    void onlyAUuidReachesTheConsole() {
        assertNull(LiteBansRevokeLift.requestFor("ban", "Steve", "manual"));
        assertNull(LiteBansRevokeLift.requestFor("ban", UUID_STRING + " --sender=x", "manual"));
        assertNull(LiteBansRevokeLift.requestFor("ban", "1-2-3-4-5", "manual"),
                "UUID.fromString accepts this, and it would print as a different player");
    }

    @Test
    @DisplayName("nothing is run when LiteBans has nothing in force")
    void nothingInForceRunsNothing() {
        FakePlatform platform = new FakePlatform(ServerRole.STANDALONE, dir);
        LiteBansRevokeLift.lift(LiteBansRevokeLift.requestFor("ban", UUID_STRING, "manual"),
                (family, uuid) -> false, INLINE, platform.console(), new RecordingLogger());

        assertEquals(Collections.emptyList(), platform.dispatchedCommands(),
                "every server in a network gets the frame, and a dashboard ban was never in "
                        + "LiteBans at all");
    }

    @Test
    @DisplayName("the namespaced label is tried first, and the bare verb only where it is unknown")
    void fallsBackToTheBareVerb() {
        FakePlatform proxy = new FakePlatform(ServerRole.GATEKEEPER, dir).withoutCommand("litebans:unban");
        AtomicInteger asked = new AtomicInteger();
        LiteBansRevokeLift.lift(LiteBansRevokeLift.requestFor("ban", UUID_STRING, "manual"),
                (family, uuid) -> {
                    asked.incrementAndGet();
                    assertEquals(LiteBansRevokeLift.Family.BAN, family);
                    assertEquals(PLAYER, uuid);
                    return true;
                }, INLINE, proxy.console(), new RecordingLogger());

        assertEquals(1, asked.get());
        assertEquals(Arrays.asList("litebans:unban " + UUID_STRING + " -s",
                "unban " + UUID_STRING + " -s"), proxy.dispatchedCommands());

        FakePlatform bukkit = new FakePlatform(ServerRole.STANDALONE, dir);
        LiteBansRevokeLift.lift(LiteBansRevokeLift.requestFor("mute", UUID_STRING, "manual"),
                (family, uuid) -> true, INLINE, bukkit.console(), new RecordingLogger());
        assertEquals(Collections.singletonList("litebans:unmute " + UUID_STRING + " -s"),
                bukkit.dispatchedCommands(),
                "on Bukkit a bare unban can belong to Essentials or vanilla");
    }

    @Test
    @DisplayName("a failed LiteBans lookup lifts anyway, and a failed unban is logged for staff")
    void failuresAreReportedNotSwallowed() {
        FakePlatform platform = new FakePlatform(ServerRole.STANDALONE, dir)
                .failingDispatch(new IllegalStateException("console unavailable"));
        RecordingLogger logger = new RecordingLogger();
        LiteBansRevokeLift.lift(LiteBansRevokeLift.requestFor("ban", UUID_STRING, "manual"),
                (family, uuid) -> {
                    throw new IllegalStateException("pool exhausted");
                }, INLINE, platform.console(), logger);

        assertTrue(logger.logged(LogLevel.WARN, "Lift it in LiteBans by hand"),
                logger.records().toString());
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
