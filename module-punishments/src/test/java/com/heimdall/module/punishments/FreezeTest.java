package com.heimdall.module.punishments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.config.ServerRole;
import com.heimdall.core.json.Payload;
import com.heimdall.core.pipeline.ChatMessage;
import com.heimdall.core.pipeline.CommandAttempt;
import com.heimdall.core.pipeline.Verdict;
import com.heimdall.core.punish.HiddenPunishments;
import com.heimdall.core.punish.PunishmentScreens;
import com.heimdall.core.testing.FakeCommandSource;
import com.heimdall.core.testing.FakePlayer;
import com.heimdall.core.testing.TestText;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Freeze, end to end on the plugin side: issued and lifted in game in both native modes, enforced
 * on chat and commands in both, told to the player, relayed to staff, and the logout ban.
 */
class FreezeTest {

    @TempDir
    Path dataDir;

    @AfterEach
    void unhookLiteBans() {
        LiteBansSupport.activeCheckForTest(null);
    }

    private static PunishmentsHarness enable(PunishmentsHarness harness, String mode) {
        return enable(harness, mode, false, 0L);
    }

    private static PunishmentsHarness enable(PunishmentsHarness harness, String mode,
            boolean logoutBan, long logoutBanSeconds) {
        return harness.enableWith(Payload.builder()
                .put("mode", mode)
                .put("ipSalt", "salt")
                .put("rootAliases", false)
                .put(PunishmentSettings.KEY_FREEZE_LOGOUT_BAN, logoutBan)
                .put(PunishmentSettings.KEY_FREEZE_LOGOUT_BAN_SECONDS, logoutBanSeconds)
                .build());
    }

    private static Payload freezeFrame(FakePlayer player, String id, boolean hidden) {
        return Payload.builder()
                .put("id", id)
                .put("type", "freeze")
                .put("targetUuid", player.uuid().toString())
                .put("targetName", player.name())
                .put("reason", "x-ray check")
                .put("source", "dashboard")
                .put("silent", true)
                .put("hidden", hidden)
                .build();
    }

    private static Payload revokeFrame(FakePlayer player) {
        return Payload.builder()
                .put("type", "freeze")
                .put("targetUuid", player.uuid().toString())
                .put("revokeCause", "manual")
                .put("revokedBy", "Adam")
                .build();
    }

    private static List<PunishmentOutbox.Entry> queued(PunishmentsHarness harness, String op) {
        List<PunishmentOutbox.Entry> out = new ArrayList<PunishmentOutbox.Entry>();
        for (PunishmentOutbox.Entry entry : harness.module.outboxForTest().snapshot()) {
            if (op.equals(entry.op)) out.add(entry);
        }
        return out;
    }

    private static String text(Verdict verdict) {
        return TestText.plain(verdict.reason());
    }

    @Test
    @DisplayName("/hd freeze works in hook mode, where every other verb is LiteBans'")
    void freezeInHookMode() {
        try (PunishmentsHarness harness = enable(
                new PunishmentsHarness(dataDir, ServerRole.STANDALONE), "hook")) {
            FakePlayer steve = harness.platform.join(FakePlayer.named("Steve"));
            FakeCommandSource mod = FakeCommandSource.console();

            harness.module.onStaffCommand(mod, "freeze", Arrays.asList("Steve", "x-ray", "check"));

            assertTrue(harness.module.isFrozen(steve.uuid()), "frozen locally at once");
            List<PunishmentOutbox.Entry> issues = queued(harness, "issue");
            assertEquals(1, issues.size());
            assertEquals("freeze", issues.get(0).payload.string("type", ""));
            assertEquals("x-ray check", issues.get(0).payload.string("reason", ""));
            assertTrue(issues.get(0).payload.bool("silent", false), "a freeze is always silent");
            assertTrue(steve.messageText().get(0).contains("You are frozen"),
                    steve.messageText().toString());

            // The ban verbs stay LiteBans' in hook mode.
            mod.clearMessages();
            harness.module.onStaffCommand(mod, "ban", Arrays.asList("Steve", "griefing"));
            assertTrue(mod.wasTold("not in replace mode"), mod.messageText().toString());

            harness.module.onStaffCommand(mod, "unfreeze", Arrays.asList("Steve"));
            assertFalse(harness.module.isFrozen(steve.uuid()));
            assertEquals(1, queued(harness, "revoke").size());
            assertEquals("freeze", queued(harness, "revoke").get(0).payload.string("type", ""));
            assertTrue(steve.messageText().contains("You are no longer frozen."),
                    steve.messageText().toString());
        }
    }

