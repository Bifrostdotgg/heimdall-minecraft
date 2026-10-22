package com.heimdall.module.punishments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.config.ServerRole;
import com.heimdall.core.json.Payload;
import com.heimdall.core.testing.FakeCommandSource;
import com.heimdall.core.testing.FakePlayer;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A warn is news, shown once: live if the player is online, otherwise at their next join, and only
 * by the server that sees every player (the proxy, or a server with none in front of it). It used
 * to be shown by every server at every join, forever.
 */
class WarnShownOnceTest {

    @TempDir
    Path dataDir;

    private static final String REASON = "spamming in chat";

    private static Payload warnFrame(FakePlayer player, String id) {
        return Payload.builder()
                .put("id", id)
                .put("type", "warn")
                .put("targetUuid", player.uuid().toString())
                .put("targetName", player.name())
                .put("reason", REASON)
                .put("source", "dashboard")
                // Silent, so the staff announcement does not land in the player's messages too.
                .put("silent", true)
                .build();
    }

    private static List<PunishmentOutbox.Entry> queued(PunishmentsHarness harness, String op) {
        List<PunishmentOutbox.Entry> out = new java.util.ArrayList<PunishmentOutbox.Entry>();
        for (PunishmentOutbox.Entry entry : harness.module.outboxForTest().snapshot()) {
            if (op.equals(entry.op)) out.add(entry);
        }
        return out;
    }

    private static void join(PunishmentsHarness harness, FakePlayer player) {
        harness.platform.join(player);
        harness.sessions.join(player, System.currentTimeMillis());
    }

    @Test
    @DisplayName("a warn to a player online here is shown now, uploaded delivered, and not kept")
    void onlineWarnIsShownOnceAndUploadedDelivered() {
        try (PunishmentsHarness harness = new PunishmentsHarness(dataDir, ServerRole.STANDALONE)
                .enableReplace()) {
            FakePlayer steve = harness.platform.join(FakePlayer.named("Steve"));

            harness.module.onStaffCommand(FakeCommandSource.console(), "warn",
                    Arrays.asList("Steve", "-s", "spamming"));

            assertFalse(steve.messages().isEmpty(), "Steve saw his warning");
            List<PunishmentOutbox.Entry> issues = queued(harness, "issue");
            assertEquals(1, issues.size());
            assertTrue(issues.get(0).payload.bool("delivered", false), issues.get(0).payload.toJson());
            assertNull(harness.module.mirrorForTest().get("warn:" + steve.uuid()),
                    "nothing is left to show him at his next join");

            int seen = steve.messages().size();
            harness.sessions.join(steve, System.currentTimeMillis());
            assertEquals(seen, steve.messages().size(), "and it is not shown again when he joins");
        }
    }

    @Test
    @DisplayName("a warn to a player who is offline is shown at their next join, and only that one")
    void offlineWarnIsShownAtTheNextJoinOnly() {
        try (PunishmentsHarness harness = new PunishmentsHarness(dataDir, ServerRole.STANDALONE)
                .enableReplace()) {
            FakePlayer steve = FakePlayer.named("Steve");
            // Issued from the dashboard while he is offline, fanned out by the bot.
            harness.tunnel.push("punish.apply", warnFrame(steve, "64b0000000000000000000a1"));
            assertNotNull(harness.module.mirrorForTest().get("warn:" + steve.uuid()),
                    "held for his next join");

            join(harness, steve);
            int afterFirstJoin = steve.messages().size();
            assertTrue(afterFirstJoin > 0, "shown when he joins");
            assertNull(harness.module.mirrorForTest().get("warn:" + steve.uuid()));
            List<PunishmentOutbox.Entry> acks = queued(harness, HeimdallPunishmentsModule.OP_DELIVERED);
            assertEquals(1, acks.size(), "and the bot is told, through the outbox");
            assertEquals("64b0000000000000000000a1", acks.get(0).payload.string("id", ""));

            harness.sessions.join(steve, System.currentTimeMillis());
            assertEquals(afterFirstJoin, steve.messages().size(), "never shown at a second join");
        }
    }

