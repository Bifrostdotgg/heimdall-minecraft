package com.heimdall.stubbot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The two endpoints a plugin uses to set itself up, and their sharp edges.
 *
 * <p>Both are transcribed from the bot's {@code feat/minecraft-v3-protocol} branch, and both have
 * behaviour a reasonable person would guess wrong:
 *
 * <ul>
 *   <li>the claim endpoint is <strong>public</strong> — no signature, because the caller has nothing
 *       to sign with yet — and it silently drops an invalid {@code role} rather than rejecting it;
 *   <li>the config import is <strong>write-once</strong>, and a second import is a 200 that changed
 *       nothing rather than a conflict.
 * </ul>
 */
class ClaimAndImportTest {

    private static final String GUILD = StubBotConfig.DEFAULT_GUILD_ID;
    private static final String KEY = StubBotConfig.DEFAULT_API_KEY;

    private StubBot bot;
    private SignedClient client;

    private void boot() {
        boot(StubBotConfig.withDemoFixtures());
    }

    private void boot(StubBotConfig config) {
        bot = StubBot.start(config.bindHost("127.0.0.1").port(0));
        client = new SignedClient(bot.baseUrl(), KEY);
    }

    @AfterEach
    void tearDown() {
        if (bot != null) {
            bot.close();
        }
    }

    private String claimBody(String code) {
        JsonObject body = new JsonObject();
        body.addProperty("code", code);
        body.addProperty("platform", "bukkit");
        body.addProperty("mcVersion", "1.21.8");
        body.addProperty("role", "standalone");
        return body.toString();
    }

    @Nested
    @DisplayName("POST /api/minecraft/claim")
    class Claim {

        @Test
        @DisplayName("a valid code returns credentials, unsigned")
        void validCodeReturnsCredentials() throws Exception {
            boot();
            bot.http().issueClaimCode("ABCD2345", "Survival");

            // Unsigned on purpose. This is the one endpoint ahead of the HMAC gate, because a
            // server claiming a code has no token to sign with — which is the whole point.
            HttpResponse<String> response =
                    client.postUnsigned("/api/minecraft/claim", claimBody("ABCD2345"));

            assertEquals(200, response.statusCode());
            JsonObject data = SignedClient.data(response);
            assertEquals(GUILD, data.get("guildId").getAsString());
            assertEquals("Survival", data.get("serverName").getAsString());
            assertNotNull(data.get("tokenId"));
            assertNotNull(data.get("token"), "the plaintext key, returned exactly once");
            assertFalse(data.get("serverId").getAsString().isEmpty());
        }

        @Test
        @DisplayName("a dashed or spaced transcription of the code is accepted")
        void codeIsNormalised() throws Exception {
            boot();
            bot.http().issueClaimCode("ABCD2345", "Survival");

            HttpResponse<String> response =
                    client.postUnsigned("/api/minecraft/claim", claimBody("abcd-2345"));

            assertEquals(200, response.statusCode(),
                    "an operator reads the code off a screen and types it with the dash they see");
        }

        @Test
        @DisplayName("a code is single-use")
        void codeIsConsumed() throws Exception {
            boot();
            bot.http().issueClaimCode("ABCD2345", "Survival");

            assertEquals(200,
                    client.postUnsigned("/api/minecraft/claim", claimBody("ABCD2345")).statusCode());
            HttpResponse<String> second =
                    client.postUnsigned("/api/minecraft/claim", claimBody("ABCD2345"));

            assertEquals(401, second.statusCode());
            assertEquals("INVALID_CODE",
                    SignedClient.envelope(second).getAsJsonObject("error").get("code").getAsString());
        }

        @Test
        @DisplayName("a missing code is 400, an unknown one is 401")
        void badRequests() throws Exception {
            boot();

            HttpResponse<String> missing = client.postUnsigned("/api/minecraft/claim", "{}");
            assertEquals(400, missing.statusCode());
            assertEquals("MISSING_PARAMS", SignedClient.envelope(missing)
                    .getAsJsonObject("error").get("code").getAsString());

            HttpResponse<String> unknown =
                    client.postUnsigned("/api/minecraft/claim", claimBody("ZZZZ9999"));
            assertEquals(401, unknown.statusCode());
        }

        @Test
        @DisplayName("an invalid role is dropped, not rejected")
        void invalidRoleIsSilentlyDropped() throws Exception {
            boot();
            bot.http().issueClaimCode("ABCD2345", "Survival");
            JsonObject body = new JsonObject();
            body.addProperty("code", "ABCD2345");
            body.addProperty("role", "supervisor");

            HttpResponse<String> response =
                    client.postUnsigned("/api/minecraft/claim", body.toString());

            assertEquals(200, response.statusCode(),
                    "a client sending a typo gets a successful claim and a server with no role — "
                            + "surprising, and it is what the bot does");
        }

