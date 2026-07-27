package com.heimdall.stubbot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** End-to-end tests over the real socket: signing, routing, and every response shape. */
class StubHttpApiTest {

    private static final String GUILD = StubBotConfig.DEFAULT_GUILD_ID;
    private static final String KEY = "unit-test-key";
    private static final String PREFIX = "/api/guilds/" + GUILD + "/minecraft";

    private StubBot bot;
    private SignedClient client;

    @BeforeEach
    void startBot() {
        bot = StubBot.start(StubBotConfig.withDemoFixtures().port(0).apiKey(KEY).bindHost("127.0.0.1"));
        client = new SignedClient(bot.baseUrl(), KEY);
    }

    @AfterEach
    void stopBot() {
        if (bot != null) {
            bot.close();
        }
    }

    private static String attempt(String uuid, String username) {
        return "{\"username\":\"" + username + "\",\"uuid\":\"" + uuid + "\",\"ip\":\"127.0.0.1\"}";
    }

    // ── Authentication ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("HMAC gate")
    class Auth {

        @Test
        void unsignedRequestsAreRejected() throws Exception {
            HttpResponse<String> response =
                    client.postUnsigned(PREFIX + "/connection-attempt", attempt("x", "y"));
            assertEquals(401, response.statusCode());
        }

        @Test
        void aSignatureOverADifferentPathIsRejected() throws Exception {
            HttpResponse<String> response =
                    client.postWithBadSignature(PREFIX + "/connection-attempt", attempt("x", "y"));
            assertEquals(401, response.statusCode());
        }

        @Test
        void aStaleTimestampIsRejected() throws Exception {
            HttpResponse<String> response =
                    client.postWithStaleTimestamp(PREFIX + "/connection-attempt", attempt("x", "y"));
            assertEquals(401, response.statusCode());
        }

        @Test
        @DisplayName("a 401 is a BARE {error} object, not the success envelope — reproduced on purpose")
        void unauthorizedUsesTheBotsInconsistentShape() throws Exception {
            JsonObject body = SignedClient.envelope(
                    client.postUnsigned(PREFIX + "/connection-attempt", attempt("x", "y")));
            assertEquals("Unauthorized", body.get("error").getAsString());
            assertFalse(body.has("success"),
                    "the real bot's HMAC middleware does not wrap this one; a client that assumes "
                            + "error.code on every failure breaks against it, so the stub must not "
                            + "be tidier than the thing it stands in for");
        }

        @Test
        void anUnknownGuildIsNotConfigured() throws Exception {
            HttpResponse<String> response = client.post(
                    "/api/guilds/999999999999999999/minecraft/connection-attempt", attempt("x", "y"));
            assertEquals(404, response.statusCode());
            assertEquals("NOT_CONFIGURED",
                    SignedClient.envelope(response).getAsJsonObject("error").get("code").getAsString());
        }
    }

    // ── connection-attempt ───────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /connection-attempt covers all six outcomes")
    class ConnectionAttempt {

        @Test
        void missingFieldsIsA400() throws Exception {
            HttpResponse<String> response = client.post(PREFIX + "/connection-attempt", "{}");
            assertEquals(400, response.statusCode());
            assertEquals("MISSING_FIELDS",
                    SignedClient.envelope(response).getAsJsonObject("error").get("code").getAsString());
        }

        @Test
        void allow() throws Exception {
            JsonObject data = SignedClient.data(client.post(PREFIX + "/connection-attempt",
                    attempt("11111111-1111-1111-1111-111111111111", "AllowedSteve")));
            assertTrue(data.get("whitelisted").getAsBoolean());
            assertTrue(data.get("message").getAsString().contains("AllowedSteve"),
                    "{player} must be substituted");

            JsonObject roleSync = data.getAsJsonObject("roleSync");
            assertTrue(roleSync.get("enabled").getAsBoolean());
            assertEquals(List.of("vip"), strings(roleSync.getAsJsonArray("targetGroups")));
            assertEquals(List.of("vip", "member"), strings(roleSync.getAsJsonArray("managedGroups")));
        }

