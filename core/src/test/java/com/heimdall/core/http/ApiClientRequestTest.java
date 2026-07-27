package com.heimdall.core.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.heimdall.core.BuildConstants;
import com.heimdall.core.concurrent.HeimdallExecutors;
import com.heimdall.core.http.model.ConnectionAttempt;
import com.heimdall.core.log.RecordingLogger;
import com.heimdall.stubbot.Hmac;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * What the client puts on the wire, and what its retry loop does — the half {@code :stub-bot}
 * cannot show, because it never echoes a request body and cannot be told to fail on cue.
 *
 * <p>The signature assertions verify against {@code stub-bot}'s own {@link Hmac}, which is the
 * bot's verifier transcribed. So "we signed it correctly" is checked by the same code that would
 * reject us in production, not by re-deriving the signature with the class under test.
 */
class ApiClientRequestTest {

    private static final String GUILD = "123456789012345678";
    private static final String SECRET = "test-secret-key";
    private static final String UUID = "11111111-2222-3333-4444-555555555555";

    private final RecordingLogger logger = new RecordingLogger(true);

    private RecordingHttpServer server;
    private HeimdallExecutors executors;
    private ApiClient client;

    @BeforeEach
    void start() {
        server = new RecordingHttpServer();
        executors = new HeimdallExecutors(logger, 2);
        client = new ApiClient(logger, settings(3, 20), executors.io());
    }

    @AfterEach
    void stop() {
        if (executors != null) {
            executors.shutdown(2000);
        }
        if (server != null) {
            server.close();
        }
    }

    private ApiSettings settings(int retries, int retryDelayMs) {
        return ApiSettings.builder()
                .baseUrl(server.baseUrl())
                .guildId(GUILD)
                .apiKey(SECRET)
                .serverId("survival")
                .timeoutMs(2000)
                .retries(retries)
                .retryDelayMs(retryDelayMs)
                .build();
    }

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

    private void connect(ConnectionAttempt attempt) throws Exception {
        await(client.connectionAttempt(attempt));
    }

    private static JsonObject bodyOf(RecordingHttpServer.Request request) {
        return JsonParser.parseString(request.body).getAsJsonObject();
    }

    // ── The request itself ───────────────────────────────────────────────────

    @Nested
    @DisplayName("what goes on the wire")
    class TheRequest {

        @Test
        void connectionAttemptCarriesEveryField() throws Exception {
            connect(ConnectionAttempt.builder("AllowedSteve", UUID)
                    .ip("203.0.113.7")
                    .serverIp("play.example.com")
                    .currentlyWhitelisted(true)
                    .currentGroups(Arrays.asList("default", "vip"))
                    .build());

            RecordingHttpServer.Request request = server.lastRequest();
            assertEquals("POST", request.method);
            assertEquals("/api/guilds/" + GUILD + "/minecraft/connection-attempt", request.path);

            JsonObject body = bodyOf(request);
            assertEquals("AllowedSteve", body.get("username").getAsString(),
                    "verbatim, not lower-cased");
            assertEquals(UUID, body.get("uuid").getAsString());
            assertEquals("203.0.113.7", body.get("ip").getAsString());
            assertEquals("play.example.com", body.get("serverIp").getAsString());
            assertEquals("survival", body.get("serverId").getAsString(),
                    "the serverId comes from settings, not from the caller");
            assertTrue(body.get("currentlyWhitelisted").getAsBoolean());
            assertEquals(2, body.getAsJsonArray("currentGroups").size());
        }

        @Test
        @DisplayName("the link-code body is verbatim too — this is the one that reached the database")
        void linkCodeUsernameIsVerbatim() throws Exception {
            server.respond(200, "{\"success\":true,\"data\":{\"alreadyLinked\":false,\"code\":\"135790\"}}");

            await(client.requestLinkCode("  Steve  ", UUID));

            assertEquals("Steve", bodyOf(server.lastRequest()).get("username").getAsString(),
                    "link.ts writes minecraftUsername straight from this body; v2's lower-casing "
                            + "silently rewrote every linked player's name");
        }

        @Test
        @DisplayName("a blank serverIp defaults to localhost, as in v2")
        void serverIpDefaults() throws Exception {
            connect(ConnectionAttempt.builder("Steve", UUID).build());

            assertEquals("localhost", bodyOf(server.lastRequest()).get("serverIp").getAsString());
        }

