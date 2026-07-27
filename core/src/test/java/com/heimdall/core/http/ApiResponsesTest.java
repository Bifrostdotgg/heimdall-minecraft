package com.heimdall.core.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.http.model.ConnectionAction;
import com.heimdall.core.http.model.ConnectionAttemptResult;
import com.heimdall.core.http.model.LinkCodeResult;
import com.heimdall.core.http.model.OffenseResult;
import com.heimdall.core.http.model.OffenseType;
import com.heimdall.core.http.model.PluginRelease;
import com.heimdall.core.http.model.RoleSyncDirective;
import com.heimdall.core.http.model.WhitelistSyncResult;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Wire shapes in, models out — asserted against hand-written JSON so the parser is pinned
 * independently of whether the stub happens to be running.
 *
 * <p>The end-to-end suite proves the stub really sends these shapes; this proves what the plugin
 * makes of them.
 */
class ApiResponsesTest {

    private static RawResponse ok(String body) {
        return new RawResponse(200, body, null);
    }

    private static ConnectionAttemptResult attempt(String data) {
        return ApiResponses.connectionAttempt(ok("{\"success\":true,\"data\":" + data + "}"));
    }

    @Nested
    @DisplayName("the envelope")
    class EnvelopeHandling {

        @Test
        @DisplayName("a bare body is tolerated as well as a wrapped one")
        void bareBodyIsTolerated() {
            ConnectionAttemptResult wrapped =
                    ApiResponses.connectionAttempt(ok("{\"success\":true,\"data\":{\"whitelisted\":true}}"));
            ConnectionAttemptResult bare = ApiResponses.connectionAttempt(ok("{\"whitelisted\":true}"));

            assertEquals(ConnectionAction.ALLOW, wrapped.action());
            assertEquals(ConnectionAction.ALLOW, bare.action(),
                    "v2 tolerated both shapes at every call site; that tolerance lives here now");
        }

        @Test
        @DisplayName("success:false is an error even when the status said 200")
        void successFalseIsAnError() {
            ApiError error = assertThrows(ApiError.class, () -> ApiResponses.connectionAttempt(
                    ok("{\"success\":false,\"error\":{\"code\":\"NOT_CONFIGURED\",\"message\":\"nope\"}}")));

            assertEquals("NOT_CONFIGURED", error.code());
            assertTrue(error.getMessage().contains("nope"));
        }

        @Test
        @DisplayName("the standard error envelope becomes a typed ApiError")
        void structuredErrorEnvelope() {
            ApiError error = Envelopes.errorFor(new RawResponse(404,
                    "{\"success\":false,\"error\":{\"code\":\"NOT_CONFIGURED\","
                            + "\"message\":\"Minecraft integration not enabled\"}}", null));

            assertEquals(404, error.httpStatus());
            assertEquals("NOT_CONFIGURED", error.code());
            assertFalse(error.isRetryable(), "a 404 will still be a 404 next second");
        }

        @Test
        @DisplayName("the HMAC middleware's bare {\"error\":\"Unauthorized\"} is understood too")
        void bareUnauthorizedShape() {
            ApiError error = Envelopes.errorFor(new RawResponse(401, "{\"error\":\"Unauthorized\"}", null));

            assertEquals(401, error.httpStatus());
            assertEquals("UNAUTHORIZED", error.code());
            assertTrue(error.getMessage().contains("Unauthorized"));
        }

        @Test
        @DisplayName("a non-JSON error body still yields a usable error")
        void unparseableErrorBody() {
            ApiError error = Envelopes.errorFor(new RawResponse(502, "<html>Bad Gateway</html>", null));

            assertEquals(502, error.httpStatus());
            assertEquals("HTTP_502", error.code());
            assertTrue(error.isRetryable(), "5xx is worth another go");
        }

        @Test
        void retryabilityFollowsTheStatus() {
            assertTrue(Envelopes.errorFor(new RawResponse(500, "", null)).isRetryable());
            assertTrue(Envelopes.errorFor(new RawResponse(408, "", null)).isRetryable());
            assertTrue(Envelopes.errorFor(new RawResponse(429, "", null)).isRetryable());
            assertFalse(Envelopes.errorFor(new RawResponse(400, "", null)).isRetryable());
            assertFalse(Envelopes.errorFor(new RawResponse(401, "", null)).isRetryable());
            assertFalse(Envelopes.errorFor(new RawResponse(404, "", null)).isRetryable());
        }
    }

    @Nested
    @DisplayName("connection-attempt outcomes")
    class ConnectionOutcomes {

        @Test
        void allowDropsTheWelcomeMessage() {
            ConnectionAttemptResult result =
                    attempt("{\"whitelisted\":true,\"message\":\"§aWelcome back!\",\"roleSync\":null}");

            assertEquals(ConnectionAction.ALLOW, result.action());
            assertTrue(result.whitelisted());
            assertNull(result.message(), "a welcome line is not a kick reason");
        }

