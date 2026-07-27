package com.heimdall.core.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.concurrent.HeimdallExecutors;
import com.heimdall.core.http.model.ConnectionAction;
import com.heimdall.core.http.model.ConnectionAttempt;
import com.heimdall.core.http.model.ConnectionAttemptResult;
import com.heimdall.core.http.model.LinkCodeResult;
import com.heimdall.core.http.model.OffenseReport;
import com.heimdall.core.http.model.OffenseResult;
import com.heimdall.core.http.model.OffenseType;
import com.heimdall.core.http.model.PluginRelease;
import com.heimdall.core.http.model.RoleSyncDirective;
import com.heimdall.core.http.model.WhitelistSyncEntry;
import com.heimdall.core.http.model.WhitelistSyncResult;
import com.heimdall.core.log.RecordingLogger;
import com.heimdall.stubbot.Outcome;
import com.heimdall.stubbot.PlayerFixture;
import com.heimdall.stubbot.StubBot;
import com.heimdall.stubbot.StubBotConfig;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link ApiClient} against {@code :stub-bot}, over a real socket, behind real HMAC verification.
 *
 * <p>This is where the fixture earns its keep. The unit tests in {@link ApiResponsesTest} assert
 * what the plugin makes of a JSON shape <em>this repo wrote down</em>; these assert that the shape
 * is the one the bot actually sends, because the stub is a transcription of the bot's own handlers
 * and its README's message table is derived from its code. A hand-written fixture drifting from the
 * bot is invisible; the stub drifting is a failure in its own suite.
 *
 * <p>Each test gets its own stub on an ephemeral port, so the fixture mutations the ETag and
 * escalation tests need cannot leak between them.
 */
class StubBotApiClientTest {

    private static final String GUILD = StubBotConfig.DEFAULT_GUILD_ID;

    private static final String ALLOWED = "11111111-1111-1111-1111-111111111111";
    private static final String DENIED = "22222222-2222-2222-2222-222222222222";
    private static final String PENDING_AUTH = "33333333-3333-3333-3333-333333333333";
    private static final String REVOKED = "44444444-4444-4444-4444-444444444444";
    private static final String QUEUED = "55555555-5555-5555-5555-555555555555";
    private static final String EXISTING_LINK = "66666666-6666-6666-6666-666666666666";
    private static final String SCHEDULED = "77777777-7777-7777-7777-777777777777";

    private final RecordingLogger logger = new RecordingLogger(true);

    private StubBot bot;
    private HeimdallExecutors executors;
    private ApiClient client;

    @BeforeEach
    void startStub() {
        bot = StubBot.start(StubBotConfig.withDemoFixtures().bindHost("127.0.0.1").port(0));
        executors = new HeimdallExecutors(logger, 2);
        client = new ApiClient(logger, settings(GUILD, StubBotConfig.DEFAULT_API_KEY), executors.io());
    }

    @AfterEach
    void stopStub() {
        if (executors != null) {
            executors.shutdown(2000);
        }
        if (bot != null) {
            bot.close();
        }
    }

    private ApiSettings settings(String guildId, String apiKey) {
        return ApiSettings.builder()
                .baseUrl(bot.baseUrl())
                .guildId(guildId)
                .apiKey(apiKey)
                .serverId("survival")
                .timeoutMs(5000)
                .retries(2)
                .retryDelayMs(25)
                .build();
    }

    /** Blocks on a future and rethrows the real failure rather than an ExecutionException wrapper. */
    private static <T> T await(CompletableFuture<T> future) throws Exception {
        try {
            return future.get(20, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException) {
                throw (RuntimeException) e.getCause();
            }
            throw e;
        }
    }

    private ConnectionAttemptResult connect(String uuid, String username) throws Exception {
        return await(client.connectionAttempt(ConnectionAttempt.builder(username, uuid)
                .ip("203.0.113.7")
                .serverIp("play.example.com")
                .currentGroups(Arrays.asList("default"))
                .build()));
    }

    // ── Authentication ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("HMAC")
    class Authentication {

        @Test
        @DisplayName("a correctly signed request is accepted")
        void signedRequestsAreAccepted() throws Exception {
            List<OffenseType> types = await(client.offenseTypes());

            assertEquals(1, types.size());
            assertEquals("cheating", types.get(0).typeId());
            assertEquals(Arrays.asList("xray", "exploiting"), types.get(0).offenses());
        }

        @Test
        @DisplayName("a signature from the wrong secret gets the bot's bare 401 body, not an envelope")
        void aTamperedSignatureIsRejected() {
            client.reconfigure(settings(GUILD, "not-the-right-secret"));

            ApiError error = assertThrows(ApiError.class, () -> connect(ALLOWED, "AllowedSteve"));

            assertEquals(401, error.httpStatus());
            assertEquals("UNAUTHORIZED", error.code(),
                    "the HMAC middleware answers {\"error\":\"Unauthorized\"} with no envelope at "
                            + "all — a client that assumes error.code exists breaks here");
            assertFalse(error.isRetryable());
        }