        @Test
        void everyRequestAnnouncesTheVersion() throws Exception {
            connect(ConnectionAttempt.builder("Steve", UUID).build());

            assertEquals("Heimdall/" + BuildConstants.VERSION,
                    server.lastRequest().header("User-Agent"));
        }

        @Test
        @DisplayName("the signature verifies against the bot's own verifier")
        void signatureVerifiesAgainstTheReferenceImplementation() throws Exception {
            connect(ConnectionAttempt.builder("Steve", UUID).build());

            RecordingHttpServer.Request request = server.lastRequest();
            String signature = request.header("X-Signature");
            String timestamp = request.header("X-Timestamp");
            assertNotNull(signature);
            assertNotNull(timestamp);

            assertTrue(Hmac.verify(SECRET, request.method, request.path, request.body, signature, timestamp),
                    "the bot's transcribed verifier should accept what we sent");
            assertFalse(
                    Hmac.verify(SECRET, request.method, request.path + "?tampered=1", request.body,
                            signature, timestamp),
                    "and reject it once the signed path changes — otherwise the check proves nothing");
        }

        @Test
        @DisplayName("a GET signs over the empty-body hash")
        void getRequestsAreSignedToo() throws Exception {
            server.respond(200, "{\"success\":true,\"data\":[]}");

            await(client.offenseTypes());

            RecordingHttpServer.Request request = server.lastRequest();
            assertEquals("GET", request.method);
            assertEquals("", request.body);
            assertTrue(Hmac.verify(SECRET, "GET", request.path, "",
                    request.header("X-Signature"), request.header("X-Timestamp")));
        }

        @Test
        @DisplayName("If-None-Match is sent only when an ETag is supplied")
        void conditionalWhitelistSync() throws Exception {
            server.respond(200, "{\"success\":true,\"data\":{\"players\":[]}}");

            await(client.whitelistSync(null));
            assertNull(server.lastRequest().header("If-None-Match"));

            await(client.whitelistSync("\"deadbeef\""));
            assertEquals("\"deadbeef\"", server.lastRequest().header("If-None-Match"));

            await(client.whitelistSync("   "));
            assertNull(server.lastRequest().header("If-None-Match"), "a blank ETag is no ETag");
        }

        @Test
        @DisplayName("an unsigned client warns rather than silently sending a request that 401s")
        void missingApiKeyIsReported() throws Exception {
            client.reconfigure(settings(1, 0).toBuilder().apiKey("").build());

            connect(ConnectionAttempt.builder("Steve", UUID).build());

            assertNull(server.lastRequest().header("X-Signature"));
            assertFalse(logger.at(com.heimdall.core.log.LogLevel.WARN).isEmpty(),
                    "an unsigned request that will certainly 401 deserves a local explanation");
        }
    }

    // ── Bedrock enrichment ───────────────────────────────────────────────────

    @Nested
    @DisplayName("Bedrock identity enrichment")
    class BedrockEnrichment {

        @Test
        @DisplayName("nothing is added for a Java player")
        void absentByDefault() throws Exception {
            connect(ConnectionAttempt.builder("Steve", UUID).build());

            JsonObject body = bodyOf(server.lastRequest());
            assertFalse(body.has("isBedrock"));
            assertFalse(body.has("bedrockGamertag"));
        }

        @Test
        void gamertagAndXuidAreMergedIn() throws Exception {
            client.setBedrockIdentityProvider(uuid -> new BedrockIdentity("BedrockBob", "2535467890123456"));

            connect(ConnectionAttempt.builder(".BedrockBob", UUID).build());

            JsonObject body = bodyOf(server.lastRequest());
            assertTrue(body.get("isBedrock").getAsBoolean());
            assertEquals("BedrockBob", body.get("bedrockGamertag").getAsString(),
                    "the gamertag is prefix-free; the username still carries the Floodgate prefix");
            assertEquals("2535467890123456", body.get("bedrockXuid").getAsString());
            assertEquals(".BedrockBob", body.get("username").getAsString(),
                    "the username keeps its Floodgate prefix and its case");
        }