        @Test
        void deny() {
            ConnectionAttemptResult result =
                    attempt("{\"whitelisted\":false,\"message\":\"§cnot whitelisted\"}");

            assertEquals(ConnectionAction.DENY, result.action());
            assertFalse(result.whitelisted());
            assertEquals("§cnot whitelisted", result.message());
            assertFalse(result.revoked());
        }

        @Test
        void pendingAuthShowsACodeAndDenies() {
            ConnectionAttemptResult result = attempt(
                    "{\"whitelisted\":false,\"message\":\"code\",\"pendingAuth\":true,\"authCode\":\"135790\"}");

            assertEquals(ConnectionAction.SHOW_AUTH_CODE, result.action());
            assertFalse(result.whitelisted());
            assertEquals("135790", result.authCode());
        }

        @Test
        @DisplayName("revoked is a denial that says so")
        void revoked() {
            ConnectionAttemptResult result =
                    attempt("{\"whitelisted\":false,\"message\":\"revoked\",\"revoked\":true}");

            assertEquals(ConnectionAction.DENY, result.action());
            assertTrue(result.revoked(), "'was whitelisted, no longer is' is a different message");
        }

        @Test
        void pendingApprovalWithAQueuePosition() {
            ConnectionAttemptResult result = attempt(
                    "{\"whitelisted\":false,\"pendingApproval\":true,\"queuePosition\":3,\"message\":\"#3\"}");

            assertEquals(ConnectionAction.PENDING_APPROVAL, result.action());
            assertTrue(result.hasQueuePosition());
            assertEquals(Integer.valueOf(3), result.queuePosition());
        }

        @Test
        @DisplayName("the scheduled branch omits queuePosition entirely, and 0 would be a lie")
        void pendingApprovalWithoutAQueuePosition() {
            ConnectionAttemptResult result = attempt(
                    "{\"whitelisted\":false,\"pendingApproval\":true,\"message\":\"on Friday\"}");

            assertEquals(ConnectionAction.PENDING_APPROVAL, result.action());
            assertFalse(result.hasQueuePosition());
            assertNull(result.queuePosition(),
                    "v2's primitive int made this indistinguishable from 'position zero'");
        }

        @Test
        @DisplayName("existingPlayerLink is whitelisted:true AND an auth code")
        void existingPlayerLink() {
            ConnectionAttemptResult result = attempt(
                    "{\"whitelisted\":true,\"message\":\"link me\",\"existingPlayerLink\":true,"
                            + "\"authCode\":\"246800\"}");

            assertEquals(ConnectionAction.SHOW_AUTH_CODE, result.action());
            assertTrue(result.whitelisted(), "they are let in as well as offered a link");
            assertEquals("246800", result.authCode());
            assertEquals("link me", result.message(), "this message IS shown, unlike a plain allow");
        }
    }

    @Nested
    @DisplayName("roleSync is a tri-state")
    class RoleSync {

        @Test
        void absentMeansNoSnapshot() {
            assertEquals(RoleSyncDirective.absent(), attempt("{\"whitelisted\":true}").roleSync());
        }

        @Test
        void explicitNullIsTheSameAsAbsent() {
            RoleSyncDirective directive = attempt("{\"whitelisted\":true,\"roleSync\":null}").roleSync();

            assertFalse(directive.isPresent());
            assertFalse(directive.isEnabled());
        }

        @Test
        @DisplayName("{enabled:false} is present-but-hands-off, which is not the same as absent")
        void disabledIsPresent() {
            RoleSyncDirective directive =
                    attempt("{\"whitelisted\":true,\"roleSync\":{\"enabled\":false}}").roleSync();

            assertTrue(directive.isPresent());
            assertFalse(directive.isEnabled());
            assertTrue(directive.targetGroups().isEmpty());
        }

        @Test
        void enabledCarriesBothGroupLists() {
            RoleSyncDirective directive = attempt(
                    "{\"whitelisted\":true,\"roleSync\":{\"enabled\":true,"
                            + "\"targetGroups\":[\"vip\"],\"managedGroups\":[\"vip\",\"member\"]}}")
                    .roleSync();

            assertTrue(directive.isPresent());
            assertTrue(directive.isEnabled());
            assertEquals(Arrays.asList("vip"), directive.targetGroups());
            assertEquals(Arrays.asList("vip", "member"), directive.managedGroups());
        }
    }

    @Nested
    @DisplayName("the other endpoints")
    class OtherEndpoints {

        @Test
        void linkCodeIsData() {
            LinkCodeResult minted = ApiResponses.linkCode(
                    ok("{\"success\":true,\"data\":{\"alreadyLinked\":false,\"code\":\"135790\"}}"));
            assertFalse(minted.alreadyLinked());
            assertEquals("135790", minted.code());

            LinkCodeResult linked = ApiResponses.linkCode(ok(
                    "{\"success\":true,\"data\":{\"alreadyLinked\":true,\"message\":\"already linked\","
                            + "\"discordId\":\"42\",\"discordUsername\":\"steve\","
                            + "\"discordDisplayName\":\"Steve\"}}"));
            assertTrue(linked.alreadyLinked(), "v2 threw here, discarding all of the below");
            assertNull(linked.code());
            assertEquals("42", linked.discordId());
            assertEquals("steve", linked.discordUsername());
            assertEquals("Steve", linked.discordDisplayName());
        }