    @Test
    @DisplayName("a warn still queued for upload is marked delivered on its queued issue instead")
    void queuedWarnShownAtJoinMarksItsIssue() {
        try (PunishmentsHarness harness = new PunishmentsHarness(dataDir, ServerRole.STANDALONE)
                .enableReplace()) {
            FakePlayer steve = FakePlayer.named("Steve");
            // Seen here before, so his name resolves with the bot unreachable; offline now.
            harness.login(steve.uuid(), steve.name(), "203.0.113.9");
            harness.module.onStaffCommand(FakeCommandSource.console(), "warn",
                    Arrays.asList("Steve", "-s", "spamming"));
            List<PunishmentOutbox.Entry> issues = queued(harness, "issue");
            assertEquals(1, issues.size(), "issued locally, queued for the bot");
            assertFalse(issues.get(0).payload.bool("delivered", false));
            assertTrue(harness.module.mirrorForTest().get("warn:" + steve.uuid()).id
                    .startsWith(HeimdallPunishmentsModule.LOCAL_ID_PREFIX));

            join(harness, steve);

            issues = queued(harness, "issue");
            assertTrue(issues.get(0).payload.bool("delivered", false), issues.get(0).payload.toJson());
            assertTrue(queued(harness, HeimdallPunishmentsModule.OP_DELIVERED).isEmpty(),
                    "a local id has nothing for the bot to acknowledge");
        }
    }

    @Test
    @DisplayName("a backend behind a Heimdall proxy never shows a warn: the proxy does")
    void enforcerLeavesWarnsToAHeimdallProxy() {
        try (PunishmentsHarness harness = new PunishmentsHarness(dataDir, ServerRole.ENFORCER)
                .enableReplace()) {
            FakePlayer steve = harness.platform.join(FakePlayer.named("Steve"));

            harness.tunnel.push("punish.apply", warnFrame(steve, "64b0000000000000000000a2")
                    .toBuilder().put("proxyShowsWarns", true).build());
            harness.sessions.join(steve, System.currentTimeMillis());

            assertTrue(steve.messages().isEmpty(), "nothing shown here: " + steve.messageText());
            assertTrue(queued(harness, HeimdallPunishmentsModule.OP_DELIVERED).isEmpty());
        }
    }

    @Test
    @DisplayName("a backend whose proxy does not run Heimdall shows warns itself")
    void enforcerWithoutAHeimdallProxyShowsWarns() {
        try (PunishmentsHarness harness = new PunishmentsHarness(dataDir, ServerRole.ENFORCER)
                .enableReplace()) {
            FakePlayer steve = harness.platform.join(FakePlayer.named("Steve"));

            // Proxy forwarding is on, so this server resolves ENFORCER, but the bot says no
            // Heimdall proxy is connected; and an older bot, which says nothing, means the same.
            harness.tunnel.push("punish.apply", warnFrame(steve, "64b0000000000000000000a8")
                    .toBuilder().put("proxyShowsWarns", false).build());

            assertFalse(steve.messages().isEmpty(), "shown here, since nothing else will");
            assertEquals(1, queued(harness, HeimdallPunishmentsModule.OP_DELIVERED).size());
        }
    }

    @Test
    @DisplayName("LiteBans showing its own warn leaves a Heimdall warn still to be shown")
    void hookWarnKeepsTheHeimdallWarn() {
        try (PunishmentsHarness harness = new PunishmentsHarness(dataDir, ServerRole.STANDALONE)
                .enableReplace()) {
            FakePlayer steve = FakePlayer.named("Steve");
            harness.tunnel.push("punish.apply", warnFrame(steve, "64b0000000000000000000a9"));

            harness.tunnel.push("punish.apply", warnFrame(steve, "64b0000000000000000000aa")
                    .toBuilder()
                    .put("source", "hook")
                    .put("deliveredAt", "2026-10-22T12:00:00.000Z")
                    .build());

            ActivePunishment held = harness.module.mirrorForTest().get("warn:" + steve.uuid());
            assertNotNull(held, "the dashboard warn is still held");
            assertEquals("64b0000000000000000000a9", held.id);
        }
    }

