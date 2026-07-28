package com.heimdall.core.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.http.model.ConnectionAttempt;
import com.heimdall.core.log.RecordingLogger;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The gateway's three states, and the property that made it worth building.
 *
 * <p>Departure D56: a module used to capture an {@code ApiClient} that was {@code null} on a server
 * nobody had set up, once, at registration — so {@code /hd setup} could configure a server, connect
 * a tunnel, and leave {@code /offend} refusing forever. The fix is not a smarter null check; it is
 * that there is exactly one gateway object, it exists before any module is registered, and what
 * moves underneath it is the transport's <em>settings</em>.
 *
 * <p>So the interesting assertion in this file is the last one: the same instance a caller took at
 * the start answers differently after the client is reconfigured, with nobody re-handing it
 * anything.
 */
class HeimdallApiTest {

    private final RecordingLogger logger = new RecordingLogger();

    /**
     * An executor that runs inline.
     *
     * <p>Nothing here should ever reach it — every call under test is refused before a request is
     * built — so running inline is also an assertion: a test that started making real requests would
     * hang against an address nothing is listening on rather than passing quietly.
     */
    private static final Executor INLINE = new Executor() {
        @Override
        public void execute(Runnable command) {
            command.run();
        }
    };

    private ApiClient client(ApiSettings settings) {
        return new ApiClient(logger, settings, INLINE);
    }

    private static ApiSettings.Builder complete() {
        return ApiSettings.builder()
                .baseUrl("https://api.example.test")
                .guildId("123456789012345678")
                .apiKey("secret")
                .serverId("survival");
    }

    private static ConnectionAttempt anyAttempt() {
        return ConnectionAttempt
                .builder("Steve", "11111111-2222-3333-4444-555555555555")
                .build();
    }

    @Nested
    @DisplayName("availability")
    class Availability {

        @Test
        @DisplayName("no endpoint or no token is 'not set up'")
        void notConfigured() {
            assertEquals(HeimdallApi.Availability.NOT_CONFIGURED,
                    new HeimdallApi(client(ApiSettings.builder().build())).availability(),
                    "a fresh install has neither");
            assertEquals(HeimdallApi.Availability.NOT_CONFIGURED,
                    new HeimdallApi(client(complete().apiKey("").build())).availability(),
                    "an endpoint with no credentials cannot sign anything");
            assertEquals(HeimdallApi.Availability.NOT_CONFIGURED,
                    new HeimdallApi(client(complete().baseUrl("").build())).availability(),
                    "credentials with nowhere to send them are equally useless");
        }

        @Test
        @DisplayName("credentials but no guild is 'discovering', which is not the same thing")
        void discovering() {
            HeimdallApi api = new HeimdallApi(client(complete().guildId("").build()));

            assertEquals(HeimdallApi.Availability.DISCOVERING, api.availability(),
                    "every configured server passes through this on the way up (D54), and it looks "
                            + "like a network problem while being nothing of the sort");
            assertFalse(api.isUsable());
        }

        @Test
        @DisplayName("endpoint, token and guild is ready")
        void ready() {
            HeimdallApi api = new HeimdallApi(client(complete().build()));

            assertEquals(HeimdallApi.Availability.READY, api.availability());
            assertTrue(api.isUsable());
            assertTrue(api.describe().contains("123456789012345678"),
                    "a status line has to name the guild, or 'ready' says nothing checkable");
        }
    }

    @Nested
    @DisplayName("an unusable gateway")
    class Refusals {

        @Test
        @DisplayName("fails the future immediately, naming which state it was in")
        void failsFastWithAReason() throws Exception {
            HeimdallApi notSetUp = new HeimdallApi(client(ApiSettings.builder().build()));

            ExecutionException raised = assertThrows(ExecutionException.class,
                    () -> notSetUp.connectionAttempt(anyAttempt()).get(1, TimeUnit.SECONDS));

            ApiUnavailableException refusal =
                    assertInstanceOf(ApiUnavailableException.class, raised.getCause());
            assertEquals(HeimdallApi.Availability.NOT_CONFIGURED, refusal.reason());
            assertTrue(refusal.getMessage().contains("/hd setup"),
                    "the message is what an operator reads; it has to name the fix");
        }

        @Test
        @DisplayName("says 'discovering' rather than 'not set up' when a guild is all that is missing")
        void discoveringIsItsOwnRefusal() {
            HeimdallApi discovering = new HeimdallApi(client(complete().guildId("").build()));

            ExecutionException raised = assertThrows(ExecutionException.class,
                    () -> discovering.whitelistSync(null).get(1, TimeUnit.SECONDS));

            ApiUnavailableException refusal =
                    assertInstanceOf(ApiUnavailableException.class, raised.getCause());
            assertEquals(HeimdallApi.Availability.DISCOVERING, refusal.reason(),
                    "telling an operator to run setup on a server that IS set up sends them to "
                            + "re-claim a perfectly good token");
        }

        @Test
        @DisplayName("refuses every endpoint, not just the one somebody remembered")
        void everyEndpointIsGated() {
            HeimdallApi api = new HeimdallApi(client(ApiSettings.builder().build()));

            assertThrows(ExecutionException.class,
                    () -> api.connectionAttempt(anyAttempt()).get(1, TimeUnit.SECONDS));
            assertThrows(ExecutionException.class,
                    () -> api.requestLinkCode("Steve", "11111111-2222-3333-4444-555555555555")
                            .get(1, TimeUnit.SECONDS));
            assertThrows(ExecutionException.class, () -> api.offenseTypes().get(1, TimeUnit.SECONDS));
            assertThrows(ExecutionException.class, () -> api.whitelistSync(null).get(1, TimeUnit.SECONDS));
            assertThrows(ExecutionException.class, () -> api.latestRelease().get(1, TimeUnit.SECONDS));
            assertThrows(ExecutionException.class,
                    () -> api.importConfig("survival", null).get(1, TimeUnit.SECONDS));
        }

        @Test
        @DisplayName("settings are readable even while nothing can be sent")
        void settingsStillAnswer() {
            HeimdallApi api = new HeimdallApi(client(complete().guildId("").build()));

            // A caller bounding a blocking wait needs the budget whether or not the request will go
            // out. If this threw, every module would have to branch before reading a timeout.
            assertTrue(api.settings().whitelistSyncJoinTimeoutMs() > 0);
        }
    }

    @Test
    @DisplayName("the same instance becomes usable when the client is reconfigured underneath it")
    void survivesBeingSetUpUnderneath() {
        ApiClient client = client(ApiSettings.builder().build());
        // Captured exactly as a module captures it: once, before anything is configured.
        HeimdallApi captured = new HeimdallApi(client);

        assertEquals(HeimdallApi.Availability.NOT_CONFIGURED, captured.availability());

        // What /hd setup does, and then what guild discovery does a moment later.
        client.reconfigure(complete().guildId("").build());
        assertEquals(HeimdallApi.Availability.DISCOVERING, captured.availability());

        client.reconfigure(complete().build());
        assertTrue(captured.isUsable(),
                "this is the whole of departure D56: nothing re-handed the caller a new object, and "
                        + "the one it is holding now works");
    }
}