        @Test
        @DisplayName("offense-types comes back as a JSON array in the data slot")
        void offenseTypesArrayEnvelope() {
            List<OffenseType> types = ApiResponses.offenseTypes(ok(
                    "{\"success\":true,\"data\":[{\"typeId\":\"cheating\",\"displayName\":\"Cheating\","
                            + "\"description\":null,\"offenses\":[\"xray\",\"exploiting\"],"
                            + "\"enabled\":true}]}"));

            assertEquals(1, types.size());
            assertEquals("cheating", types.get(0).typeId());
            assertEquals("", types.get(0).description(), "a null description reads as empty");
            assertEquals(Arrays.asList("xray", "exploiting"), types.get(0).offenses());
            assertTrue(types.get(0).enabled());
        }

        @Test
        void offenseResultCarriesTheResolvedCommand() {
            OffenseResult result = ApiResponses.offense(ok(
                    "{\"success\":true,\"data\":{\"infraction\":{\"_id\":\"abc\"},"
                            + "\"command\":\"tempban steve 1d cheating\",\"action\":\"tempban\","
                            + "\"duration\":1440,\"totalPoints\":3,\"tierApplied\":2,"
                            + "\"tierDescription\":\"tempban (1d)\",\"offenseType\":\"Cheating\"}}"));

            assertEquals("abc", result.infractionId());
            assertEquals("tempban steve 1d cheating", result.command());
            assertEquals(Integer.valueOf(1440), result.durationMinutes());
            assertEquals(3, result.totalPoints());
            assertEquals(2, result.tierApplied());
        }

        @Test
        @DisplayName("an explicitly null duration stays null rather than becoming zero")
        void nullDurationIsNull() {
            OffenseResult result = ApiResponses.offense(ok(
                    "{\"success\":true,\"data\":{\"command\":\"warn steve\",\"action\":\"warn\","
                            + "\"duration\":null,\"totalPoints\":1,\"tierApplied\":1}}"));

            assertNull(result.durationMinutes());
            assertEquals("", result.infractionId(), "no infraction object is empty, not null");
        }

        @Test
        void pluginRelease() {
            PluginRelease release = ApiResponses.pluginRelease(ok(
                    "{\"success\":true,\"data\":{\"version\":\"v3.0.0\","
                            + "\"downloadUrl\":\"https://example/heimdall.jar\",\"releaseNotes\":null,"
                            + "\"htmlUrl\":\"https://example/tag\",\"publishedAt\":\"2026-01-01T00:00:00.000Z\"}}"));

            assertEquals("v3.0.0", release.version());
            assertEquals("https://example/heimdall.jar", release.downloadUrl());
            assertEquals("", release.releaseNotes());
        }

        @Test
        void whitelistSyncReadsThePlayers() {
            WhitelistSyncResult result = ApiResponses.whitelistSync(new RawResponse(200,
                    "{\"success\":true,\"data\":{\"hash\":\"deadbeef\",\"count\":2,"
                            + "\"generatedAt\":\"2026-01-01T00:00:00.000Z\",\"players\":["
                            + "{\"uuid\":\"u1\",\"username\":\"Steve\"},"
                            + "{\"uuid\":\"u2\",\"username\":null}]}}",
                    "\"deadbeef\""));

            assertFalse(result.notModified());
            assertEquals("\"deadbeef\"", result.etag());
            assertEquals("deadbeef", result.hash());
            assertEquals(2, result.count());
            assertEquals(2, result.players().size());
            assertNull(result.players().get(1).username(), "the bot sends an explicit null name");
        }

        @Test
        @DisplayName("entries with no uuid are dropped rather than counted")
        void whitelistSyncSkipsUuidlessEntries() {
            WhitelistSyncResult result = ApiResponses.whitelistSync(new RawResponse(200,
                    "{\"data\":{\"players\":[{\"uuid\":\"u1\"},{\"username\":\"nobody\"},{\"uuid\":\"  \"}]}}",
                    null));

            assertEquals(1, result.players().size());
        }

        @Test
        @DisplayName("a 304 is a result, not an empty whitelist")
        void whitelistSyncNotModified() {
            WhitelistSyncResult result =
                    ApiResponses.whitelistSync(new RawResponse(304, "", "\"deadbeef\""));

            assertTrue(result.notModified());
            assertEquals("\"deadbeef\"", result.etag());
            assertTrue(result.players().isEmpty(),
                    "and the caller must not reconcile against this — see the javadoc");
        }
    }
}