        @Test
        void deny() throws Exception {
            JsonObject data = SignedClient.data(client.post(PREFIX + "/connection-attempt",
                    attempt("22222222-2222-2222-2222-222222222222", "DeniedAlex")));
            assertFalse(data.get("whitelisted").getAsBoolean());
            assertFalse(data.has("pendingAuth"));
            assertFalse(data.has("revoked"));
        }

        @Test
        void pendingAuth() throws Exception {
            JsonObject data = SignedClient.data(client.post(PREFIX + "/connection-attempt",
                    attempt("33333333-3333-3333-3333-333333333333", "PendingCode")));
            assertFalse(data.get("whitelisted").getAsBoolean());
            assertTrue(data.get("pendingAuth").getAsBoolean());
            assertEquals("135790", data.get("authCode").getAsString());
            assertTrue(data.get("message").getAsString().contains("135790"),
                    "{code} must be substituted into the message the player actually sees");
        }

        @Test
        void revoked() throws Exception {
            JsonObject data = SignedClient.data(client.post(PREFIX + "/connection-attempt",
                    attempt("44444444-4444-4444-4444-444444444444", "RevokedRita")));
            assertFalse(data.get("whitelisted").getAsBoolean());
            assertTrue(data.get("revoked").getAsBoolean());
            assertTrue(data.get("message").getAsString().contains("for griefing"));
        }

        @Test
        void pendingApproval() throws Exception {
            JsonObject data = SignedClient.data(client.post(PREFIX + "/connection-attempt",
                    attempt("55555555-5555-5555-5555-555555555555", "QueuedQuinn")));
            assertFalse(data.get("whitelisted").getAsBoolean());
            assertTrue(data.get("pendingApproval").getAsBoolean());
            assertEquals(3, data.get("queuePosition").getAsInt());
            assertTrue(data.get("message").getAsString().contains("#3"));
        }

        @Test
        @DisplayName("existing-link lets the player IN while offering a code — whitelisted is true")
        void existingLink() throws Exception {
            JsonObject data = SignedClient.data(client.post(PREFIX + "/connection-attempt",
                    attempt("66666666-6666-6666-6666-666666666666", "LegacyLee")));
            assertTrue(data.get("whitelisted").getAsBoolean(),
                    "this is the outcome most easily got wrong: the player is admitted, not bounced");
            assertTrue(data.get("existingPlayerLink").getAsBoolean());
            assertEquals("246800", data.get("authCode").getAsString());
        }

        @Test
        void anUnknownPlayerGetsTheDefaultOutcome() throws Exception {
            JsonObject data = SignedClient.data(client.post(PREFIX + "/connection-attempt",
                    attempt("99999999-9999-9999-9999-999999999999", "Nobody")));
            assertFalse(data.get("whitelisted").getAsBoolean());
        }

        @Test
        @DisplayName("roleSync has three shapes and the fixture can select each")
        void roleSyncShapes() throws Exception {
            bot.fixtures().put(PlayerFixture.of("aaaaaaaa-0000-0000-0000-000000000001", "NoSnapshot", Outcome.ALLOW));
            bot.fixtures().put(PlayerFixture.of("aaaaaaaa-0000-0000-0000-000000000002", "RconMode", Outcome.ALLOW)
                    .withRoleSyncEnabled(false));

            JsonObject none = SignedClient.data(client.post(PREFIX + "/connection-attempt",
                    attempt("aaaaaaaa-0000-0000-0000-000000000001", "NoSnapshot")));
            assertTrue(none.get("roleSync").isJsonNull(), "a row with no snapshot yet sends null");

            JsonObject rcon = SignedClient.data(client.post(PREFIX + "/connection-attempt",
                    attempt("aaaaaaaa-0000-0000-0000-000000000002", "RconMode")));
            assertFalse(rcon.getAsJsonObject("roleSync").get("enabled").getAsBoolean(),
                    "RCON mode tells the plugin to keep its hands off LuckPerms");
        }

        @Test
        @DisplayName("an explicit null goes out as a null, not as an absent key")
        void nullsAreSerialisedNotDropped() throws Exception {
            bot.fixtures().put(PlayerFixture.of("aaaaaaaa-0000-0000-0000-000000000003", "NoSnapshot", Outcome.ALLOW));
            String raw = client.post(PREFIX + "/connection-attempt",
                    attempt("aaaaaaaa-0000-0000-0000-000000000003", "NoSnapshot")).body();
            assertTrue(raw.contains("\"roleSync\":null"),
                    "Gson drops nulls unless told not to, which would turn a null field into a "
                            + "missing one — a difference the plugin's parser can see. Raw body: " + raw);
        }
    }