    @Test
    @DisplayName("offenses-only mode issues no freeze")
    void offensesOnlyRefuses() {
        try (PunishmentsHarness harness = enable(
                new PunishmentsHarness(dataDir, ServerRole.STANDALONE), "offenses-only")) {
            harness.platform.join(FakePlayer.named("Steve"));
            FakeCommandSource mod = FakeCommandSource.console();
            harness.module.onStaffCommand(mod, "freeze", Arrays.asList("Steve"));
            assertTrue(mod.wasTold("not in replace mode"), mod.messageText().toString());
            assertTrue(queued(harness, "issue").isEmpty());
        }
    }

    @Test
    @DisplayName("a freeze takes no length, and -p cannot make one public")
    void noLengthAndAlwaysSilent() {
        try (PunishmentsHarness harness = enable(
                new PunishmentsHarness(dataDir, ServerRole.STANDALONE), "replace")) {
            harness.platform.join(FakePlayer.named("Steve"));
            FakeCommandSource mod = FakeCommandSource.console();
            harness.module.onStaffCommand(mod, "freeze", Arrays.asList("Steve", "7d", "check"));
            assertTrue(mod.wasTold("has no length"), mod.messageText().toString());
            assertTrue(queued(harness, "issue").isEmpty());

            FakeCommandSource plain = FakeCommandSource.player("Mod")
                    .grant(FrozenPlayers.PERMISSION);
            harness.module.onStaffCommand(plain, "freeze", Arrays.asList("Steve", "-p", "check"));
            assertEquals(1, queued(harness, "issue").size(), plain.messageText().toString());
            assertTrue(queued(harness, "issue").get(0).payload.bool("silent", false));
        }
    }

    @Test
    @DisplayName("in hook mode a frozen player's chat goes to staff, and their commands are refused")
    void chatToStaffAndCommandsBlocked() {
        try (PunishmentsHarness harness = enable(
                new PunishmentsHarness(dataDir, ServerRole.ENFORCER), "hook")) {
            FakePlayer steve = harness.platform.join(FakePlayer.named("Steve"));
            FakePlayer mod = harness.platform.join(FakePlayer.named("Mod")
                    .grant(FrozenPlayers.PERMISSION));
            FakePlayer admin = harness.platform.join(FakePlayer.named("Admin")
                    .grant("heimdall.admin"));
            FakePlayer alex = harness.platform.join(FakePlayer.named("Alex"));
            harness.tunnel.push("punish.apply", freezeFrame(steve, "64b0000000000000000000f1", false));

            Verdict chat = harness.chatPipeline.dispatch(
                    ChatMessage.of(steve.uuid(), "Steve", "what did I do"));
            assertTrue(chat.isDeny(), "not shown publicly");
            assertTrue(text(chat).contains("[Frozen] Steve: what did I do"), text(chat));
            assertTrue(mod.messageText().contains("[Frozen] Steve: what did I do"),
                    mod.messageText().toString());
            assertTrue(admin.messageText().contains("[Frozen] Steve: what did I do"),
                    admin.messageText().toString());
            assertTrue(alex.messages().isEmpty(), alex.messageText().toString());

            Verdict command = harness.commandPipeline.dispatch(
                    CommandAttempt.of(steve.uuid(), "Steve", "/spawn"));
            assertTrue(command.isDeny());
            assertTrue(text(command).contains("You are frozen"), text(command));

            // Alex is not frozen and nothing stops them.
            assertFalse(harness.chatPipeline.dispatch(
                    ChatMessage.of(alex.uuid(), "Alex", "hi")).isDeny());
        }
    }

    @Test
    @DisplayName("a hidden freeze's chat reaches only holders of the hidden node")
    void hiddenFreezeNarrowsTheAudience() {
        try (PunishmentsHarness harness = enable(
                new PunishmentsHarness(dataDir, ServerRole.STANDALONE), "replace")) {
            FakePlayer steve = harness.platform.join(FakePlayer.named("Steve"));
            FakePlayer mod = harness.platform.join(FakePlayer.named("Mod")
                    .grant(FrozenPlayers.PERMISSION));
            FakePlayer senior = harness.platform.join(FakePlayer.named("Senior")
                    .grant(HiddenPunishments.PERMISSION));
            harness.tunnel.push("punish.apply", freezeFrame(steve, "64b0000000000000000000f2", true));
            int modBefore = mod.messages().size();

            harness.chatPipeline.dispatch(ChatMessage.of(steve.uuid(), "Steve", "hello"));
            assertEquals(modBefore, mod.messages().size(), mod.messageText().toString());
            assertTrue(senior.messageText().contains("[Frozen] (hidden) Steve: hello"),
                    senior.messageText().toString());
            Verdict echo = harness.chatPipeline.dispatch(
                    ChatMessage.of(steve.uuid(), "Steve", "again"));
            assertEquals("[Frozen] Steve: again", text(echo),
                    "the player is never told their freeze is hidden");
        }
    }