        @Test
        @DisplayName("a missing XUID is omitted rather than sent as null")
        void missingXuidIsOmitted() throws Exception {
            client.setBedrockIdentityProvider(uuid -> new BedrockIdentity("BedrockBob", null));

            connect(ConnectionAttempt.builder("Bob", UUID).build());

            JsonObject body = bodyOf(server.lastRequest());
            assertTrue(body.get("isBedrock").getAsBoolean());
            assertFalse(body.has("bedrockXuid"));
        }

        @Test
        void linkCodeRequestsAreEnrichedToo() throws Exception {
            client.setBedrockIdentityProvider(uuid -> new BedrockIdentity("BedrockBob", "123"));
            server.respond(200, "{\"success\":true,\"data\":{\"alreadyLinked\":false,\"code\":\"135790\"}}");

            await(client.requestLinkCode("Bob", UUID));

            assertTrue(bodyOf(server.lastRequest()).get("isBedrock").getAsBoolean());
        }

        @Test
        @DisplayName("a provider that throws costs nobody their login")
        void throwingProviderIsContained() throws Exception {
            client.setBedrockIdentityProvider(uuid -> {
                throw new IllegalStateException("Floodgate blew up");
            });

            connect(ConnectionAttempt.builder("Steve", UUID).build());

            assertFalse(bodyOf(server.lastRequest()).has("isBedrock"));
            assertFalse(logger.at(com.heimdall.core.log.LogLevel.WARN).isEmpty(),
                    "swallowing a broken provider silently would hide a misinstalled Floodgate");
        }

        @Test
        void aBlankGamertagIsTreatedAsNoIdentity() throws Exception {
            client.setBedrockIdentityProvider(uuid -> new BedrockIdentity("  ", "123"));

            connect(ConnectionAttempt.builder("Steve", UUID).build());

            assertFalse(bodyOf(server.lastRequest()).has("isBedrock"));
        }
    }

    // ── The retry loop ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("the retry loop")
    class Retries {

        @Test
        @DisplayName("a 5xx is retried and the eventual success is returned")
        void serverErrorsAreRetried() throws Exception {
            server.failFirst(2, 503);
            server.respond(200, "{\"success\":true,\"data\":{\"whitelisted\":true}}");

            connect(ConnectionAttempt.builder("Steve", UUID).build());

            assertEquals(3, server.requestCount(), "two failures then the success");
        }

        @Test
        @DisplayName("once the attempts run out, the last failure is what surfaces")
        void exhaustedRetriesThrowTheLastError() {
            server.failFirst(99, 500);

            ApiError error = assertThrows(ApiError.class,
                    () -> connect(ConnectionAttempt.builder("Steve", UUID).build()));

            assertEquals(500, error.httpStatus());
            assertEquals("SCRIPTED", error.code());
            assertEquals(3, server.requestCount(), "exactly `retries` attempts, no more");
        }

        @Test
        @DisplayName("a 4xx is not retried — it will say the same thing next time")
        void clientErrorsAreNotRetried() {
            server.failFirst(99, 400);

            ApiError error = assertThrows(ApiError.class,
                    () -> connect(ConnectionAttempt.builder("Steve", UUID).build()));

            assertEquals(400, error.httpStatus());
            assertEquals(1, server.requestCount(),
                    "v2 spent three signed round trips being told the same thing about a bad body");
        }

        @Test
        @DisplayName("429 and 408 are the 4xx exceptions, because they invite a retry")
        void rateLimitsAreRetried() {
            server.failFirst(99, 429);

            assertThrows(ApiError.class, () -> connect(ConnectionAttempt.builder("Steve", UUID).build()));

            assertEquals(3, server.requestCount());
        }

        @Test
        @DisplayName("an unreachable bot surfaces as a transport failure, not an ApiError")
        void transportFailuresAreDistinguishable() {
            // A port nothing is listening on: the connect itself fails, so no HTTP status exists.
            client.reconfigure(ApiSettings.builder()
                    .baseUrl("http://127.0.0.1:1")
                    .guildId(GUILD)
                    .apiKey(SECRET)
                    .timeoutMs(ApiSettings.MIN_TIMEOUT_MS)
                    .retries(2)
                    .retryDelayMs(10)
                    .build());

            assertThrows(UncheckedIOException.class,
                    () -> connect(ConnectionAttempt.builder("Steve", UUID).build()),
                    "'the bot is down' and 'the bot said no' are different decisions for a caller");
        }
    }
}