    // ── whitelist/sync ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /whitelist/sync")
    class WhitelistSync {

        @Test
        void returnsTheWhitelistWithAStableEtag() throws Exception {
            HttpResponse<String> response = client.get(PREFIX + "/whitelist/sync");
            assertEquals(200, response.statusCode());

            JsonObject data = SignedClient.data(response);
            assertEquals(2, data.get("count").getAsInt(), "ALLOW + EXISTING_LINK from the demo fixtures");
            assertEquals(2, data.getAsJsonArray("players").size());
            assertTrue(data.get("generatedAt").getAsString().endsWith("Z"));

            String etag = response.headers().firstValue("ETag").orElseThrow();
            assertEquals("\"" + data.get("hash").getAsString() + "\"", etag,
                    "the header and the body hash must agree or the poll loop thrashes");
        }

        @Test
        @DisplayName("If-None-Match with the current hash gets a bodyless 304")
        void conditionalGet() throws Exception {
            HttpResponse<String> first = client.get(PREFIX + "/whitelist/sync");
            String etag = first.headers().firstValue("ETag").orElseThrow();

            HttpResponse<String> quoted = client.get(PREFIX + "/whitelist/sync", etag);
            assertEquals(304, quoted.statusCode());
            assertTrue(quoted.body().isEmpty());

            HttpResponse<String> unquoted = client.get(PREFIX + "/whitelist/sync", etag.replace("\"", ""));
            assertEquals(304, unquoted.statusCode(),
                    "the bot strips quotes before comparing, so both spellings must work");
        }

        @Test
        @DisplayName("changing the whitelist invalidates the ETag")
        void mutationInvalidatesTheEtag() throws Exception {
            String before = client.get(PREFIX + "/whitelist/sync").headers().firstValue("ETag").orElseThrow();

            bot.fixtures().put(PlayerFixture.of(
                    "11111111-1111-1111-1111-111111111111", "AllowedSteve", Outcome.REVOKED));

            HttpResponse<String> conditional = client.get(PREFIX + "/whitelist/sync", before);
            assertEquals(200, conditional.statusCode(), "a revoked player must not stay cached");
            assertEquals(1, SignedClient.data(conditional).get("count").getAsInt());
        }
    }

    // ── The remaining endpoints ──────────────────────────────────────────────

    @Test
    void requestLinkCodeMintsACode() throws Exception {
        JsonObject data = SignedClient.data(client.post(PREFIX + "/request-link-code",
                "{\"username\":\"PendingCode\",\"uuid\":\"33333333-3333-3333-3333-333333333333\"}"));
        assertFalse(data.get("alreadyLinked").getAsBoolean());
        assertEquals("135790", data.get("code").getAsString());
    }

    @Test
    void requestLinkCodeReportsAnAlreadyLinkedAccount() throws Exception {
        bot.fixtures().put(PlayerFixture.of("bbbbbbbb-0000-0000-0000-000000000001", "Linked", Outcome.ALLOW)
                .linkedTo("999888777666555444", "linked_user", "Linked User"));

        JsonObject data = SignedClient.data(client.post(PREFIX + "/request-link-code",
                "{\"username\":\"Linked\",\"uuid\":\"bbbbbbbb-0000-0000-0000-000000000001\"}"));
        assertTrue(data.get("alreadyLinked").getAsBoolean());
        assertEquals("999888777666555444", data.get("discordId").getAsString());
        assertTrue(data.get("message").getAsString().contains("@linked_user"));
    }

    @Test
    void offenseTypesAreReturnedAsAnArray() throws Exception {
        JsonObject envelope = SignedClient.envelope(client.get(PREFIX + "/offense-types"));
        JsonArray types = envelope.getAsJsonArray("data");
        assertEquals(1, types.size());
        assertEquals("cheating", types.get(0).getAsJsonObject().get("typeId").getAsString());
    }

