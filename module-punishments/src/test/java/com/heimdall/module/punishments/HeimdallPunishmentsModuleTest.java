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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
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

    @Test
    @DisplayName("hook + rootAliases does not steal /ban")
    void hookModeDoesNotBindRootBan() {
        try (PunishmentsHarness harness = new PunishmentsHarness(dataDir, ServerRole.GATEKEEPER)
                .enableWith(Payload.builder()
                        .put("mode", "hook")
                        .put("ipSalt", SALT)
                        .put("rootAliases", true)
                        .build())) {
            assertFalse(harness.platform.commandRegistry().has("ban"));
            assertFalse(harness.platform.commandRegistry().has("ipban"));
        }
    }

    @Test
    @DisplayName("an ipban without a digest is not stored under the UUID")
    void digestlessIpbanHasNoMirrorKey() {
        ActivePunishment p = new ActivePunishment();
        p.type = "ipban";
        p.targetUuid = STEVE.toString();
        assertNull(HeimdallPunishmentsModule.keyFor(p));
        p.ipDigest = "abc";
        assertEquals("ipban:abc", HeimdallPunishmentsModule.keyFor(p));
    }

    @Test
    @DisplayName("empty salt does not HMAC an import IP")
    void importRefusesEmptySalt() {
        PunishmentImportRow row = new PunishmentImportRow();
        row.ip = "198.51.100.7";
        PunishmentImportRow.hashIps(java.util.Collections.singletonList(row), "");
        assertNull(row.ip);
        assertNull(row.ipDigest);
    }

    @Test
    @DisplayName("a local unban is not resurrected by a stale ETag pull while the revoke is queued")
    void pendingRevokeSurvivesReconcile() {
        try (PunishmentsHarness harness = new PunishmentsHarness(dataDir, ServerRole.STANDALONE).enableReplace()) {
            ActivePunishment ban = new ActivePunishment();
            ban.id = "ban-1";
            ban.type = "ban";
            ban.targetUuid = STEVE.toString();
            ban.targetName = "Steve";
            harness.module.mirrorForTest().record("ban:" + STEVE, ban);

            harness.platform.join(new FakePlayer(STEVE, "Steve"));
            harness.module.onStaffCommand(FakeCommandSource.console(), "unban", Arrays.asList("Steve"));
            assertNull(harness.module.mirrorForTest().get("ban:" + STEVE));
            assertFalse(harness.module.outboxForTest().isEmpty());

            Map<String, ActivePunishment> snap = new LinkedHashMap<String, ActivePunishment>();
            snap.put("ban:" + STEVE, ban);
            harness.module.mirrorForTest().reconcile(snap);
            assertTrue(harness.module.mirrorForTest().isPresent("ban:" + STEVE),
                    "reconcile would put the ban back");
            harness.module.replayPendingWrites();
            assertNull(harness.module.mirrorForTest().get("ban:" + STEVE),
                    "unacked revoke must win over a stale snapshot");
        }
    }

    @Test
    @DisplayName("flushQueue posts issue then filter-revoke, and stores the sync ETag")
    void flushQueueHitsHttpAndKeepsEtag() throws Exception {
        try (ScriptedPunishApi bot = new ScriptedPunishApi();
                PunishmentsHarness harness = PunishmentsHarness.withApi(
                        dataDir, ServerRole.STANDALONE, bot.baseUrl()).enableReplace()) {
            waitFor(() -> harness.module.mirrorForTest().lastEtag() != null, 5_000);
            assertEquals("\"etag-1\"", harness.module.mirrorForTest().lastEtag());

            harness.platform.join(new FakePlayer(STEVE, "Steve"));
            FakeCommandSource console = FakeCommandSource.console();
            harness.module.onStaffCommand(console, "ban", Arrays.asList("Steve", "griefing"));
            harness.module.onStaffCommand(console, "unban", Arrays.asList("Steve"));
            harness.module.flushQueue();
            waitFor(() -> harness.module.outboxForTest().isEmpty(), 5_000);

            assertTrue(bot.countSuffix("/punishments") >= 1, "issue was posted");
            assertTrue(bot.countSuffix("/punishments/revoke") >= 1, "revoke used the filter route");
            for (ScriptedPunishApi.Hit hit : bot.hits()) {
                assertFalse(hit.path.contains("/punishments/local-"), hit.path);
            }
            assertNull(harness.module.mirrorForTest().get("ban:" + STEVE));
        }
    }

    @Test
    @DisplayName("plugin.yml does not claim a ban: command key")
    void pluginYmlDoesNotClaimBan() throws Exception {
        java.nio.file.Path yml = pluginYml();
        String text = new String(java.nio.file.Files.readAllBytes(yml), java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(java.util.regex.Pattern.compile("(?m)^  ban:\\s*$").matcher(text).find(),
                "a plugin.yml ban: command would swallow /ban on Paper even when unbound");
        assertFalse(java.util.regex.Pattern.compile("(?m)^  mute:\\s*$").matcher(text).find());
        assertFalse(java.util.regex.Pattern.compile("(?m)^  history:\\s*$").matcher(text).find());
    }

    private static java.nio.file.Path pluginYml() {
        java.nio.file.Path[] candidates = {
                java.nio.file.Paths.get("app", "src", "main", "resources", "plugin.yml"),
                java.nio.file.Paths.get("..", "app", "src", "main", "resources", "plugin.yml"),
        };
        for (int i = 0; i < candidates.length; i++) {
            if (java.nio.file.Files.isRegularFile(candidates[i])) {
                return candidates[i];
            }
        }
        throw new AssertionError("plugin.yml not found from " + java.nio.file.Paths.get(".").toAbsolutePath());
    }

    @Test
    @DisplayName("a 401 flush holds the outbox so a later flush can still send the revoke")
    void hmac401DoesNotEmptyOutbox() throws Exception {
        try (ScriptedPunishApi bot = new ScriptedPunishApi()) {
            bot.revokeResponds(401, "{\"error\":\"Unauthorized\"}");
            PunishmentsHarness harness = PunishmentsHarness.withApi(
                    dataDir.resolve("401"), ServerRole.STANDALONE, bot.baseUrl()).enableReplace();
            try {
            waitFor(() -> harness.module.mirrorForTest().lastEtag() != null, 5_000);

            harness.platform.join(new FakePlayer(STEVE, "Steve"));
            ActivePunishment ban = new ActivePunishment();
            ban.id = "ban-1";
            ban.type = "ban";
            ban.targetUuid = STEVE.toString();
            ban.targetName = "Steve";
            harness.module.mirrorForTest().record("ban:" + STEVE, ban);
            harness.module.onStaffCommand(FakeCommandSource.console(), "unban", Arrays.asList("Steve"));
            harness.module.flushQueue();
            assertFalse(harness.module.outboxForTest().isEmpty(), "401 must not wipe the queue");

            java.util.Map<String, ActivePunishment> snap =
                    new LinkedHashMap<String, ActivePunishment>();
            snap.put("ban:" + STEVE, ban);
            harness.module.mirrorForTest().reconcile(snap);
            harness.module.replayPendingWrites();
            assertNull(harness.module.mirrorForTest().get("ban:" + STEVE),
                    "queued revoke still wins over ETag GET");

            int held = bot.countRevokes();
            bot.revokeResponds(200, "{\"success\":true,\"data\":{\"revoked\":1}}");
            harness.module.flushQueue();
            waitFor(() -> harness.module.outboxForTest().isEmpty(), 5_000);
            assertTrue(bot.countRevokes() > held,
                    "later successful flush must still send the held revoke");
            } finally {
                harness.close();
            }
        }
    }

    @Test
    @DisplayName("a 404 revoke is dropped so the rest of the outbox can flush")
    void flushQueueContinuesAfterRevoke404() throws Exception {
        try (ScriptedPunishApi bot = new ScriptedPunishApi();
                PunishmentsHarness harness = PunishmentsHarness.withApi(
                        dataDir.resolve("404"), ServerRole.STANDALONE, bot.baseUrl()).enableReplace()) {
            waitFor(() -> harness.module.mirrorForTest().lastEtag() != null, 5_000);
            bot.revokeResponds(404,
                    "{\"success\":false,\"error\":{\"code\":\"NOT_FOUND\",\"message\":\"gone\"}}");

            harness.platform.join(new FakePlayer(STEVE, "Steve"));
            ActivePunishment ban = new ActivePunishment();
            ban.id = "ban-1";
            ban.type = "ban";
            ban.targetUuid = STEVE.toString();
            ban.targetName = "Steve";
            harness.module.mirrorForTest().record("ban:" + STEVE, ban);
            harness.module.onStaffCommand(FakeCommandSource.console(), "unban", Arrays.asList("Steve"));
            ActivePunishment mute = new ActivePunishment();
            mute.id = "mute-1";
            mute.type = "mute";
            mute.targetUuid = STEVE.toString();
            mute.targetName = "Steve";
            harness.module.mirrorForTest().record("mute:" + STEVE, mute);
            harness.module.onStaffCommand(FakeCommandSource.console(), "unmute", Arrays.asList("Steve"));

            harness.module.flushQueue();
            waitFor(() -> harness.module.outboxForTest().isEmpty(), 5_000);
            assertTrue(harness.module.outboxForTest().isEmpty());
        }
    }

    @Test
    @DisplayName("root aliases rebind when config.push flips replace mode on")
    void aliasesRebindOnConfigChange() {
        try (PunishmentsHarness harness = new PunishmentsHarness(dataDir, ServerRole.GATEKEEPER)
                .enableWith(Payload.builder()
                        .put("mode", "hook")
                        .put("ipSalt", SALT)
                        .put("rootAliases", true)
                        .build())) {
            assertFalse(harness.platform.commandRegistry().has("ban"));
            harness.enableWith(Payload.builder()
                    .put("mode", "replace")
                    .put("ipSalt", SALT)
                    .put("rootAliases", true)
                    .build());
            assertTrue(harness.platform.commandRegistry().has("ban"));
        }
    }

    private static void waitFor(java.util.function.BooleanSupplier condition, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L);
        }
        assertTrue(condition.getAsBoolean(), "timed out");
    }
}
