package com.heimdall.module.rolesync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.heimdall.core.config.ServerRole;
import com.heimdall.core.http.ApiClient;
import com.heimdall.core.http.ApiSettings;
import com.heimdall.core.http.HeimdallApi;
import com.heimdall.core.log.LogLevel;
import com.heimdall.core.log.RecordingLogger;
import com.heimdall.core.roles.RoleSyncLoginSource;
import com.heimdall.core.testing.FakeLuckPerms;
import com.heimdall.core.testing.FakePlayer;
import com.heimdall.stubbot.Outcome;
import com.heimdall.stubbot.PlayerFixture;
import com.heimdall.stubbot.StubBot;
import com.heimdall.stubbot.StubBotConfig;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Role sync on join when no login answer is going to carry the directive.
 *
 * <p>Against the stub bot, so the assertions are about requests that really went over the wire, and
 * in particular about the one that must NOT: with the whitelist module answering logins, a snapshot
 * request would be a second round trip for the same join.
 */
class RoleSyncJoinSnapshotTest {

    private static final List<String> TARGET = Arrays.asList("vip");
    private static final List<String> MANAGED = Arrays.asList("vip", "member");
    private static final String SNAPSHOT = "POST role-sync/snapshot";

    @TempDir
    Path dataDirectory;

    private StubBot bot;
    private RoleSyncHarness harness;

    @BeforeEach
    void setUp() {
        bot = StubBot.start(StubBotConfig.withDemoFixtures().bindHost("127.0.0.1").port(0));
        harness = new RoleSyncHarness(dataDirectory, executors -> new HeimdallApi(new ApiClient(
                new RecordingLogger(), settings(StubBotConfig.DEFAULT_API_KEY), executors.io())));
    }

    /** Replaces the harness with one on a server of the given role, pointed at the same stub. */
    private void onRole(ServerRole role) {
        harness.close();
        harness = new RoleSyncHarness(dataDirectory, role, executors -> new HeimdallApi(new ApiClient(
                new RecordingLogger(), settings(StubBotConfig.DEFAULT_API_KEY), executors.io())));
    }

    /** A login source that answers the same for everybody. */
    private static RoleSyncLoginSource always(final RoleSyncLoginSource.Coverage coverage) {
        return new RoleSyncLoginSource() {
            @Override
            public Coverage coverageFor(UUID playerUuid) {
                return coverage;
            }
        };
    }

    @AfterEach
    void tearDown() {
        try {
            harness.close();
        } finally {
            bot.close();
        }
    }

    private ApiSettings settings(String apiKey) {
        return ApiSettings.builder()
                .baseUrl(bot.baseUrl())
                .guildId(StubBotConfig.DEFAULT_GUILD_ID)
                .apiKey(apiKey)
                .serverId("survival")
                .timeoutMs(4000)
                .retries(1)
                .retryDelayMs(20)
                .build();
    }

    /** A player the stub has a role snapshot for. */
    private FakePlayer linked(String name) {
        FakePlayer player = FakePlayer.named(name);
        bot.fixtures().put(PlayerFixture.of(player.uuid().toString(), name, Outcome.ALLOW)
                .withGroups(TARGET, MANAGED));
        // Loaded in LuckPerms and holding nothing, said explicitly: the fake, like the real bridge,
        // treats a player it has never heard of as unknown and fails the read. A test that wants
        // groups calls holding() afterwards; one that wants a failed read calls failing().
        harness.luckPerms.known(player.uuid());
        return player;
    }