    @Test
    @DisplayName("the player is told on the server they play on, and the proxy stays quiet")
    void toldWhereTheyPlay() {
        try (PunishmentsHarness backend = enable(
                new PunishmentsHarness(dataDir.resolve("b"), ServerRole.ENFORCER), "replace")) {
            FakePlayer steve = backend.platform.join(FakePlayer.named("Steve"));
            backend.tunnel.push("punish.apply", freezeFrame(steve, "64b0000000000000000000f3", false));
            assertEquals(1, steve.messages().size(), steve.messageText().toString());
            assertTrue(steve.messageText().get(0).contains("x-ray check"));

            backend.tunnel.push("punish.revoke", revokeFrame(steve));
            assertFalse(backend.module.isFrozen(steve.uuid()));
            assertEquals("You are no longer frozen.", steve.messageText().get(1));
        }
        try (PunishmentsHarness proxy = enable(
                new PunishmentsHarness(dataDir.resolve("p"), ServerRole.GATEKEEPER), "replace")) {
            FakePlayer steve = proxy.platform.join(FakePlayer.named("Steve"));
            proxy.tunnel.push("punish.apply", freezeFrame(steve, "64b0000000000000000000f4", false));
            assertTrue(proxy.module.isFrozen(steve.uuid()), "the proxy mirror still holds it");
            proxy.tunnel.push("punish.revoke", revokeFrame(steve));
            assertTrue(steve.messages().isEmpty(), steve.messageText().toString());
        }
    }

    @Test
    @DisplayName("a player frozen while away is told when they join")
    void toldOnJoin() {
        try (PunishmentsHarness harness = enable(
                new PunishmentsHarness(dataDir, ServerRole.ENFORCER), "replace")) {
            FakePlayer steve = FakePlayer.named("Steve");
            harness.tunnel.push("punish.apply", freezeFrame(steve, "64b0000000000000000000f5", false));
            harness.platform.join(steve);
            harness.sessions.join(steve, System.currentTimeMillis());
            assertEquals(1, steve.messages().size(), steve.messageText().toString());
            assertTrue(steve.messageText().get(0).contains("You are frozen"));
        }
    }

    @Test
    @DisplayName("a blank freeze screen falls back to the default rather than saying nothing")
    void blankScreenFallsBack() {
        try (PunishmentsHarness harness = new PunishmentsHarness(dataDir, ServerRole.ENFORCER)
                .enableWith(Payload.builder()
                        .put("mode", "replace")
                        .put(PunishmentScreens.KEY_FREEZE, "   ")
                        .build())) {
            FakePlayer steve = harness.platform.join(FakePlayer.named("Steve"));
            harness.tunnel.push("punish.apply", freezeFrame(steve, "64b0000000000000000000f6", false));
            assertTrue(steve.messageText().get(0).startsWith("You are frozen."),
                    steve.messageText().toString());
        }
    }

    @Test
    @DisplayName("logging out while frozen alerts staff, and holds a ban only when it is on")
    void logoutAlertsAndHoldsBan() {
        try (PunishmentsHarness harness = enable(
                new PunishmentsHarness(dataDir, ServerRole.STANDALONE), "replace", true, 86_400L)) {
            FakePlayer steve = harness.platform.join(FakePlayer.named("Steve"));
            FakePlayer mod = harness.platform.join(FakePlayer.named("Mod")
                    .grant(FrozenPlayers.PERMISSION));
            harness.tunnel.push("punish.apply", freezeFrame(steve, "64b0000000000000000000f7", false));

            harness.platform.leave(steve);
            harness.sessions.quit(steve, System.currentTimeMillis());

            String alert = mod.messageText().get(mod.messageText().size() - 1);
            assertTrue(alert.contains("Steve logged out while frozen."), alert);
            assertTrue(alert.contains("will be banned"), alert);
            assertTrue(harness.module.frozenForTest().holding(steve.uuid()));

            // Back in time: nothing fires.
            harness.platform.join(steve);
            harness.sessions.join(steve, System.currentTimeMillis());
            assertFalse(harness.module.frozenForTest().holding(steve.uuid()));
        }
    }