        @Test
        @DisplayName("repeated failures are throttled")
        void failuresAreThrottled() throws Exception {
            boot();

            for (int attempt = 0; attempt < 10; attempt++) {
                client.postUnsigned("/api/minecraft/claim", claimBody("ZZZZ9999"));
            }
            HttpResponse<String> throttled =
                    client.postUnsigned("/api/minecraft/claim", claimBody("ZZZZ9999"));

            assertEquals(429, throttled.statusCode(),
                    "an unauthenticated route that mints API tokens is a brute-force target");
            assertEquals("TOO_MANY_ATTEMPTS", SignedClient.envelope(throttled)
                    .getAsJsonObject("error").get("code").getAsString());
        }

        @Test
        @DisplayName("a claimed server becomes registered, so its tunnel gets config")
        void claimingRegistersTheServer() throws Exception {
            boot(StubBotConfig.withDemoFixtures().unregisterServer("survival"));
            assertFalse(bot.config().isRegistered("survival"));
            bot.http().issueClaimCode("ABCD2345", "Survival");

            HttpResponse<String> response =
                    client.postUnsigned("/api/minecraft/claim", claimBody("ABCD2345"));
            String serverId = SignedClient.data(response).get("serverId").getAsString();

            assertTrue(bot.config().isRegistered(serverId),
                    "the claim is what writes the registry row the WebSocket upgrade looks up");
        }
    }

    @Nested
    @DisplayName("POST .../servers/{serverId}/config/import")
    class ConfigImport {

        private String importPath(String serverId) {
            return "/api/guilds/" + GUILD + "/minecraft/servers/" + serverId + "/config/import";
        }

        private String modulesBody() {
            JsonObject whitelist = new JsonObject();
            whitelist.addProperty("enabled", true);
            whitelist.addProperty("failOpen", false);
            JsonObject modules = new JsonObject();
            modules.add("whitelist", whitelist);
            JsonObject body = new JsonObject();
            body.add("modules", modules);
            return body.toString();
        }

        @Test
        @DisplayName("the first import creates the document")
        void firstImportCreates() throws Exception {
            boot();

            HttpResponse<String> response = client.post(importPath("survival"), modulesBody());

            assertEquals(200, response.statusCode());
            JsonObject data = SignedClient.data(response);
            assertTrue(data.get("imported").getAsBoolean());
            assertEquals(1, data.get("version").getAsInt());
            assertTrue(data.getAsJsonObject("modules").has("whitelist"));
        }

        @Test
        @DisplayName("a second import changes nothing, and says so with a 200")
        void secondImportIsWriteOnce() throws Exception {
            boot();
            client.post(importPath("survival"), modulesBody());

            JsonObject replacement = new JsonObject();
            JsonObject modules = new JsonObject();
            modules.add("console", new JsonObject());
            replacement.add("modules", modules);
            HttpResponse<String> second = client.post(importPath("survival"), replacement.toString());

            assertEquals(200, second.statusCode(), "not a conflict — a rejected import would make "
                    + "every plugin ask first, and the answer never changes");
            JsonObject data = SignedClient.data(second);
            assertFalse(data.get("imported").getAsBoolean());
            assertTrue(data.getAsJsonObject("modules").has("whitelist"),
                    "the stored document wins, so a plugin migrating a v2 config file cannot "
                            + "clobber what an operator has since edited in the dashboard");
            assertFalse(data.getAsJsonObject("modules").has("console"));
        }

        @Test
        @DisplayName("a server owned by another token is 403")
        void foreignServerIsForbidden() throws Exception {
            boot();
            bot.http().setServerOwnedByAnotherToken("someone-elses");

            HttpResponse<String> response = client.post(importPath("someone-elses"), modulesBody());

            assertEquals(403, response.statusCode());
            JsonObject error = SignedClient.envelope(response).getAsJsonObject("error");
            assertEquals("FORBIDDEN", error.get("code").getAsString());
            assertEquals("This API key does not own that server.", error.get("message").getAsString());
        }

        @Test
        @DisplayName("an unregistered serverId is allowed through, deliberately")
        void unregisteredServerIsAllowed() throws Exception {
            boot(StubBotConfig.withDemoFixtures().unregisterServer("not-yet-registered"));

            HttpResponse<String> response =
                    client.post(importPath("not-yet-registered"), modulesBody());

            assertEquals(200, response.statusCode(),
                    "there is no 404 here: the document is inert until a registry row points at it, "
                            + "so refusing would only force a plugin to claim before it can import");
        }

        @Test
        @DisplayName("an unsigned import is rejected — this route is not public")
        void importRequiresASignature() throws Exception {
            boot();

            HttpResponse<String> response =
                    client.postUnsigned(importPath("survival"), modulesBody());

            assertEquals(401, response.statusCode());
        }
    }
}