    private static void await(BooleanSupplier condition, String what) {
        long deadline = System.currentTimeMillis() + 15_000L;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(20L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertTrue(condition.getAsBoolean(), what);
    }

    @Test
    @DisplayName("with no whitelist answering logins, a join fetches the snapshot and applies it")
    void joinWithoutWhitelistAppliesTheSnapshot() {
        harness.enable();
        FakePlayer steve = linked("Steve");

        harness.sessions.join(steve, 1L);

        await(() -> !harness.syncs().isEmpty(), "the snapshot never reached LuckPerms: "
                + harness.logger.records());
        FakeLuckPerms.Sync sync = harness.syncs().get(0);
        assertEquals(steve.uuid(), sync.uuid());
        assertEquals(TARGET, sync.targetGroups());
        assertEquals(MANAGED, sync.managedGroups());
        assertEquals(1, bot.requestCount(SNAPSHOT));
    }

    @Test
    @DisplayName("the request carries the player's current groups, as connection-attempt does")
    void requestCarriesCurrentGroups() {
        harness.enable();
        FakePlayer steve = linked("Steve");
        harness.luckPerms.holding(steve.uuid(), "member", "default");

        harness.sessions.join(steve, 1L);

        await(() -> bot.lastRoleSyncSnapshotRequest() != null, "no request arrived");
        JsonArray sent = bot.lastRoleSyncSnapshotRequest().getAsJsonArray("currentGroups");
        assertEquals(2, sent.size());
        assertEquals("member", sent.get(0).getAsString());
        assertEquals("default", sent.get(1).getAsString());
    }

    @Test
    @DisplayName("groups that could not be read are left out, never sent as an empty list, and warned once")
    void unreadableGroupsAreOmitted() {
        harness.enable();
        harness.luckPerms.failing(new IllegalStateException("storage is down"));

        harness.sessions.join(linked("Steve"), 1L);
        harness.sessions.join(linked("Alex"), 2L);

        await(() -> bot.requestCount(SNAPSHOT) == 2, "both requests should still be sent");
        assertFalse(bot.lastRoleSyncSnapshotRequest().has("currentGroups"),
                "missing means unknown to the bot; an empty list means holds nothing (#796 / MC-11)");
        assertEquals(1, harness.countLogged(LogLevel.WARN, "LuckPerms groups for the role-sync"),
                "warned like the connection-attempt reporter, but once per kind: "
                        + harness.logger.records());
    }

    @Test
    @DisplayName("a backend deferring to its gatekeeper asks nothing: the proxy makes the network's call")
    void deferringBackendMakesNoCall() {
        harness.module.setLoginSource(always(RoleSyncLoginSource.Coverage.DEFERS_TO_GATEKEEPER));
        harness.enable();

        harness.sessions.join(linked("Steve"), 1L);

        assertTrue(harness.logger.logged(LogLevel.DEBUG, "leaves logins to its gatekeeper"),
                harness.logger.records().toString());
        assertEquals(0, bot.requestCount(SNAPSHOT));
        assertTrue(harness.syncs().isEmpty());
    }

    @Test
    @DisplayName("no login check on a backend: the role says the gatekeeper asks, so this does not")
    void backendWithoutWhitelistMakesNoCall() {
        onRole(ServerRole.ENFORCER);
        harness.enable();

        harness.sessions.join(linked("Steve"), 1L);

        assertTrue(harness.logger.logged(LogLevel.DEBUG, "this is a backend behind a gatekeeper"),
                harness.logger.records().toString());
        assertEquals(0, bot.requestCount(SNAPSHOT));
    }

    @Test
    @DisplayName("no login check on the proxy: it is the network's caller, so it asks once")
    void gatekeeperWithoutWhitelistAsks() {
        onRole(ServerRole.GATEKEEPER);
        harness.enable();

        harness.sessions.join(linked("Steve"), 1L);

        await(() -> !harness.syncs().isEmpty(), "the proxy should sync: " + harness.logger.records());
        assertEquals(1, bot.requestCount(SNAPSHOT));
    }

    @Test
    @DisplayName("a player whose login went to the bot is not asked about again")
    void coveredLoginMakesNoSecondCall() {
        final FakePlayer covered = linked("Covered");
        FakePlayer uncovered = linked("Uncovered");
        harness.module.setLoginSource(new RoleSyncLoginSource() {
            @Override
            public Coverage coverageFor(UUID playerUuid) {
                return covered.uuid().equals(playerUuid) ? Coverage.DELIVERS : Coverage.NOT_COVERED;
            }
        });
        harness.enable();

        harness.sessions.join(covered, 1L);
        // The positive control: without it, "zero requests" could just mean "not arrived yet".
        harness.sessions.join(uncovered, 2L);

        await(() -> harness.syncs().size() == 1, "the uncovered join should still sync");
        assertEquals(uncovered.uuid(), harness.syncs().get(0).uuid());
        assertEquals(1, bot.requestCount(SNAPSHOT),
                "exactly one request: the covered login's answer already carried its directive");
    }

    @Test
    @DisplayName("a bot without the route is 'no snapshot', not an error")
    void olderBotIsAbsentAndQuiet() {
        bot.setRoleSyncSnapshotRoute(false);
        harness.enable();

        harness.sessions.join(linked("Steve"), 1L);

        await(() -> harness.logger.logged(LogLevel.DEBUG, "no role-sync snapshot for Steve"),
                "the 404 should reach the applier as absent: " + harness.logger.records());
        assertTrue(harness.syncs().isEmpty());
        assertEquals(0, harness.countLogged(LogLevel.WARN, "role-sync snapshot"),
                "an older bot is an ordinary rollout state: " + harness.logger.records());
    }

    @Test
    @DisplayName("a bot that refuses every request is warned about once, not once per join")
    void refusalIsWarnedOncePerReason() {
        harness.close();
        harness = new RoleSyncHarness(dataDirectory, executors -> new HeimdallApi(new ApiClient(
                new RecordingLogger(), settings("not-the-key"), executors.io())));
        harness.enable();

        harness.sessions.join(linked("Steve"), 1L);
        harness.sessions.join(linked("Alex"), 2L);

        await(() -> harness.countLogged(LogLevel.DEBUG, "role-sync snapshot for") >= 1,
                "the second failure should be logged at debug: " + harness.logger.records());
        assertEquals(1, harness.countLogged(LogLevel.WARN, "could not fetch a role-sync snapshot"),
                harness.logger.records().toString());
        assertTrue(harness.syncs().isEmpty());
    }

    @Test
    @DisplayName("an unconfigured server asks nothing and applies nothing")
    void unconfiguredServerMakesNoCall() {
        harness.close();
        harness = new RoleSyncHarness(dataDirectory, null);
        harness.enable();

        harness.sessions.join(linked("Steve"), 1L);

        assertEquals(0, bot.requestCount(SNAPSHOT));
        assertTrue(harness.syncs().isEmpty());
    }

    @Test
    @DisplayName("with no LuckPerms nothing is asked, and nothing is warned on the join path")
    void noLuckPermsMakesNoCall() {
        harness.withLuckPerms(null).enable();

        harness.sessions.join(linked("Steve"), 1L);

        assertTrue(harness.logger.logged(LogLevel.DEBUG, "LuckPerms is not available here"),
                harness.logger.records().toString());
        assertEquals(0, bot.requestCount(SNAPSHOT));
        assertEquals(0, harness.countLogged(LogLevel.WARN, "LuckPerms is not available"),
                "a server that never configured role sync must not be warned on its first join: "
                        + harness.logger.records());
    }

    @Test
    @DisplayName("switching the module off takes the join listener with it")
    void disableUnwindsTheJoinListener() {
        harness.enable();
        // Two: the snapshot requester, and the reverse-sync reporter's join seed.
        assertEquals(2, harness.sessions.joinListenerCount());

        harness.disable();

        assertEquals(0, harness.sessions.joinListenerCount());
    }

    // Reverse role sync's seed comes only from a join call that reached the bot.

    @Test
    @DisplayName("a successful snapshot request seeds reverse sync with the groups it carried")
    void successfulSnapshotSeedsReverseSync() {
        harness.configure(RoleSyncHarness.watching("vip"));
        FakePlayer steve = linked("Steve");
        harness.platform.join(steve);
        harness.luckPerms.holding(steve.uuid(), "default", "vip");

        harness.sessions.join(steve, 1L);
        await(() -> bot.requestCount(SNAPSHOT) == 1 && !harness.syncs().isEmpty(),
                "the snapshot request should complete: " + harness.logger.records());

        harness.luckPerms.fireGroupsChanged(steve.uuid(), Arrays.asList("default", "vip", "builder"));
        sleepPastWindow();
        assertEquals(0, harness.bus.sent(GroupChangeReporter.FRAME_TYPE).size(),
                "the snapshot request told the bot about vip; nothing new to report");
    }

    @Test
    @DisplayName("a failed snapshot request leaves the player unseeded, so their next change reports")
    void failedSnapshotLeavesPlayerUnseeded() {
        harness.close();
        harness = new RoleSyncHarness(dataDirectory, executors -> new HeimdallApi(new ApiClient(
                new RecordingLogger(), settings("wrong-key"), executors.io())));
        harness.configure(RoleSyncHarness.watching("vip"));
        FakePlayer steve = linked("Steve");
        harness.platform.join(steve);
        harness.luckPerms.holding(steve.uuid(), "default", "vip");

        harness.sessions.join(steve, 1L);
        await(() -> harness.countLogged(LogLevel.WARN, "could not fetch a role-sync snapshot") == 1,
                "the snapshot request should fail: " + harness.logger.records());

        harness.luckPerms.fireGroupsChanged(steve.uuid(), Arrays.asList("default", "vip", "builder"));
        await(() -> harness.bus.sent(GroupChangeReporter.FRAME_TYPE).size() == 1,
                "the bot never heard about vip, so it must be reported: " + harness.logger.records());
    }

    private static void sleepPastWindow() {
        try {
            Thread.sleep(GroupChangeReporter.DEBOUNCE_MS + 400L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
