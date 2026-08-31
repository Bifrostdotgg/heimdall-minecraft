package com.heimdall.module.punishments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.config.ServerRole;
import com.heimdall.core.http.model.PunishmentImportRow;
import com.heimdall.core.json.Payload;
import com.heimdall.core.pipeline.ChatMessage;
import com.heimdall.core.pipeline.CommandAttempt;
import com.heimdall.core.pipeline.Verdict;
import com.heimdall.core.punish.PunishmentIp;
import com.heimdall.core.testing.FakeCommandSource;
import com.heimdall.core.testing.FakePlayer;
import com.heimdall.core.testing.TestText;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HeimdallPunishmentsModuleTest {

    private static final UUID STEVE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String SALT = "replace-salt";

    @TempDir
    Path dataDir;

    @Test
    @DisplayName("a UUID ban denies login with a MiniMessage screen")
    void uuidBanDeniesLogin() {
        try (PunishmentsHarness harness = new PunishmentsHarness(dataDir, ServerRole.STANDALONE).enableReplace()) {
            ActivePunishment ban = new ActivePunishment();
            ban.id = "ban-1";
            ban.type = "ban";
            ban.targetUuid = STEVE.toString();
            ban.targetName = "Steve";
            ban.reason = "cheating";
            harness.module.mirrorForTest().record("ban:" + STEVE, ban);

            Verdict verdict = harness.login(STEVE, "Steve", "203.0.113.9");
            assertTrue(verdict.isDeny());
            String plain = TestText.plain(verdict.reason());
            assertTrue(plain.contains("banned") || plain.contains("Banned") || plain.contains("cheating"),
                    plain);
            assertFalse(plain.contains("<red>"), "MiniMessage tags must be rendered, not shown raw: " + plain);
        }
    }

    @Test
    @DisplayName("standalone checks IP bans; ENFORCER without forwarding does not")
    void enforcerIpBanNeedsForwarding() {
        ActivePunishment ipban = new ActivePunishment();
        ipban.id = "ip-1";
        ipban.type = "ipban";
        ipban.ipDigest = PunishmentIp.hash("203.0.113.9", SALT);
        ipban.targetName = "Steve";
        ipban.reason = "alt";

        try (PunishmentsHarness standalone = new PunishmentsHarness(dataDir.resolve("sa"), ServerRole.STANDALONE)
                .enableReplace()) {
            standalone.module.mirrorForTest().record("ipban:" + ipban.ipDigest, ipban);
            assertTrue(standalone.login(STEVE, "Steve", "203.0.113.9").isDeny());
        }

        try (PunishmentsHarness enforcer = new PunishmentsHarness(dataDir.resolve("enf"), ServerRole.ENFORCER)
                .enableReplace()) {
            enforcer.module.mirrorForTest().record("ipban:" + ipban.ipDigest, ipban);
            assertFalse(enforcer.platform.forwardsPlayerIps());
            assertFalse(enforcer.login(STEVE, "Steve", "203.0.113.9").isDeny(),
                    "without forwarding the address is the proxy; IP bans must not apply");
        }

        try (PunishmentsHarness forwarded = new PunishmentsHarness(dataDir.resolve("fwd"), ServerRole.ENFORCER)
                .enableReplace()) {
            forwarded.platform.withPlayerIpForwarding(true);
            forwarded.module.mirrorForTest().record("ipban:" + ipban.ipDigest, ipban);
            assertTrue(forwarded.login(STEVE, "Steve", "203.0.113.9").isDeny());
        }
    }

    @Test
    @DisplayName("mute cancels chat and blocked commands on a backend")
    void muteGatesChatAndCommands() {
        try (PunishmentsHarness harness = new PunishmentsHarness(dataDir, ServerRole.STANDALONE).enableReplace()) {
            ActivePunishment mute = new ActivePunishment();
            mute.id = "mute-1";
            mute.type = "mute";
            mute.targetUuid = STEVE.toString();
            mute.targetName = "Steve";
            mute.reason = "spam";
            harness.module.mirrorForTest().record("mute:" + STEVE, mute);

            Verdict chat = harness.chatPipeline.dispatch(ChatMessage.of(STEVE, "Steve", "hello"));
            assertTrue(chat.isDeny());

            Verdict msg = harness.commandPipeline.dispatch(CommandAttempt.of(STEVE, "Steve", "/essentials:msg Alex hi"));
            assertTrue(msg.isDeny());

            Verdict spawn = harness.commandPipeline.dispatch(CommandAttempt.of(STEVE, "Steve", "/spawn"));
            assertFalse(spawn.isDeny());
        }
    }

    @Test
    @DisplayName("a throwing command interceptor fails open next to the mute gate")
    void throwingInterceptorFailsOpen() {
        try (PunishmentsHarness harness = new PunishmentsHarness(dataDir, ServerRole.STANDALONE).enableReplace()) {
            harness.commandPipeline.register(attempt -> {
                throw new IllegalStateException("boom");
            }, 1, "broken");
            Verdict verdict = harness.commandPipeline.dispatch(CommandAttempt.of(STEVE, "Steve", "/spawn"));
            assertFalse(verdict.isDeny(), "fail-open: a broken interceptor must not cancel commands");
        }
    }

    @Test
    @DisplayName("root /ban aliases stay off unless the dashboard toggle is on")
    void rootAliasesDefaultOff() {
        try (PunishmentsHarness harness = new PunishmentsHarness(dataDir, ServerRole.GATEKEEPER).enableReplace()) {
            assertFalse(harness.platform.commandRegistry().has("ban"));
            assertFalse(harness.platform.commandRegistry().has("ipban"));
        }
        try (PunishmentsHarness aliased = new PunishmentsHarness(dataDir.resolve("alias"), ServerRole.GATEKEEPER)
                .enableWith(Payload.builder()
                        .put("mode", "replace")
                        .put("ipSalt", SALT)
                        .put("rootAliases", true)
                        .build())) {
            assertTrue(aliased.platform.commandRegistry().has("ban"));
            assertTrue(aliased.platform.commandRegistry().has("ipban"));
            assertTrue(aliased.platform.commandRegistry().has("history"));
        }
    }

    @Test
    @DisplayName("revoke drops the local mirror immediately")
    void revokeUpdatesMirror() {
        try (PunishmentsHarness harness = new PunishmentsHarness(dataDir, ServerRole.STANDALONE).enableReplace()) {
            ActivePunishment ban = new ActivePunishment();
            ban.id = "ban-1";
            ban.type = "ban";
            ban.targetUuid = STEVE.toString();
            ban.targetName = "Steve";
            harness.module.mirrorForTest().record("ban:" + STEVE, ban);

            FakeCommandSource console = FakeCommandSource.console();
            harness.module.onStaffCommand(console, "unban", Arrays.asList("Steve"));

            assertNull(harness.module.mirrorForTest().get("ban:" + STEVE));
            assertTrue(console.wasTold("Revoked"));
            assertFalse(harness.module.outboxForTest().isEmpty(), "the revoke is queued for the bot");
        }
    }

    @Test
    @DisplayName("rollback revokes the most recent active punishment")
    void rollbackRevokesLatest() {
        try (PunishmentsHarness harness = new PunishmentsHarness(dataDir, ServerRole.STANDALONE).enableReplace()) {
            ActivePunishment mute = new ActivePunishment();
            mute.id = "mute-1";
            mute.type = "mute";
            mute.targetUuid = STEVE.toString();
            mute.targetName = "Steve";
            mute.issuedAt = "2020-01-01T00:00:00Z";
            harness.module.mirrorForTest().record("mute:" + STEVE, mute);

            ActivePunishment ban = new ActivePunishment();
            ban.id = "ban-2";
            ban.type = "ban";
            ban.targetUuid = STEVE.toString();
            ban.targetName = "Steve";
            ban.issuedAt = "2024-01-01T00:00:00Z";
            harness.module.mirrorForTest().record("ban:" + STEVE, ban);

            harness.platform.join(new FakePlayer(STEVE, "Steve"));

            FakeCommandSource console = FakeCommandSource.console();
            harness.module.onStaffCommand(console, "rollback", Arrays.asList("Steve"));

            assertNull(harness.module.mirrorForTest().get("ban:" + STEVE));
            assertTrue(harness.module.mirrorForTest().isPresent("mute:" + STEVE));
        }
    }

    @Test
    @DisplayName("dupeip lists names and never a raw address")
    void dupeipHidesRawIp() {
        try (PunishmentsHarness harness = new PunishmentsHarness(dataDir, ServerRole.GATEKEEPER).enableReplace()) {
            harness.login(STEVE, "Steve", "203.0.113.9");
            UUID alex = UUID.fromString("22222222-2222-2222-2222-222222222222");
            harness.login(alex, "Alex", "203.0.113.9");

            FakeCommandSource console = FakeCommandSource.console();
            harness.module.onStaffCommand(console, "dupeip", Arrays.asList("Steve"));
            String joined = String.join("\n", console.messageText());
            assertTrue(joined.contains("Alex") || joined.contains("Steve"), joined);
            assertFalse(joined.contains("203.0.113.9"), joined);
        }
    }

    @Test
    @DisplayName("import hashing clears the scratch IP before a body can be built")
    void hashBeforePost() {
        PunishmentImportRow row = new PunishmentImportRow();
        row.type = "ipban";
        row.ip = "198.51.100.7";
        PunishmentImportRow.hashIps(java.util.Collections.singletonList(row), SALT);
        assertNull(row.ip);
        assertEquals(PunishmentIp.hash("198.51.100.7", SALT), row.ipDigest);
    }

    @Test
    @DisplayName("hook mode does not native-enforce /hd ban")
    void hookModeDoesNotIssue() {
        try (PunishmentsHarness harness = new PunishmentsHarness(dataDir, ServerRole.STANDALONE)
                .enableWith(Payload.builder()
                        .put("mode", "hook")
                        .put("ipSalt", SALT)
                        .build())) {
            FakeCommandSource console = FakeCommandSource.console();
            harness.module.onStaffCommand(console, "ban", Arrays.asList("Steve", "griefing"));
            assertTrue(console.wasTold("not in replace mode"));
            assertTrue(harness.module.outboxForTest() == null
                    || harness.module.outboxForTest().isEmpty());
        }
    }
}