    @Test
    @DisplayName("on the proxy a frozen player's own commands are refused, and they are told")
    void proxyCommandsRefused() {
        try (PunishmentsHarness harness = enable(
                new PunishmentsHarness(dataDir, ServerRole.GATEKEEPER), "hook")) {
            FakePlayer steve = harness.platform.join(FakePlayer.named("Steve"));
            FakePlayer alex = harness.platform.join(FakePlayer.named("Alex"));
            assertFalse(harness.module.refuseFrozenProxyCommand(steve.uuid()));
            harness.tunnel.push("punish.apply", freezeFrame(steve, "64b0000000000000000000fc", false));

            assertTrue(harness.module.refuseFrozenProxyCommand(steve.uuid()));
            String told = steve.messageText().get(steve.messageText().size() - 1);
            assertTrue(told.contains("commands are blocked"), told);
            assertFalse(harness.module.refuseFrozenProxyCommand(alex.uuid()));

            harness.tunnel.push("punish.revoke", revokeFrame(steve));
            assertFalse(harness.module.refuseFrozenProxyCommand(steve.uuid()));
        }
    }

    @Test
    @DisplayName("the held ban, once fired, bans for the configured length and for the fixed reason")
    void logoutBanFires() {
        try (PunishmentsHarness harness = enable(
                new PunishmentsHarness(dataDir, ServerRole.STANDALONE), "replace", true, 86_400L)) {
            FakePlayer steve = FakePlayer.named("Steve");
            harness.tunnel.push("punish.apply", freezeFrame(steve, "64b0000000000000000000f8", false));

            harness.module.banFrozenLogout(steve.uuid(), "Steve");

            List<PunishmentOutbox.Entry> issues = queued(harness, "issue");
            assertEquals(1, issues.size());
            Payload ban = issues.get(0).payload;
            assertEquals("ban", ban.string("type", ""));
            assertEquals(steve.uuid().toString(), ban.string("targetUuid", ""));
            assertEquals(FrozenPlayers.LOGOUT_BAN_REASON, ban.string("reason", ""));
            assertEquals(86_400L, ban.longValue("durationSeconds", 0L));
            assertEquals("Heimdall", ban.string("issuedByName", ""));

            // Already banned: a second firing does nothing.
            harness.module.banFrozenLogout(steve.uuid(), "Steve");
            assertEquals(1, queued(harness, "issue").size());
        }
    }

    @Test
    @DisplayName("the held ban does nothing once the player is back, unfrozen, or the setting is off")
    void logoutBanStandsDown() {
        try (PunishmentsHarness harness = enable(
                new PunishmentsHarness(dataDir, ServerRole.STANDALONE), "replace", true, 0L)) {
            FakePlayer steve = FakePlayer.named("Steve");
            harness.tunnel.push("punish.apply", freezeFrame(steve, "64b0000000000000000000f9", false));

            harness.platform.join(steve);
            harness.module.banFrozenLogout(steve.uuid(), "Steve");
            assertTrue(queued(harness, "issue").isEmpty(), "back online");
            harness.platform.leave(steve);

            harness.tunnel.push("punish.revoke", revokeFrame(steve));
            harness.module.banFrozenLogout(steve.uuid(), "Steve");
            assertTrue(queued(harness, "issue").isEmpty(), "unfrozen");
        }
        try (PunishmentsHarness off = enable(
                new PunishmentsHarness(dataDir.resolve("off"), ServerRole.STANDALONE), "replace")) {
            FakePlayer steve = off.platform.join(FakePlayer.named("Steve"));
            FakePlayer mod = off.platform.join(FakePlayer.named("Mod")
                    .grant(FrozenPlayers.PERMISSION));
            off.tunnel.push("punish.apply", freezeFrame(steve, "64b0000000000000000000fa", false));
            off.platform.leave(steve);
            off.sessions.quit(steve, System.currentTimeMillis());
            String alert = mod.messageText().get(mod.messageText().size() - 1);
            assertTrue(alert.contains("logged out while frozen."), alert);
            assertFalse(alert.contains("will be banned"), alert);
            assertFalse(off.module.frozenForTest().holding(steve.uuid()));
        }
    }

    @Test
    @DisplayName("a backend never treats a quit as a logout: it is a server switch as often as not")
    void backendIgnoresQuit() {
        try (PunishmentsHarness harness = enable(
                new PunishmentsHarness(dataDir, ServerRole.ENFORCER), "replace", true, 0L)) {
            FakePlayer steve = harness.platform.join(FakePlayer.named("Steve"));
            FakePlayer mod = harness.platform.join(FakePlayer.named("Mod")
                    .grant(FrozenPlayers.PERMISSION));
            harness.tunnel.push("punish.apply", freezeFrame(steve, "64b0000000000000000000fb", false));
            int before = mod.messages().size();
            harness.platform.leave(steve);
            harness.sessions.quit(steve, System.currentTimeMillis());
            assertEquals(before, mod.messages().size(), mod.messageText().toString());
            assertFalse(harness.module.frozenForTest().holding(steve.uuid()));
        }
    }

