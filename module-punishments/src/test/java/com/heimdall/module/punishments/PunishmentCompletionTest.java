package com.heimdall.module.punishments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.admin.PunishmentAdmin;
import com.heimdall.core.config.ServerRole;
import com.heimdall.core.json.Payload;
import com.heimdall.core.punish.SilenceDecision;
import com.heimdall.core.testing.FakeCommandSource;
import com.heimdall.core.testing.FakePlayer;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What {@code /hd ban} suggests, and to whom.
 *
 * <p>Before this, the punishment verbs completed nothing at all - not even the online players
 * Bukkit would have offered on its own, because the tree returns an empty list rather than
 * {@code null} for a verb it knows about. The bug report was "/hd ban did not tab-complete my
 * name".
 */
class PunishmentCompletionTest {

    @TempDir
    Path dataDir;

    private static final FakeCommandSource MODERATOR =
            FakeCommandSource.player("Mod").grant(SilenceDecision.OVERRIDE_PERMISSION);

    @Test
    @DisplayName("an online player completes at the target position")
    void onlinePlayerCompletes() {
        try (PunishmentsHarness harness = replacing()) {
            join(harness, "Steve");
            join(harness, "Alex");

            assertEquals(Arrays.asList("Steve"), complete(harness, "ban", "Ste"));
            assertTrue(complete(harness, "ban", "").containsAll(Arrays.asList("Alex", "Steve")));
            assertEquals(Collections.emptyList(), complete(harness, "ban", "zz"));
        }
    }

    @Test
    @DisplayName("no arguments at all is the target position, not the end of the world")
    void emptyArgumentListCompletesNames() {
        try (PunishmentsHarness harness = replacing()) {
            join(harness, "Steve");

            assertEquals(Arrays.asList("Steve"),
                    harness.module.complete(MODERATOR, "ban", Collections.<String>emptyList()),
                    "the trailing empty word is a convention of the two callers, not a promise of "
                            + "the interface, and without it this offered nothing at all");
        }
    }

    @Test
    @DisplayName("the target is the first non-flag word, however many flags came first")
    void flagsDoNotMoveTheTarget() {
        try (PunishmentsHarness harness = replacing()) {
            join(harness, "Steve");

            assertEquals(Arrays.asList("Steve"), complete(harness, "ban", "-s", "Ste"));
            assertEquals(Arrays.asList("Steve"),
                    complete(harness, "ban", "-s", "-p", "--sender=Console", "Ste"));
        }
    }

    @Test
    @DisplayName("a player this server has only seen, not hosted, still completes")
    void seenPlayersComplete() {
        try (PunishmentsHarness harness = replacing()) {
            harness.login(UUID.randomUUID(), "Notch", "203.0.113.9");

            assertEquals(Arrays.asList("Notch"), complete(harness, "ban", "Not"));
        }
    }

    @Test
    @DisplayName("so does a player who is only a row in the punishment mirror")
    void punishedPlayersComplete() {
        try (PunishmentsHarness harness = replacing()) {
            ban(harness, "Herobrine");

            assertEquals(Arrays.asList("Herobrine"), complete(harness, "ban", "Hero"));
        }
    }

    @Test
    @DisplayName("online players come before everyone else")
    void onlineFirst() {
        try (PunishmentsHarness harness = replacing()) {
            harness.login(UUID.randomUUID(), "Aaa", "203.0.113.9");
            join(harness, "Zzz");

            assertEquals(Arrays.asList("Zzz", "Aaa"), complete(harness, "ban", ""),
                    "alphabetical inside each group, but the people who are here come first");
        }
    }

    @Test
    @DisplayName("a player who left is still known, just no longer first")
    void quitKeepsTheName() {
        try (PunishmentsHarness harness = replacing()) {
            FakePlayer steve = join(harness, "Steve");
            harness.sessions.quit(steve, 2L);
            harness.platform.leave(steve);

            assertEquals(Arrays.asList("Steve"), complete(harness, "ban", "Ste"));
        }
    }

