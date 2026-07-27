package com.heimdall.stubbot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The two upgrade refusals that are real HTTP responses rather than dropped sockets.
 *
 * <p>Most refusals — a bad path, a missing or invalid signature — are a silent
 * {@code socket.destroy()}, unchanged from v2 so a v2 plugin sees exactly what it always did. These
 * two are different because a plugin has to be able to <strong>tell them apart from each other</strong>:
 *
 * <ul>
 *   <li><strong>403</strong> is permanent. The serverId is registered to a different token, and
 *       retrying gets the same answer until somebody changes the configuration. A plugin that
 *       reconnect-loops on it hammers an endpoint that will never say yes.
 *   <li><strong>503</strong> is transient. The registry could not be read and a live connection for
 *       that id is held by a different token, so the bot refuses to guess rather than let two
 *       servers share an id. Back off and retry.
 * </ul>
 *
 * <p>Asserted at the socket level, because the status line is the entire signal — a WebSocket client
 * library will usually reduce both to "handshake failed".
 */
class UpgradeRejectionTest {

    private static final String GUILD = StubBotConfig.DEFAULT_GUILD_ID;
    private static final String KEY = StubBotConfig.DEFAULT_API_KEY;

    private StubBot bot;

    @AfterEach
    void tearDown() {
        if (bot != null) {
            bot.close();
        }
    }

    private void boot(StubBotConfig config) {
        bot = StubBot.start(config.bindHost("127.0.0.1").port(0));
    }

    /** Opens a raw upgrade request and returns the first line of the response. */
    private String upgradeStatusLine(String serverId) throws Exception {
        String path = "/ws/minecraft/" + GUILD + "?serverId=" + serverId
                + "&signature=x&timestamp=" + System.currentTimeMillis();
        String request = "GET " + path + " HTTP/1.1\r\n"
                + "Host: 127.0.0.1\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
                + "Sec-WebSocket-Version: 13\r\n\r\n";
        try (Socket socket = new Socket("127.0.0.1", bot.port())) {
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
            return in.readLine();
        }
    }

    @Test
    @DisplayName("a serverId registered to another token is refused with 403")
    void foreignServerIdIs403() throws Exception {
        boot(StubBotConfig.withDemoFixtures().registerServerToAnotherToken("survival"));

        String status = upgradeStatusLine("survival");

        assertNotNull(status, "the refusal must be an HTTP response, not a dropped socket");
        assertTrue(status.contains("403"), "expected a 403 status line, got: " + status);
        assertTrue(status.contains("Forbidden"), status);
    }

    @Test
    @DisplayName("an unreadable registry with an incumbent under another token is 503")
    void registryOutageWithIncumbentIs503() throws Exception {
        boot(StubBotConfig.withDemoFixtures());

        // An incumbent connection, established while the registry was readable.
        try (TestWsClient incumbent = TestWsClient.connect(bot, GUILD, "survival", KEY)) {
            incumbent.identifyV3("survival", "Survival", 3, List.of("whitelist@1"));
            assertNotNull(bot.ws().awaitIdentify(GUILD, "survival", 3000));

            bot.config().registryUnreadable(true);
            String status = upgradeStatusLine("survival");

            assertNotNull(status);
            assertTrue(status.contains("503"), "expected a 503 status line, got: " + status);
            assertTrue(status.contains("Service Unavailable"), status);
        }
    }

    @Test
    @DisplayName("an unreadable registry with no incumbent is allowed through")
    void registryOutageWithoutIncumbentPasses() throws Exception {
        boot(StubBotConfig.withDemoFixtures().registryUnreadable(true));

        String status = upgradeStatusLine("survival");

        // Whatever the WebSocket library answers, it must not be one of the registry refusals: the
        // bot proceeds and treats the connection as unregistered rather than refusing on a guess.
        assertNotNull(status);
        assertEquals(false, status.contains("503"),
                "with nobody else holding the id there is nothing to protect: " + status);
        assertEquals(false, status.contains("403"), status);
    }

    @Test
    @DisplayName("an ordinary serverId is not refused")
    void ordinaryUpgradeIsNotRefused() throws Exception {
        boot(StubBotConfig.withDemoFixtures());

        String status = upgradeStatusLine("survival");

        assertNotNull(status);
        assertEquals(false, status.contains("403"), status);
        assertEquals(false, status.contains("503"), status);
    }

    @Test
    @DisplayName("a same-token reconnect always passes — the case that actually happens")
    void sameTokenReconnectPasses() throws Exception {
        boot(StubBotConfig.withDemoFixtures());

        try (TestWsClient first = TestWsClient.connect(bot, GUILD, "survival", KEY)) {
            first.identifyV3("survival", "Survival", 3, List.of("whitelist@1"));
            assertNotNull(bot.ws().awaitIdentify(GUILD, "survival", 3000));

            // A server restarting reconnects under the same token while the old socket may still be
            // registered. Refusing that would break every restart, so it must pass by both routes.
            try (TestWsClient second = TestWsClient.connect(bot, GUILD, "survival", KEY)) {
                second.identifyV3("survival", "Survival", 3, List.of("whitelist@1"));
                assertNotNull(second.await("identify_ack", 3000),
                        "a reconnecting server must be able to identify again");
            }
        }
    }
}