    @Test
    @DisplayName("the proxy shows a warn arriving for a player online, and acknowledges it")
    void gatekeeperShowsALiveWarnAndAcknowledges() {
        try (PunishmentsHarness harness = new PunishmentsHarness(dataDir, ServerRole.GATEKEEPER)
                .enableReplace()) {
            FakePlayer steve = harness.platform.join(FakePlayer.named("Steve"));

            harness.tunnel.push("punish.apply", warnFrame(steve, "64b0000000000000000000a3"));

            assertFalse(steve.messages().isEmpty());
            assertNull(harness.module.mirrorForTest().get("warn:" + steve.uuid()));
            assertEquals(1, queued(harness, HeimdallPunishmentsModule.OP_DELIVERED).size());
        }
    }

    @Test
    @DisplayName("a warn that was already shown elsewhere is neither shown nor kept, and closes the older one")
    void deliveredFrameIsNotShown() {
        try (PunishmentsHarness harness = new PunishmentsHarness(dataDir, ServerRole.STANDALONE)
                .enableReplace()) {
            FakePlayer steve = FakePlayer.named("Steve");
            harness.tunnel.push("punish.apply", warnFrame(steve, "64b0000000000000000000a4"));
            assertNotNull(harness.module.mirrorForTest().get("warn:" + steve.uuid()));

            harness.platform.join(steve);
            harness.tunnel.push("punish.apply", warnFrame(steve, "64b0000000000000000000a5")
                    .toBuilder().put("deliveredAt", "2026-10-22T12:00:00.000Z").build());

            assertTrue(steve.messages().isEmpty(), steve.messageText().toString());
            assertNull(harness.module.mirrorForTest().get("warn:" + steve.uuid()),
                    "the bot closed the older warn with this one");
        }
    }

    @Test
    @DisplayName("punish.delivered drops this server's copy only when it is one the bot marked")
    void deliveredNoticeDropsOnlyMarkedWarns() {
        try (PunishmentsHarness harness = new PunishmentsHarness(dataDir, ServerRole.ENFORCER)
                .enableReplace()) {
            FakePlayer steve = FakePlayer.named("Steve");
            harness.tunnel.push("punish.apply", warnFrame(steve, "64b0000000000000000000a6"));

            harness.tunnel.push("punish.delivered", Payload.builder()
                    .putStrings("ids", Arrays.asList("64b0000000000000000000ff"))
                    .put("targetUuid", steve.uuid().toString())
                    .build());
            assertNotNull(harness.module.mirrorForTest().get("warn:" + steve.uuid()),
                    "a newer warn the bot did not mark stays");

            harness.tunnel.push("punish.delivered", Payload.builder()
                    .putStrings("ids", Arrays.asList("64b0000000000000000000a6"))
                    .put("targetUuid", steve.uuid().toString())
                    .build());
            assertNull(harness.module.mirrorForTest().get("warn:" + steve.uuid()));
        }
    }

    @Test
    @DisplayName("an acknowledgement a bot older than the route refuses is dropped, not held")
    void olderBotRefusingTheAcknowledgementDropsIt() throws Exception {
        try (ScriptedPunishApi bot = new ScriptedPunishApi();
                PunishmentsHarness harness = PunishmentsHarness
                        .withApi(dataDir, ServerRole.STANDALONE, bot.baseUrl())
                        .enableReplace()) {
            FakePlayer steve = harness.platform.join(FakePlayer.named("Steve"));
            harness.tunnel.push("punish.apply", warnFrame(steve, "64b0000000000000000000a7"));
            assertFalse(steve.messages().isEmpty());

            // The stub knows no /delivered route and answers 404, as a bot before it does.
            long deadline = System.currentTimeMillis() + 5_000L;
            while (System.currentTimeMillis() < deadline
                    && (bot.countSuffix("/delivered") == 0 || !harness.module.outboxForTest().isEmpty())) {
                harness.module.flushQueue();
                Thread.sleep(20L);
            }

            assertEquals(1, bot.countSuffix("/punishments/64b0000000000000000000a7/delivered"));
            assertTrue(harness.module.outboxForTest().isEmpty(), "the entry is dropped");
        }
    }
}