    @Test
    @DisplayName("unban offers only players with a ban to lift")
    void revokeVerbsFilterToTheirFamily() {
        try (PunishmentsHarness harness = replacing()) {
            join(harness, "Steve");
            join(harness, "Alex");
            ban(harness, "Steve");
            mute(harness, "Alex");

            assertEquals(Arrays.asList("Steve"), complete(harness, "unban", ""));
            assertEquals(Arrays.asList("Alex"), complete(harness, "unmute", ""));
            assertEquals(Collections.emptyList(), complete(harness, "unwarn", ""));
            assertTrue(complete(harness, "ban", "").containsAll(Arrays.asList("Alex", "Steve")),
                    "an issue verb is not filtered: anybody can be banned");
        }
    }

    @Test
    @DisplayName("an expired ban is not an unban candidate")
    void expiredRowsAreNotOffered() {
        try (PunishmentsHarness harness = replacing()) {
            join(harness, "Steve");
            recordUntil(harness, "ban", "Steve",
                    java.time.Instant.ofEpochMilli(System.currentTimeMillis() - 1000).toString());

            assertEquals(Arrays.asList("Steve"), complete(harness, "ban", "Ste"),
                    "the name is known either way; it is the unban candidacy that expired");
            assertEquals(Collections.emptyList(), complete(harness, "unban", "Ste"));
        }
    }

    @Test
    @DisplayName("a revoked name leaves the family it was in")
    void revokedNamesLeaveTheFamily() {
        try (PunishmentsHarness harness = replacing()) {
            join(harness, "Steve");
            ban(harness, "Steve");
            assertEquals(Arrays.asList("Steve"), complete(harness, "unban", ""));

            harness.tunnel.push("punish.revoke", Payload.builder()
                    .put("type", "ban")
                    .put("targetUuid", FakePlayer.named("Steve").uuid().toString())
                    .build());

            assertEquals(Collections.emptyList(), complete(harness, "unban", ""),
                    "there is nothing left to lift, so the name is not an answer");
            assertEquals(Arrays.asList("Steve"), complete(harness, "ban", "Ste"),
                    "and the name is still known, because it is still a name");
        }
    }

    @Test
    @DisplayName("a name a moderator banned and then unbanned leaves the family too")
    void nativeRoundTripLeavesTheFamily() {
        try (PunishmentsHarness harness = replacing()) {
            join(harness, "Steve");

            harness.module.onStaffCommand(MODERATOR, "ban", Arrays.asList("Steve", "griefing"));
            assertEquals(Arrays.asList("Steve"), complete(harness, "unban", ""));

            harness.module.onStaffCommand(MODERATOR, "unban", Arrays.asList("Steve"));
            assertEquals(Collections.emptyList(), complete(harness, "unban", ""));
        }
    }

    @Test
    @DisplayName("an IP ban is an unban candidate, and it is the same family as a ban")
    void ipBansAreUnbanCandidates() {
        try (PunishmentsHarness harness = replacing()) {
            join(harness, "Steve");
            harness.tunnel.push("punish.apply", Payload.builder()
                    .put("id", "ip-1")
                    .put("type", "ipban")
                    .put("targetUuid", FakePlayer.named("Steve").uuid().toString())
                    .put("targetName", "Steve")
                    .put("ipDigest", "digest-1")
                    .build());

            assertEquals(Arrays.asList("Steve"), complete(harness, "unban", ""));
            assertEquals(Collections.emptyList(), complete(harness, "unmute", ""));
        }
    }

    @Test
    @DisplayName("a dash completes the flags, and only for somebody allowed to use them")
    void flagCompletion() {
        try (PunishmentsHarness harness = replacing()) {
            join(harness, "Steve");

            assertEquals(Arrays.asList("-s", "-p"), complete(harness, "ban", "-"));
            assertEquals(Arrays.asList("-s"), complete(harness, "ban", "-s"));
            assertEquals(Collections.emptyList(),
                    harness.module.complete(FakeCommandSource.player("Nobody"), "ban",
                            Arrays.asList("-")),
                    "the command refuses -s without the node, so completing it is a lie");
        }
    }