    @Test
    @DisplayName("in hook mode the logout ban goes through LiteBans, and is not held without it")
    void hookModeBansThroughLiteBans() {
        try (PunishmentsHarness harness = enable(
                new PunishmentsHarness(dataDir, ServerRole.STANDALONE), "hook", true, 7_200L)) {
            FakePlayer steve = harness.platform.join(FakePlayer.named("Steve"));
            harness.tunnel.push("punish.apply", freezeFrame(steve, "64b0000000000000000000fc", false));
            harness.platform.leave(steve);
            harness.sessions.quit(steve, System.currentTimeMillis());
            assertFalse(harness.module.frozenForTest().holding(steve.uuid()),
                    "no LiteBans here, so no ban anybody would enforce");

            LiteBansSupport.activeCheckForTest(new LiteBansRevokeLift.ActiveCheck() {
                @Override
                public boolean isActive(LiteBansRevokeLift.Family family, UUID uuid) {
                    return false;
                }
            });
            harness.module.banFrozenLogout(steve.uuid(), "Steve");
            assertTrue(queued(harness, "issue").isEmpty(), "LiteBans records it, not the outbox");
            List<String> ran = harness.platform.dispatchedCommands();
            assertEquals(1, ran.size(), ran.toString());
            // Always -s: the reason names the freeze, which is only ever told to staff.
            assertEquals("ban " + steve.uuid() + " 2h -s " + FrozenPlayers.LOGOUT_BAN_REASON,
                    ran.get(0).replace("litebans:ban", "ban"));
        }
    }

    @Test
    @DisplayName("a hidden freeze in hook mode holds no logout ban: LiteBans would show it to all")
    void hiddenFreezeInHookModeIsNotBanned() {
        try (PunishmentsHarness harness = enable(
                new PunishmentsHarness(dataDir, ServerRole.STANDALONE), "hook", true, 0L)) {
            LiteBansSupport.activeCheckForTest(new LiteBansRevokeLift.ActiveCheck() {
                @Override
                public boolean isActive(LiteBansRevokeLift.Family family, UUID uuid) {
                    return false;
                }
            });
            FakePlayer steve = harness.platform.join(FakePlayer.named("Steve"));
            harness.tunnel.push("punish.apply", freezeFrame(steve, "64b000000000000000000101", true));
            harness.platform.leave(steve);
            harness.sessions.quit(steve, System.currentTimeMillis());
            assertFalse(harness.module.frozenForTest().holding(steve.uuid()));
            harness.module.banFrozenLogout(steve.uuid(), "Steve");
            assertTrue(harness.platform.dispatchedCommands().isEmpty(),
                    harness.platform.dispatchedCommands().toString());
        }
    }

    @Test
    @DisplayName("the replace-mode logout ban is silent even where the guild announces")
    void logoutBanIsSilent() {
        try (PunishmentsHarness harness = harnessAnnouncing(true, 0L)) {
            FakePlayer steve = FakePlayer.named("Steve");
            harness.tunnel.push("punish.apply", freezeFrame(steve, "64b000000000000000000102", false));
            harness.module.banFrozenLogout(steve.uuid(), "Steve");
            List<PunishmentOutbox.Entry> issues = queued(harness, "issue");
            assertEquals(1, issues.size());
            assertTrue(issues.get(0).payload.bool("silent", false));
        }
    }

    private PunishmentsHarness harnessAnnouncing(boolean logoutBan, long seconds) {
        return new PunishmentsHarness(dataDir, ServerRole.STANDALONE).enableWith(Payload.builder()
                .put("mode", "replace")
                .put("ipSalt", "salt")
                .put("rootAliases", false)
                .put("silentByDefault", false)
                .put(PunishmentSettings.KEY_FREEZE_LOGOUT_BAN, logoutBan)
                .put(PunishmentSettings.KEY_FREEZE_LOGOUT_BAN_SECONDS, seconds)
                .build());
    }

