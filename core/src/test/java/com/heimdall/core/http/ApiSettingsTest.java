package com.heimdall.core.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.config.BootstrapConfig;
import com.heimdall.core.log.RecordingLogger;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The retry-aware request budget — v2's {@code ApiClientTimeoutTest} (issue #797 / MC-6 + MC-13),
 * ported to the v3 shape.
 *
 * <p>The budget has to cover the <em>whole</em> retry sequence, so a caller blocking on the future
 * does not abandon a request the retry loop is still working on, and it has to reflect a later
 * reconfiguration so a reload actually changes the bound.
 */
class ApiSettingsTest {

    /** Never used — these tests never dispatch anything. */
    private static final Executor UNUSED = new Executor() {
        @Override
        public void execute(Runnable command) {
            throw new AssertionError("no request should be dispatched by these tests");
        }
    };

    private static ApiSettings settings(int timeoutMs, int retries, int retryDelayMs) {
        return ApiSettings.builder()
                .baseUrl("http://localhost:3001")
                .guildId("123456789012345678")
                .apiKey("secret")
                .timeoutMs(timeoutMs)
                .retries(retries)
                .retryDelayMs(retryDelayMs)
                .build();
    }

    @Test
    void singleAttemptBudgetIsJustTheTimeout() {
        assertEquals(1500L, settings(1500, 1, 1000).overallTimeoutMs(), "retries=1 means no delays");
    }

    @Test
    void multiAttemptBudgetCoversEveryAttemptAndDelay() {
        // 3 attempts * 1500ms + 2 inter-attempt delays * 1000ms = 6500ms.
        // v2's caller waited `timeout + 1000` = 2500ms, truncating this by four seconds.
        assertEquals(6500L, settings(1500, 3, 1000).overallTimeoutMs());
    }

    @Test
    @DisplayName("a reconfigure changes the budget the client reports")
    void reconfigureChangesTheBudget() {
        ApiClient client = new ApiClient(new RecordingLogger(), settings(1500, 1, 1000), UNUSED);
        assertEquals(1500L, client.getOverallTimeoutMs());

        client.reconfigure(settings(2000, 2, 1000));

        assertEquals(5000L, client.getOverallTimeoutMs(), "2 * 2000 + 1 * 1000");
        assertEquals(2000, client.settings().timeoutMs());
    }

    @Test
    @DisplayName("nonsense timing values are clamped rather than honoured")
    void timingIsClamped() {
        ApiSettings clamped = settings(1, -5, -100);

        assertEquals(ApiSettings.MIN_TIMEOUT_MS, clamped.timeoutMs());
        assertEquals(1, clamped.retries(), "zero attempts would mean the request never happens");
        assertEquals(0, clamped.retryDelayMs());
    }

    @Test
    @DisplayName("the long-running endpoints get a floor, never a ceiling")
    void perEndpointTimeoutFloors() {
        ApiSettings fast = settings(1500, 1, 0);
        assertEquals(ApiSettings.WHITELIST_SYNC_TIMEOUT_MS, fast.whitelistSyncTimeoutMs());
        assertEquals(ApiSettings.UPDATE_CHECK_TIMEOUT_MS, fast.updateCheckTimeoutMs());

        ApiSettings patient = settings(30_000, 1, 0);
        assertEquals(30_000, patient.whitelistSyncTimeoutMs(),
                "a deliberately generous timeout must not be shortened by the floor");
        assertEquals(30_000, patient.updateCheckTimeoutMs());
    }

    @Test
    void usabilityNeedsUrlGuildAndKey() {
        assertTrue(settings(1500, 1, 0).isUsable());
        assertFalse(ApiSettings.builder().guildId("1").apiKey("k").build().isUsable());
        assertFalse(ApiSettings.builder().baseUrl("http://x").apiKey("k").build().isUsable());
        assertFalse(ApiSettings.builder().baseUrl("http://x").guildId("1").build().isUsable());
    }

    @Test
    void derivedFromBootstrapCarriesEndpointKeyAndServerId() {
        BootstrapConfig bootstrap = BootstrapConfig.builder()
                .endpoint("https://api.bifrost.gg/")
                .tokenId("tok")
                .token("s3cr3t")
                .serverId("survival")
                .build();

        ApiSettings derived = ApiSettings.from(bootstrap, "123456789012345678").build();

        assertEquals("https://api.bifrost.gg", derived.baseUrl());
        assertEquals("s3cr3t", derived.apiKey());
        assertEquals("survival", derived.serverId());
        assertEquals("123456789012345678", derived.guildId());
        assertTrue(derived.isUsable());
    }

    @Test
    @DisplayName("toString never prints the api key")
    void toStringRedactsTheKey() {
        String rendered = settings(1500, 1, 0).toString();
        assertFalse(rendered.contains("secret"), rendered);
        assertTrue(rendered.contains("<redacted>"));
    }
}