        @Test
        @DisplayName("a request for a guild the bot does not serve is a typed 404")
        void unknownGuildIsNotConfigured() {
            client.reconfigure(settings("999999999999999999", StubBotConfig.DEFAULT_API_KEY));

            ApiError error = assertThrows(ApiError.class, () -> await(client.offenseTypes()));

            assertEquals(404, error.httpStatus());
            assertEquals("NOT_CONFIGURED", error.code());
        }
    }

    // ── All six connection-attempt outcomes ──────────────────────────────────

    @Nested
    @DisplayName("connection-attempt, all six outcomes")
    class ConnectionOutcomes {

        @Test
        void allow() throws Exception {
            ConnectionAttemptResult result = connect(ALLOWED, "AllowedSteve");

            assertEquals(ConnectionAction.ALLOW, result.action());
            assertTrue(result.whitelisted());
            assertNull(result.message(), "a welcome-back line is not a kick reason");
            assertFalse(result.revoked());

            RoleSyncDirective roleSync = result.roleSync();
            assertTrue(roleSync.isPresent());
            assertTrue(roleSync.isEnabled());
            assertEquals(Arrays.asList("vip"), roleSync.targetGroups());
            assertEquals(Arrays.asList("vip", "member"), roleSync.managedGroups());
        }

        @Test
        void deny() throws Exception {
            ConnectionAttemptResult result = connect(DENIED, "DeniedAlex");

            assertEquals(ConnectionAction.DENY, result.action());
            assertFalse(result.whitelisted());
            assertNotNull(result.message());
            assertTrue(result.message().contains("not whitelisted"), result.message());
            assertFalse(result.revoked());
        }

        @Test
        void pendingAuth() throws Exception {
            ConnectionAttemptResult result = connect(PENDING_AUTH, "PendingCode");

            assertEquals(ConnectionAction.SHOW_AUTH_CODE, result.action());
            assertFalse(result.whitelisted(), "they are shown a code but not let in");
            assertEquals("135790", result.authCode());
            assertTrue(result.message().contains("135790"), result.message());
        }

        @Test
        void revoked() throws Exception {
            ConnectionAttemptResult result = connect(REVOKED, "RevokedRita");

            assertEquals(ConnectionAction.DENY, result.action());
            assertFalse(result.whitelisted());
            assertTrue(result.revoked(), "'was whitelisted, no longer is' deserves its own message");
            assertTrue(result.message().contains("griefing"), result.message());
        }

        @Test
        void pendingApprovalWithAQueuePosition() throws Exception {
            ConnectionAttemptResult result = connect(QUEUED, "QueuedQuinn");

            assertEquals(ConnectionAction.PENDING_APPROVAL, result.action());
            assertTrue(result.hasQueuePosition());
            assertEquals(Integer.valueOf(3), result.queuePosition());
            assertTrue(result.message().contains("#3"), result.message());
        }

        @Test
        @DisplayName("the scheduled branch really does omit queuePosition on the wire")
        void pendingApprovalOnASchedule() throws Exception {
            ConnectionAttemptResult result = connect(SCHEDULED, "ScheduledSam");

            assertEquals(ConnectionAction.PENDING_APPROVAL, result.action());
            assertFalse(result.hasQueuePosition(),
                    "the bot omits the key entirely here; v2's primitive int read it as position 0");
            assertNull(result.queuePosition());
            assertTrue(result.message().contains("Friday"), result.message());
        }

        @Test
        @DisplayName("existingPlayerLink is whitelisted:true AND an auth code")
        void existingPlayerLink() throws Exception {
            ConnectionAttemptResult result = connect(EXISTING_LINK, "LegacyLee");

            assertEquals(ConnectionAction.SHOW_AUTH_CODE, result.action());
            assertTrue(result.whitelisted(), "they are let in as well as offered the link");
            assertEquals("246800", result.authCode());
            assertNotNull(result.message(), "and unlike a plain allow, this message IS shown");
        }

        @Test
        @DisplayName("an unknown player falls to the configured default outcome")
        void unknownPlayerGetsTheDefault() throws Exception {
            ConnectionAttemptResult result =
                    connect("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", "Stranger");

            assertEquals(ConnectionAction.DENY, result.action());
            assertEquals(RoleSyncDirective.absent(), result.roleSync(),
                    "no fixture means no snapshot, which is not the same as roleSync disabled");
        }
    }

    // ── whitelist/sync ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("whitelist/sync and its ETag")
    class WhitelistSync {