    @Test
    @DisplayName("a frozen player kicked by something else is reported as kicked, and the ban still holds")
    void otherKickStillHoldsTheBan() {
        try (PunishmentsHarness harness = enable(
                new PunishmentsHarness(dataDir, ServerRole.STANDALONE), "replace", true, 0L)) {
            FakePlayer steve = harness.platform.join(FakePlayer.named("Steve"));
            FakePlayer mod = harness.platform.join(FakePlayer.named("Mod")
                    .grant(FrozenPlayers.PERMISSION));
            harness.tunnel.push("punish.apply", freezeFrame(steve, "64b000000000000000000103", false));

            // A kick a player can provoke (spam, a bad packet) must not be a way out of a check.
            harness.module.noteKicked(steve.uuid());
            harness.platform.leave(steve);
            harness.sessions.quit(steve, System.currentTimeMillis());

            String alert = mod.messageText().get(mod.messageText().size() - 1);
            assertTrue(alert.contains("was kicked while frozen"), alert);
            assertTrue(alert.contains("will be banned unless"), alert);
            assertTrue(harness.module.frozenForTest().holding(steve.uuid()));
        }
    }

    @Test
    @DisplayName("a frozen player kicked by Heimdall is reported as kicked, and no ban is held")
    void heimdallKickIsNotALogout() {
        try (PunishmentsHarness harness = enable(
                new PunishmentsHarness(dataDir, ServerRole.STANDALONE), "replace", true, 0L)) {
            FakePlayer steve = harness.platform.join(FakePlayer.named("Steve"));
            FakePlayer mod = harness.platform.join(FakePlayer.named("Mod")
                    .grant(FrozenPlayers.PERMISSION));
            harness.tunnel.push("punish.apply", freezeFrame(steve, "64b000000000000000000108", false));

            harness.tunnel.push("punish.apply", Payload.builder()
                    .put("type", "kick")
                    .put("targetUuid", steve.uuid().toString())
                    .put("targetName", "Steve")
                    .put("reason", "go cool off")
                    .build());
            assertEquals(1, steve.kickReasons().size());
            // The platform's own kick event follows Heimdall's kick; it must not downgrade it.
            harness.module.noteKicked(steve.uuid());
            harness.platform.leave(steve);
            harness.sessions.quit(steve, System.currentTimeMillis());

            String alert = mod.messageText().get(mod.messageText().size() - 1);
            assertTrue(alert.contains("was kicked while frozen"), alert);
            assertFalse(alert.contains("will be banned"), alert);
            assertFalse(harness.module.frozenForTest().holding(steve.uuid()));

            // The kick is used up: a later logout of their own is a logout again.
            harness.platform.join(steve);
            harness.sessions.join(steve, System.currentTimeMillis());
            harness.platform.leave(steve);
            harness.sessions.quit(steve, System.currentTimeMillis());
            assertTrue(harness.module.frozenForTest().holding(steve.uuid()));
        }
    }

    @Test
    @DisplayName("on a network, a Heimdall kick the backend carried out first calls the ban off")
    void heimdallKickAfterTheBackendsKick() {
        try (PunishmentsHarness proxy = enable(
                new PunishmentsHarness(dataDir, ServerRole.GATEKEEPER), "replace", true, 0L)) {
            FakePlayer steve = proxy.platform.join(FakePlayer.named("Steve"));
            FakePlayer mod = proxy.platform.join(FakePlayer.named("Mod")
                    .grant(FrozenPlayers.PERMISSION));
            proxy.tunnel.push("punish.apply", freezeFrame(steve, "64b000000000000000000109", false));

            // The backend kicked him; the proxy only saw a kick it could not place.
            proxy.module.noteKicked(steve.uuid());
            proxy.platform.leave(steve);
            proxy.sessions.quit(steve, System.currentTimeMillis());
            assertTrue(proxy.module.frozenForTest().holding(steve.uuid()));

            proxy.tunnel.push("punish.apply", Payload.builder()
                    .put("type", "kick")
                    .put("targetUuid", steve.uuid().toString())
                    .put("targetName", "Steve")
                    .put("reason", "go cool off")
                    .build());
            assertFalse(proxy.module.frozenForTest().holding(steve.uuid()));
            assertTrue(mod.messageText().toString().contains("was kicked by staff"),
                    mod.messageText().toString());
        }
    }

    @Test
    @DisplayName("an IP ban on a frozen player is not a logout, and is not banned over")
    void ipBannedWhileFrozenIsQuiet() {
        try (PunishmentsHarness harness = enable(
                new PunishmentsHarness(dataDir, ServerRole.STANDALONE), "replace", true, 0L)) {
            FakePlayer steve = harness.platform.join(FakePlayer.named("Steve"));
            FakePlayer mod = harness.platform.join(FakePlayer.named("Mod")
                    .grant(FrozenPlayers.PERMISSION));
            harness.tunnel.push("punish.apply", freezeFrame(steve, "64b00000000000000000010a", false));
            int before = mod.messageText().size();

            ActivePunishment ipban = new ActivePunishment();
            ipban.id = "64b00000000000000000010b";
            ipban.type = "ipban";
            ipban.ipDigest = "digest";
            ipban.targetUuid = steve.uuid().toString();
            ipban.targetName = "Steve";
            harness.module.mirrorForTest().record("ipban:digest", ipban);
            harness.platform.leave(steve);
            harness.sessions.quit(steve, System.currentTimeMillis());

            assertEquals(before, mod.messageText().size(), mod.messageText().toString());
            assertFalse(harness.module.frozenForTest().holding(steve.uuid()));
            harness.module.banFrozenLogout(steve.uuid(), "Steve");
            assertTrue(queued(harness, "issue").isEmpty());
        }
    }

