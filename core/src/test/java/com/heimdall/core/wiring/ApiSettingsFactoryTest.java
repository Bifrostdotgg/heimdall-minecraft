package com.heimdall.core.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.config.BootstrapConfig;
import com.heimdall.core.http.ApiSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The bootstrap-to-settings adapter, now that it lives outside both packages it joins. */
class ApiSettingsFactoryTest {

    private static BootstrapConfig bootstrap() {
        return BootstrapConfig.builder()
                .endpoint("https://api.bifrost.gg/")
                .tokenId("tok")
                .token("s3cr3t")
                .serverId("survival")
                .build();
    }

    @Test
    void carriesEndpointKeyAndServerId() {
        ApiSettings settings = ApiSettingsFactory.fromBootstrap(bootstrap(), "123456789012345678").build();

        assertEquals("https://api.bifrost.gg", settings.baseUrl());
        assertEquals("s3cr3t", settings.apiKey());
        assertEquals("survival", settings.serverId());
        assertEquals("123456789012345678", settings.guildId());
        assertTrue(settings.isUsable());
    }

    @Test
    @DisplayName("a claimed server (default bootstrap timing) gets v3's defaults")
    void defaultTimingMatchesV3Defaults() {
        ApiSettings settings = ApiSettingsFactory.fromBootstrap(bootstrap(), "1").build();

        assertEquals(ApiSettings.DEFAULT_TIMEOUT_MS, settings.timeoutMs());
        assertEquals(ApiSettings.DEFAULT_RETRIES, settings.retries());
        assertEquals(ApiSettings.DEFAULT_RETRY_DELAY_MS, settings.retryDelayMs());
    }

    @Test
    @DisplayName("a migrated v2 server's own login budget flows through, not v3's defaults (B4/D62)")
    void bootstrapTimingFlowsThrough() {
        // A v2 server tuned tight: 1500ms, one attempt. This must reach ApiSettings, or the login
        // budget balloons to v3's ~18s worst case on upgrade.
        BootstrapConfig migrated = bootstrap().toBuilder()
                .timeoutMs(1500)
                .retries(1)
                .retryDelayMs(500)
                .build();

        ApiSettings settings = ApiSettingsFactory.fromBootstrap(migrated, "1").build();

        assertEquals(1500, settings.timeoutMs());
        assertEquals(1, settings.retries());
        assertEquals(500, settings.retryDelayMs());
    }

    @Test
    @DisplayName("a builder comes back, so a caller can still override the timing")
    void returnsAWriter() {
        ApiSettings settings = ApiSettingsFactory.fromBootstrap(bootstrap(), "1")
                .timeoutMs(1234)
                .build();

        assertEquals(1234, settings.timeoutMs());
    }

    @Test
    @DisplayName("an unconfigured bootstrap yields settings that know they are unusable")
    void unconfiguredBootstrapIsNotUsable() {
        ApiSettings settings = ApiSettingsFactory.fromBootstrap(BootstrapConfig.defaults(), "").build();

        assertFalse(settings.isUsable());
    }

    @Test
    void nullBootstrapIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> ApiSettingsFactory.fromBootstrap(null, "1"));
    }
}