    @Test
    void pluginLatestIsStaticReleaseMetadata() throws Exception {
        JsonObject data = SignedClient.data(client.get(PREFIX + "/plugin/latest"));
        assertEquals("v3.0.0", data.get("version").getAsString());
        assertTrue(data.get("downloadUrl").getAsString().endsWith(".jar"));
    }

    @Nested
    @DisplayName("POST /offend escalates")
    class Offend {

        private String offend(String slug) {
            return "{\"targetUuid\":\"cccccccc-0000-0000-0000-000000000001\","
                    + "\"targetUsername\":\"Cheater\",\"offenseSlug\":\"" + slug + "\"}";
        }

        @Test
        void unknownSlugIs404() throws Exception {
            HttpResponse<String> response = client.post(PREFIX + "/offend", offend("nonsense"));
            assertEquals(404, response.statusCode());
            assertEquals("UNKNOWN_OFFENSE",
                    SignedClient.envelope(response).getAsJsonObject("error").get("code").getAsString());
        }

        @Test
        @DisplayName("repeated offences climb the tiers, and placeholders resolve")
        void escalation() throws Exception {
            bot.resetInfractions();

            JsonObject first = SignedClient.data(client.post(PREFIX + "/offend", offend("xray")));
            assertEquals(1, first.get("totalPoints").getAsInt());
            assertEquals(1, first.get("tierApplied").getAsInt());
            assertEquals("warn", first.get("action").getAsString());
            assertEquals("warn Cheater Cheating — warning (1 point)", first.get("command").getAsString(),
                    "{reason} resolves before {player}/{offense}/{points} are substituted into the command");

            SignedClient.data(client.post(PREFIX + "/offend", offend("exploiting")));

            JsonObject third = SignedClient.data(client.post(PREFIX + "/offend", offend("xray")));
            assertEquals(3, third.get("totalPoints").getAsInt(),
                    "the two slugs share an offense type, so they share a counter");
            assertEquals(2, third.get("tierApplied").getAsInt());
            assertEquals("tempban", third.get("action").getAsString());
            assertEquals(1440, third.get("duration").getAsInt());
            assertEquals("tempban (1d)", third.get("tierDescription").getAsString());
        }

        @Test
        @DisplayName("running off the end of the tiers pins to the highest one")
        void beyondTheLastTier() throws Exception {
            bot.resetInfractions();
            JsonObject last = null;
            for (int i = 0; i < 7; i++) {
                last = SignedClient.data(client.post(PREFIX + "/offend", offend("xray")));
            }
            assertEquals(7, last.get("totalPoints").getAsInt());
            assertEquals(3, last.get("tierApplied").getAsInt());
            assertEquals("permban", last.get("action").getAsString());
            assertTrue(last.get("duration").isJsonNull());
        }
    }

    @Test
    @DisplayName("the duration formatter matches the bot's")
    void durationFormatting() {
        assertEquals("", StubHttpApi.formatDuration(null));
        assertEquals("", StubHttpApi.formatDuration(0));
        assertEquals("1h", StubHttpApi.formatDuration(60));
        assertEquals("1d", StubHttpApi.formatDuration(1440));
        assertEquals("7d", StubHttpApi.formatDuration(10080));
        assertEquals("1h30m", StubHttpApi.formatDuration(90));
        assertEquals("30m", StubHttpApi.formatDuration(30));
    }

    @Test
    @DisplayName("POST /api/minecraft/identify resolves the API key to a guild id")
    void identifyResolvesTheGuild() throws Exception {
        JsonObject data = SignedClient.data(client.post("/api/minecraft/identify", "{}"));
        assertEquals(GUILD, data.get("guildId").getAsString());

        HttpResponse<String> unsigned = client.postUnsigned("/api/minecraft/identify", "{}");
        assertEquals(401, unsigned.statusCode());
        assertEquals("UNAUTHORIZED",
                SignedClient.envelope(unsigned).getAsJsonObject("error").get("code").getAsString(),
                "this route authenticates inline and DOES use the envelope, unlike the guild-route gate");
    }

    private static List<String> strings(JsonArray array) {
        return array.asList().stream().map(element -> element.getAsString()).toList();
    }
}
