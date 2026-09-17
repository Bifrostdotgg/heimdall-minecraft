package com.heimdall.module.punishments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.config.ServerRole;
import com.heimdall.core.json.Payload;
import com.heimdall.core.punish.HiddenPunishments;
import com.heimdall.core.punish.PunishmentAnnouncement;
import com.heimdall.core.punish.SilenceDecision;
import com.heimdall.core.testing.FakeCommandSource;
import com.heimdall.core.testing.FakePlayer;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code -h}: a punishment that is enforced normally and kept out of in-game staff lookups.
 *
 * <p>Two halves, and both are tested because they fail differently. Issuing is a permission gate on
 * one command path. Reading is a filter that has to hold on <em>every</em> lookup, over two
 * different sources of rows: {@code /banlist} and the offline fallbacks read the local mirror, while
 * {@code /history} and {@code /staffhistory} ask the bot. A filter that covered only one of those
 * would hide a punishment right up until the bot went unreachable, or the reverse.
 *
 * <p>Sources here are always {@link FakeCommandSource#player}, never {@code console()}: the console
 * holds every permission, so a console lookup can only ever prove the permitted case.
 */
class HiddenPunishmentTest {

    @TempDir
    Path dataDir;

    private static final String HIDDEN_ROWS =
            "{\"success\":true,\"data\":{\"punishments\":["
            + "{\"id\":\"p1\",\"type\":\"ban\",\"targetName\":\"Steve\",\"issuedByName\":\"Adam\","
            + "\"reason\":\"griefing\",\"active\":true},"
            + "{\"id\":\"p2\",\"type\":\"ban\",\"targetName\":\"Steve\",\"issuedByName\":\"Adam\","
            + "\"reason\":\"alting\",\"active\":true,\"hidden\":true}]}}";

    @Test
    @DisplayName("-h without the node refuses the punishment rather than issuing a visible one")
    void hiddenWithoutTheNodeIsRefused() {
        try (PunishmentsHarness harness = announcing()) {
            FakePlayer bystander = harness.platform.join(FakePlayer.named("Notch"));
            harness.platform.join(FakePlayer.named("Steve"));
            FakeCommandSource moderator = FakeCommandSource.player("Adam");

            harness.module.onStaffCommand(moderator, "ban", Arrays.asList("Steve", "-h", "alting"));

            assertTrue(moderator.wasTold(HiddenPunishments.REFUSAL_MESSAGE),
                    moderator.messageText().toString());
            assertNull(harness.module.mirrorForTest().get("ban:" + uuidOf("Steve")),
                    "the ban is refused, not quietly downgraded to a visible one: a moderator who "
                            + "believes a record is hidden must not be handed a public one");
            assertTrue(harness.module.outboxForTest().isEmpty(), "and nothing is queued for the bot");
            assertTrue(bystander.messageText().isEmpty(), "and nothing was announced");
        }
    }

    @Test
    @DisplayName("the hidden node alone issues a hidden, silent punishment on an announcing guild")
    void hiddenNodeAloneIsEnough() {
        try (PunishmentsHarness harness = announcing()) {
            FakePlayer ordinary = harness.platform.join(FakePlayer.named("Notch"));
            harness.platform.join(FakePlayer.named("Steve"));
            // Deliberately without SilenceDecision.OVERRIDE_PERMISSION: hidden implies silent, and
            // the hidden node grants the stronger act, so requiring the weaker one too would refuse
            // a permitted sender for a flag they never typed.
            FakeCommandSource moderator = FakeCommandSource.player("Adam")
                    .grant(HiddenPunishments.PERMISSION);

            harness.module.onStaffCommand(moderator, "ban", Arrays.asList("Steve", "-h", "alting"));

            assertFalse(moderator.wasTold(SilenceDecision.REFUSAL_MESSAGE),
                    moderator.messageText().toString());
            assertFalse(moderator.wasTold(HiddenPunishments.REFUSAL_MESSAGE));
            ActivePunishment stored = harness.module.mirrorForTest().get("ban:" + uuidOf("Steve"));
            assertNotNull(stored);
            assertTrue(stored.hidden, "the mirror row remembers it, or the next lookup shows it");
            assertTrue(stored.silent, "hidden implies silent on the row as well as on the wire");
            assertTrue(ordinary.messageText().isEmpty(),
                    "an announced hidden punishment would defeat itself: " + ordinary.messageText());

            List<PunishmentOutbox.Entry> queued = harness.module.outboxForTest().snapshot();
            assertEquals(1, queued.size());
            assertTrue(queued.get(0).payload.bool("hidden", false), "the issue body carries hidden");
            assertTrue(queued.get(0).payload.bool("silent", false),
                    "and silent beside it, so the plugin and the bot agree rather than the bot "
                            + "having to correct the plugin");
        }
    }

    @Test
    @DisplayName("a hidden punishment is still enforced, and still announced to holders of the node")
    void hiddenIsStillEnforced() {
        try (PunishmentsHarness harness = announcing()) {
            FakePlayer steve = harness.platform.join(FakePlayer.named("Steve"));
            FakePlayer notify = harness.platform.join(
                    FakePlayer.named("Mod").grant(PunishmentAnnouncement.NOTIFY_PERMISSION));
            FakeCommandSource moderator = FakeCommandSource.player("Adam")
                    .grant(HiddenPunishments.PERMISSION);

            harness.module.onStaffCommand(moderator, "ban", Arrays.asList("Steve", "-h", "alting"));

            assertEquals(1, steve.kickReasons().size(),
                    "hiding a punishment is about who reads about it, never about whether it lands");
            assertTrue(notify.messageText().toString().contains("(silent)"),
                    "hidden is silent, and silent still reaches the notify audience in chat; the "
                            + "hiding this feature adds is of the lookup rows");
        }
    }

    @Test
    @DisplayName("/banlist omits a hidden ban for a reader without the node and marks it for one with it")
    void banlistFiltersTheMirror() {
        try (PunishmentsHarness harness = announcing()) {
            banInMirror(harness, "Steve", false, "griefing");
            banInMirror(harness, "Alex", true, "alting");

            FakeCommandSource ordinary = FakeCommandSource.player("Mod");
            harness.module.onStaffCommand(ordinary, "banlist", Arrays.asList());
            String seen = String.join("\n", ordinary.messageText());
            assertTrue(seen.contains("Steve"), seen);
            assertFalse(seen.contains("Alex"), "a hidden ban is not on somebody else's banlist: " + seen);

            FakeCommandSource privileged = FakeCommandSource.player("Boss")
                    .grant(HiddenPunishments.PERMISSION);
            harness.module.onStaffCommand(privileged, "banlist", Arrays.asList());
            String all = String.join("\n", privileged.messageText());
            assertTrue(all.contains("Alex"), all);
            assertTrue(all.contains("(hidden)"),
                    "a holder is told which rows nobody else can see: " + all);
        }
    }

    @Test
    @DisplayName("the offline /history fallback filters the mirror too")
    void localHistoryFiltersTheMirror() {
        try (PunishmentsHarness harness = announcing()) {
            harness.platform.join(FakePlayer.named("Steve"));
            banInMirror(harness, "Steve", true, "alting");

            FakeCommandSource ordinary = FakeCommandSource.player("Mod");
            harness.module.onStaffCommand(ordinary, "history", Arrays.asList("Steve"));
            String seen = String.join("\n", ordinary.messageText());
            assertTrue(seen.contains("No active punishments"),
                    "with the bot unreachable the mirror is the answer, and it has to filter: " + seen);
            assertFalse(seen.contains("alting"), seen);

            FakeCommandSource privileged = FakeCommandSource.player("Boss")
                    .grant(HiddenPunishments.PERMISSION);
            harness.module.onStaffCommand(privileged, "history", Arrays.asList("Steve"));
            String all = String.join("\n", privileged.messageText());
            assertTrue(all.contains("alting"), all);
            assertTrue(all.contains("(hidden)"), all);
        }
    }

    @Test
    @DisplayName("a non-holder cannot lift a hidden ban, because for them it is not there")
    void hiddenRowIsNotRevocableWithoutTheNode() {
        try (PunishmentsHarness harness = announcing()) {
            harness.platform.join(FakePlayer.named("Steve"));
            banInMirror(harness, "Steve", true, "alting");

            FakeCommandSource ordinary = FakeCommandSource.player("Mod");
            harness.module.onStaffCommand(ordinary, "unban", Arrays.asList("Steve"));

            assertTrue(ordinary.wasTold("No active ban"), ordinary.messageText().toString());
            assertNotNull(harness.module.mirrorForTest().get("ban:" + uuidOf("Steve")),
                    "and the ban is still there - an unban announcement would disclose the very "
                            + "punishment being hidden, to everybody, later, out of context");

            FakeCommandSource privileged = FakeCommandSource.player("Boss")
                    .grant(HiddenPunishments.PERMISSION);
            harness.module.onStaffCommand(privileged, "unban", Arrays.asList("Steve"));
            assertNull(harness.module.mirrorForTest().get("ban:" + uuidOf("Steve")),
                    "a holder lifts it as normal");
        }
    }

    @Test
    @DisplayName("includeHidden is sent to the bot only for a reader holding the node")
    void includeHiddenIsAskedForOnlyWhenPermitted() throws Exception {
        try (ScriptedPunishApi bot = new ScriptedPunishApi();
                PunishmentsHarness harness = PunishmentsHarness.withApi(
                        dataDir.resolve("api"), ServerRole.STANDALONE, bot.baseUrl())
                        .enableReplace()) {
            bot.playerResponds(HIDDEN_ROWS);
            harness.platform.join(FakePlayer.named("Steve"));

            FakeCommandSource ordinary = FakeCommandSource.player("Mod");
            harness.module.onStaffCommand(ordinary, "history", Arrays.asList("Steve"));
            waitFor(() -> bot.lastGetContaining("/punishments/player/") != null, 5_000);
            assertEquals("", bot.lastGetContaining("/punishments/player/").query,
                    "no parameter at all for a reader without the node, which is also what an "
                            + "older bot that has never heard of it does");
            waitFor(() -> String.join("\n", ordinary.messageText()).contains("griefing"), 5_000);
            String seen = String.join("\n", ordinary.messageText());
            assertFalse(seen.contains("alting"),
                    "and the row is dropped here as well, in case the bot answered with it anyway: "
                            + seen);

            FakeCommandSource privileged = FakeCommandSource.player("Boss")
                    .grant(HiddenPunishments.PERMISSION);
            harness.module.onStaffCommand(privileged, "history", Arrays.asList("Steve"));
            waitFor(() -> bot.lastGetContaining("/punishments/player/").query.contains("includeHidden"),
                    5_000);
            assertEquals("includeHidden=true",
                    bot.lastGetContaining("/punishments/player/").query);
            waitFor(() -> String.join("\n", privileged.messageText()).contains("alting"), 5_000);
            assertTrue(String.join("\n", privileged.messageText()).contains("(hidden)"),
                    privileged.messageText().toString());
        }
    }

    @Test
    @DisplayName("/staffhistory asks and filters the same way")
    void staffHistoryFiltersTheBotsRows() throws Exception {
        try (ScriptedPunishApi bot = new ScriptedPunishApi();
                PunishmentsHarness harness = PunishmentsHarness.withApi(
                        dataDir.resolve("staff"), ServerRole.STANDALONE, bot.baseUrl())
                        .enableReplace()) {
            bot.listResponds(HIDDEN_ROWS);

            FakeCommandSource ordinary = FakeCommandSource.player("Mod");
            harness.module.onStaffCommand(ordinary, "staffhistory", Arrays.asList("Adam"));
            waitFor(() -> String.join("\n", ordinary.messageText()).contains("griefing"), 5_000);
            String seen = String.join("\n", ordinary.messageText());
            assertFalse(seen.contains("alting"), seen);
            assertFalse(bot.lastGetContaining("/punishments").query.contains("includeHidden"),
                    bot.lastGetContaining("/punishments").query);

            FakeCommandSource privileged = FakeCommandSource.player("Boss")
                    .grant(HiddenPunishments.PERMISSION);
            harness.module.onStaffCommand(privileged, "staffhistory", Arrays.asList("Adam"));
            waitFor(() -> String.join("\n", privileged.messageText()).contains("alting"), 5_000);
            assertTrue(String.join("\n", privileged.messageText()).contains("(hidden)"),
                    privileged.messageText().toString());
        }
    }

    @Test
    @DisplayName("hidden is read off a sync row and an apply frame, and absent means visible")
    void hiddenIsReadOffTheWire() {
        ActivePunishment plain = HeimdallPunishmentsModule.fromPayload(Payload.builder()
                .put("type", "ban")
                .put("targetUuid", uuidOf("Steve"))
                .build());
        assertNotNull(plain);
        assertFalse(plain.hidden,
                "an older bot sends no such field, and every row it sends is a visible one");

        ActivePunishment hidden = HeimdallPunishmentsModule.fromPayload(Payload.builder()
                .put("type", "ban")
                .put("targetUuid", uuidOf("Steve"))
                .put("hidden", true)
                .build());
        assertNotNull(hidden);
        assertTrue(hidden.hidden);
    }

    @Test
    @DisplayName("a hidden punish.apply frame lands on the mirror as hidden")
    void applyFrameCarriesHidden() {
        try (PunishmentsHarness harness = announcing()) {
            harness.tunnel.push("punish.apply", Payload.builder()
                    .put("type", "ban")
                    .put("targetUuid", uuidOf("Steve"))
                    .put("targetName", "Steve")
                    .put("reason", "alting")
                    .put("issuedByName", "Adam")
                    .put("silent", true)
                    .put("hidden", true)
                    .build());

            ActivePunishment stored = harness.module.mirrorForTest().get("ban:" + uuidOf("Steve"));
            assertNotNull(stored);
            assertTrue(stored.hidden,
                    "a punishment hidden from the dashboard has to be hidden here too, or the "
                            + "in-game lookup is the leak");
        }
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private PunishmentsHarness announcing() {
        return new PunishmentsHarness(dataDir, ServerRole.STANDALONE)
                .enableWith(Payload.builder()
                        .put("mode", "replace")
                        .put("ipSalt", "replace-salt")
                        .put("rootAliases", false)
                        .put("silentByDefault", false)
                        .build());
    }

    private static void banInMirror(PunishmentsHarness harness, String target, boolean hidden,
            String reason) {
        ActivePunishment ban = new ActivePunishment();
        ban.id = "ban-" + target;
        ban.type = "ban";
        ban.targetUuid = uuidOf(target);
        ban.targetName = target;
        ban.reason = reason;
        ban.hidden = hidden;
        ban.silent = hidden;
        harness.module.mirrorForTest().record("ban:" + uuidOf(target), ban);
    }

    private static String uuidOf(String name) {
        return FakePlayer.named(name).uuid().toString();
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