    @Test
    @DisplayName("a held ban is called off when the server that kicked them does not answer")
    void serverDownIsNotBanned() throws InterruptedException {
        try (PunishmentsHarness harness = enable(
                new PunishmentsHarness(dataDir, ServerRole.STANDALONE), "replace", true, 0L)) {
            final FakePlayer steve = harness.platform.join(FakePlayer.named("Steve"));
            FakePlayer mod = harness.platform.join(FakePlayer.named("Mod")
                    .grant(FrozenPlayers.PERMISSION));
            harness.tunnel.push("punish.apply", freezeFrame(steve, "64b00000000000000000010c", false));
            harness.platform.leave(steve);

            harness.module.fireLogoutBanForTest(steve.uuid(), "Steve", false, answer -> answer.accept(false));
            String alert = mod.messageText().get(mod.messageText().size() - 1);
            assertTrue(alert.contains("the server they were on is down"), alert);
            assertTrue(queued(harness, "issue").isEmpty());

            harness.module.fireLogoutBanForTest(steve.uuid(), "Steve", false, answer -> answer.accept(true));
            waitFor(() -> queued(harness, "issue").size() == 1, 5_000L);
        }
    }

    @Test
    @DisplayName("hook mode: a ban LiteBans already holds stands the logout ban down")
    void liteBansBanStandsDown() throws InterruptedException {
        final boolean[] banned = {true};
        LiteBansSupport.activeCheckForTest(new LiteBansRevokeLift.ActiveCheck() {
            @Override
            public boolean isActive(LiteBansRevokeLift.Family family, UUID uuid) {
                return banned[0];
            }
        });
        try (PunishmentsHarness harness = enable(
                new PunishmentsHarness(dataDir, ServerRole.STANDALONE), "hook", true, 0L)) {
            final FakePlayer steve = harness.platform.join(FakePlayer.named("Steve"));
            FakePlayer mod = harness.platform.join(FakePlayer.named("Mod")
                    .grant(FrozenPlayers.PERMISSION));
            harness.tunnel.push("punish.apply", freezeFrame(steve, "64b00000000000000000010d", false));
            int before = mod.messageText().size();

            harness.module.banFrozenLogout(steve.uuid(), "Steve");
            assertTrue(harness.platform.dispatchedCommands().isEmpty(),
                    "the console's ban would override the one LiteBans holds");

            harness.platform.leave(steve);
            harness.sessions.quit(steve, System.currentTimeMillis());
            Thread.sleep(300L);
            assertEquals(before, mod.messageText().size(), mod.messageText().toString());
            assertFalse(harness.module.frozenForTest().holding(steve.uuid()));

            // Not banned after all: the same quit holds the ban, off the main thread.
            banned[0] = false;
            harness.platform.join(steve);
            harness.sessions.join(steve, System.currentTimeMillis());
            harness.platform.leave(steve);
            harness.sessions.quit(steve, System.currentTimeMillis());
            waitFor(() -> harness.module.frozenForTest().holding(steve.uuid()), 5_000L);
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

    @Test
    @DisplayName("a frozen player banned by staff gets no logout alert and no held ban")
    void bannedWhileFrozenIsQuiet() {
        try (PunishmentsHarness harness = enable(
                new PunishmentsHarness(dataDir, ServerRole.STANDALONE), "replace", true, 0L)) {
            FakePlayer steve = harness.platform.join(FakePlayer.named("Steve"));
            FakePlayer mod = harness.platform.join(FakePlayer.named("Mod")
                    .grant(FrozenPlayers.PERMISSION));
            harness.tunnel.push("punish.apply", freezeFrame(steve, "64b000000000000000000104", false));
            harness.tunnel.push("punish.apply", Payload.builder()
                    .put("id", "64b000000000000000000105")
                    .put("type", "ban")
                    .put("targetUuid", steve.uuid().toString())
                    .put("targetName", "Steve")
                    .put("reason", "cheating")
                    .put("source", "dashboard")
                    .put("silent", true)
                    .build());
            int before = mod.messages().size();
            harness.platform.leave(steve);
            harness.sessions.quit(steve, System.currentTimeMillis());
            assertFalse(harness.module.frozenForTest().holding(steve.uuid()));
            for (String line : mod.messageText().subList(before, mod.messageText().size())) {
                assertFalse(line.contains("logged out while frozen"), line);
            }
        }
    }

    @Test
    @DisplayName("offenses-only enforces no freeze, even one left over from another mode")
    void offensesOnlyEnforcesNothing() {
        try (PunishmentsHarness harness = enable(
                new PunishmentsHarness(dataDir, ServerRole.STANDALONE), "replace")) {
            FakePlayer steve = harness.platform.join(FakePlayer.named("Steve"));
            harness.tunnel.push("punish.apply", freezeFrame(steve, "64b000000000000000000106", false));
            assertTrue(harness.module.isFrozen(steve.uuid()));

            enable(harness, "offenses-only");
            assertFalse(harness.module.isFrozen(steve.uuid()));
            assertFalse(harness.chatPipeline.dispatch(
                    ChatMessage.of(steve.uuid(), "Steve", "free")).isDeny());
            assertFalse(harness.commandPipeline.dispatch(
                    CommandAttempt.of(steve.uuid(), "Steve", "/spawn")).isDeny());
        }
    }

    @Test
    @DisplayName("a freeze replaced by a new one does not tell the player they are free")
    void overrideIsNotARelease() {
        try (PunishmentsHarness harness = enable(
                new PunishmentsHarness(dataDir, ServerRole.STANDALONE), "replace", true, 0L)) {
            FakePlayer steve = harness.platform.join(FakePlayer.named("Steve"));
            harness.tunnel.push("punish.apply", freezeFrame(steve, "64b000000000000000000107", false));
            harness.platform.leave(steve);
            harness.sessions.quit(steve, System.currentTimeMillis());
            assertTrue(harness.module.frozenForTest().holding(steve.uuid()));

            harness.tunnel.push("punish.revoke", Payload.builder()
                    .put("type", "freeze")
                    .put("targetUuid", steve.uuid().toString())
                    .put("revokeCause", "override")
                    .build());
            assertTrue(harness.module.frozenForTest().holding(steve.uuid()),
                    "the new freeze still stands, and so does the ban waiting on it");
            assertFalse(steve.messageText().contains("You are no longer frozen."),
                    steve.messageText().toString());
        }
    }

    @Test
    @DisplayName("nobody can freeze themselves, since they could never run /unfreeze")
    void noSelfFreeze() {
        try (PunishmentsHarness harness = enable(
                new PunishmentsHarness(dataDir, ServerRole.STANDALONE), "replace")) {
            FakePlayer mod = harness.platform.join(FakePlayer.named("Mod")
                    .grant(FrozenPlayers.PERMISSION));
            FakeCommandSource source = FakeCommandSource.player(mod.uuid(), "Mod")
                    .grant(FrozenPlayers.PERMISSION);
            harness.module.onStaffCommand(source, "freeze", Arrays.asList("mod"));
            assertTrue(source.wasTold("cannot freeze yourself"), source.messageText().toString());
            assertFalse(harness.module.isFrozen(mod.uuid()));
            assertTrue(queued(harness, "issue").isEmpty());
        }
    }

    @Test
    @DisplayName("unfreeze completes only names that are frozen")
    void unfreezeCompletesFrozenNames() {
        try (PunishmentsHarness harness = enable(
                new PunishmentsHarness(dataDir, ServerRole.STANDALONE), "replace")) {
            FakePlayer steve = harness.platform.join(FakePlayer.named("Steve"));
            harness.platform.join(FakePlayer.named("Sam"));
            harness.sessions.join(steve, 1L);
            harness.tunnel.push("punish.apply", freezeFrame(steve, "64b0000000000000000000fd", false));
            List<String> offered = harness.module.complete(
                    FakeCommandSource.console(), "unfreeze", Arrays.asList("S"));
            assertEquals(Arrays.asList("Steve"), offered);
        }
    }

    @Test
    @DisplayName("LiteBans lengths use the largest exact unit")
    void liteBansDuration() {
        assertNull(FrozenPlayers.liteBansDuration(0L));
        assertEquals("2d", FrozenPlayers.liteBansDuration(172_800L));
        assertEquals("3h", FrozenPlayers.liteBansDuration(10_800L));
        assertEquals("2m", FrozenPlayers.liteBansDuration(61L));
        assertNotNull(FrozenPlayers.liteBansDuration(1L));
    }
}