        @Test
        @DisplayName("full fetch, then 304, then fresh data once membership changes")
        void etagFlow() throws Exception {
            WhitelistSyncResult first = await(client.whitelistSync(null));

            assertFalse(first.notModified());
            assertNotNull(first.etag());
            assertEquals(2, first.players().size(),
                    "only ALLOW and EXISTING_LINK count as whitelisted: " + first.players());
            assertEquals(2, first.count());
            assertNotNull(first.hash());
            assertNotNull(first.generatedAt());
            assertTrue(uuidsOf(first).containsAll(Arrays.asList(ALLOWED, EXISTING_LINK)));

            WhitelistSyncResult unchanged = await(client.whitelistSync(first.etag()));

            assertTrue(unchanged.notModified(), "an unchanged whitelist must not re-send the dump");
            assertTrue(unchanged.players().isEmpty());
            assertNotNull(unchanged.etag());

            // Revoke one of them: membership changes, so the ETag must stop matching.
            bot.fixtures().put(PlayerFixture.of(EXISTING_LINK, "LegacyLee", Outcome.DENY));

            WhitelistSyncResult changed = await(client.whitelistSync(first.etag()));

            assertFalse(changed.notModified());
            assertEquals(1, changed.players().size());
            assertEquals(ALLOWED, changed.players().get(0).uuid());
            assertFalse(first.etag().equals(changed.etag()), "the ETag has to move with membership");
        }

        @Test
        @DisplayName("a stale ETag from a previous run is simply ignored")
        void unknownEtagGetsAFullDump() throws Exception {
            WhitelistSyncResult result = await(client.whitelistSync("\"not-a-real-hash\""));

            assertFalse(result.notModified());
            assertEquals(2, result.players().size());
        }

        @Test
        @DisplayName("an empty whitelist is a legitimate answer, not a failure")
        void emptyWhitelist() throws Exception {
            bot.fixtures().clear();

            WhitelistSyncResult result = await(client.whitelistSync(null));

            assertFalse(result.notModified());
            assertTrue(result.players().isEmpty());
            assertEquals(0, result.count());
        }

        private List<String> uuidsOf(WhitelistSyncResult result) {
            List<String> uuids = new ArrayList<String>();
            for (WhitelistSyncEntry entry : result.players()) {
                uuids.add(entry.uuid());
            }
            return uuids;
        }
    }

    // ── Offenses ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("the offense flow")
    class Offenses {

        @Test
        @DisplayName("repeat offenses escalate through the configured tiers")
        void escalation() throws Exception {
            OffenseResult first = await(client.offend(report("xray")));

            assertEquals("warn", first.action());
            assertEquals(1, first.tierApplied());
            assertEquals(1, first.totalPoints());
            assertNull(first.durationMinutes(), "the warn tier has no duration");
            assertEquals("warn DeniedAlex", firstTwoWords(first.command()));
            assertEquals("Cheating", first.offenseType());
            assertFalse(first.infractionId().isEmpty());

            OffenseResult second = await(client.offend(report("exploiting")));

            assertEquals(2, second.totalPoints(),
                    "both slugs belong to the same type, so they share a running total");
            assertEquals(2, second.tierApplied());
            assertEquals("tempban", second.action());
            assertEquals(Integer.valueOf(1440), second.durationMinutes());
            assertEquals("tempban (1d)", second.tierDescription());
            assertTrue(second.command().startsWith("tempban DeniedAlex 1d"), second.command());
        }

        @Test
        void anUnknownSlugIsATyped404() {
            ApiError error = assertThrows(ApiError.class, () -> await(client.offend(report("jaywalking"))));

            assertEquals(404, error.httpStatus());
            assertEquals("UNKNOWN_OFFENSE", error.code());
            assertFalse(error.isRetryable());
        }

        private OffenseReport report(String slug) {
            return OffenseReport.builder(DENIED, "DeniedAlex", slug)
                    .issuedBy(ALLOWED, "AllowedSteve")
                    .notes("caught on camera")
                    .build();
        }

        private String firstTwoWords(String command) {
            String[] parts = command.split(" ");
            return parts.length < 2 ? command : parts[0] + " " + parts[1];
        }
    }

    // ── The remaining endpoints ──────────────────────────────────────────────

    @Nested
    @DisplayName("link codes and the update check")
    class RemainingEndpoints {

        @Test
        void requestLinkCodeMintsACode() throws Exception {
            LinkCodeResult result = await(client.requestLinkCode("PendingCode", PENDING_AUTH));

            assertFalse(result.alreadyLinked());
            assertEquals("135790", result.code());
        }

        @Test
        @DisplayName("an already-linked account comes back as data, not as an exception")
        void requestLinkCodeReportsAnExistingLink() throws Exception {
            bot.fixtures().put(PlayerFixture.of(DENIED, "DeniedAlex", Outcome.DENY)
                    .linkedTo("424242424242424242", "steve", "Steve"));

            LinkCodeResult result = await(client.requestLinkCode("DeniedAlex", DENIED));

            assertTrue(result.alreadyLinked());
            assertNull(result.code());
            assertEquals("424242424242424242", result.discordId());
            assertEquals("steve", result.discordUsername());
            assertEquals("Steve", result.discordDisplayName());
            assertTrue(result.message().contains("already linked"), result.message());
        }

        @Test
        void latestRelease() throws Exception {
            PluginRelease release = await(client.latestRelease());

            assertEquals("v3.0.0", release.version());
            assertTrue(release.downloadUrl().endsWith(".jar"), release.downloadUrl());
            assertNotNull(release.htmlUrl());
            assertNotNull(release.publishedAt());
            assertFalse(release.releaseNotes().isEmpty());
        }
    }
}