    @Test
    @DisplayName("durations are offered after the target, until one is typed")
    void durationCompletion() {
        try (PunishmentsHarness harness = replacing()) {
            join(harness, "Steve");

            assertEquals(HeimdallPunishmentsModule.DURATION_SUGGESTIONS,
                    complete(harness, "ban", "Steve", ""));
            assertEquals(Arrays.asList("30m", "30d"), complete(harness, "ban", "Steve", "30"));
            assertEquals(Arrays.asList("perm"), complete(harness, "ban", "Steve", "p"));
            assertEquals(Collections.emptyList(), complete(harness, "ban", "Steve", "7d", ""),
                    "the duration is taken, so the rest is the reason and has no vocabulary");
            assertEquals(Collections.emptyList(),
                    complete(harness, "ban", "-s", "Steve", "7d", "grief", ""));
        }
    }

    @Test
    @DisplayName("verbs with no duration to give offer none")
    void durationlessVerbs() {
        try (PunishmentsHarness harness = replacing()) {
            join(harness, "Steve");

            assertEquals(Collections.emptyList(), complete(harness, "unban", "Steve", ""));
            assertEquals(Collections.emptyList(), complete(harness, "kick", "Steve", ""));
            assertEquals(Collections.emptyList(), complete(harness, "warn", "Steve", ""),
                    "a warn is an event, not a state: offering a length the bot then drops is "
                            + "worse than offering nothing");
            assertEquals(Collections.emptyList(), complete(harness, "warn", "Steve", "1"));
            assertEquals(Collections.emptyList(), complete(harness, "history", "Steve", ""));
            assertEquals(Collections.emptyList(), complete(harness, "banlist", ""),
                    "/banlist takes no arguments at all");
        }
    }

    @Test
    @DisplayName("the answer is capped, so a tab press cannot ship a whole player history")
    void cappedAtAHundred() {
        try (PunishmentsHarness harness = replacing()) {
            for (int i = 0; i < 250; i++) {
                harness.login(UUID.randomUUID(), String.format("Player%03d", i), "203.0.113.9");
            }

            List<String> suggestions = complete(harness, "ban", "Player");
            assertEquals(PunishmentAdmin.COMPLETION_LIMIT, suggestions.size());
            assertTrue(suggestions.contains("Player000"));
            assertFalse(suggestions.contains("Player249"),
                    "alphabetical, so the cut falls at the far end rather than at random");
        }
    }

    @Test
    @DisplayName("a name is offered once, however many ways this server knows it")
    void namesAreNotDuplicated() {
        try (PunishmentsHarness harness = replacing()) {
            join(harness, "Steve");
            harness.login(FakePlayer.named("Steve").uuid(), "Steve", "203.0.113.9");
            ban(harness, "steve");

            assertEquals(Arrays.asList("Steve"), complete(harness, "ban", "ste"),
                    "matched case-insensitively, and the online spelling is the one shown");
        }
    }

    private PunishmentsHarness replacing() {
        return new PunishmentsHarness(dataDir, ServerRole.STANDALONE).enableReplace();
    }

    private static FakePlayer join(PunishmentsHarness harness, String name) {
        FakePlayer player = harness.platform.join(FakePlayer.named(name));
        harness.sessions.join(player, 1L);
        return player;
    }

    private static void ban(PunishmentsHarness harness, String name) {
        record(harness, "ban", name);
    }

    private static void mute(PunishmentsHarness harness, String name) {
        record(harness, "mute", name);
    }

    private static void recordUntil(PunishmentsHarness harness, String type, String name,
            String expiresAt) {
        harness.tunnel.push("punish.apply", Payload.builder()
                .put("id", type + "-" + name)
                .put("type", type)
                .put("targetUuid", FakePlayer.named(name).uuid().toString())
                .put("targetName", name)
                .put("reason", "testing")
                .put("expiresAt", expiresAt)
                .build());
    }

    /** A punishment arriving from the bot, which is how a name this server never hosted gets in. */
    private static void record(PunishmentsHarness harness, String type, String name) {
        harness.tunnel.push("punish.apply", Payload.builder()
                .put("id", type + "-" + name)
                .put("type", type)
                .put("targetUuid", FakePlayer.named(name).uuid().toString())
                .put("targetName", name)
                .put("reason", "testing")
                .build());
    }

    private static List<String> complete(PunishmentsHarness harness, String verb, String... args) {
        return harness.module.complete(MODERATOR, verb, Arrays.asList(args));
    }
}
