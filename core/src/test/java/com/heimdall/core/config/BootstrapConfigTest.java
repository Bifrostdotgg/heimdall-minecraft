package com.heimdall.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The immutable snapshot: defaults, normalisation, and the one thing it must never print. */
class BootstrapConfigTest {

    private static BootstrapConfig configured() {
        return BootstrapConfig.builder()
                .endpoint("https://api.bifrost.gg")
                .tokenId("tok_123")
                .token("s3cr3t")
                .serverId("survival")
                .role(ServerRole.GATEKEEPER)
                .debug(true)
                .build();
    }

    @Test
    void defaultsAreNotConfigured() {
        BootstrapConfig config = BootstrapConfig.defaults();

        assertEquals("", config.endpoint());
        assertEquals("", config.tokenId());
        assertEquals("", config.token());
        assertEquals("", config.serverId());
        assertEquals(ServerRole.AUTO, config.role());
        assertFalse(config.debug());
        assertFalse(config.isConfigured());
    }

    @Test
    void aFullConfigIsConfigured() {
        assertTrue(configured().isConfigured());
    }

    @Test
    @DisplayName("an endpoint without a token, or a token without an endpoint, is not enough")
    void partialConfigIsNotConfigured() {
        assertFalse(BootstrapConfig.builder().tokenId("t").token("s").build().isConfigured());
        assertFalse(BootstrapConfig.builder().endpoint("https://x").tokenId("t").build().isConfigured());
    }

    @Test
    @DisplayName("a token with no token id IS configured — that is what a v2 migration produces")
    void legacyCredentialsAreConfigured() {
        assertTrue(BootstrapConfig.builder().endpoint("https://x").token("s").build().isConfigured(),
                "v2 had one guild key and no id for it, and the signature is what authenticates. "
                        + "Requiring the id here made every migrated server permanently 'not set "
                        + "up': its HTTP client worked, its guild resolved, and its tunnel never "
                        + "dialled.");
    }

    @Test
    @DisplayName("the endpoint loses its trailing slash so callers can just concatenate")
    void endpointIsNormalised() {
        assertEquals("https://api.bifrost.gg",
                BootstrapConfig.builder().endpoint("https://api.bifrost.gg/").build().endpoint());
        assertEquals("https://api.bifrost.gg",
                BootstrapConfig.builder().endpoint("  https://api.bifrost.gg///  ").build().endpoint());
    }

    @Test
    void nullRoleMeansAuto() {
        assertEquals(ServerRole.AUTO, BootstrapConfig.builder().role(null).build().role());
    }

    @Test
    @DisplayName("toString never prints the token")
    void toStringRedactsTheToken() {
        String rendered = configured().toString();

        assertFalse(rendered.contains("s3cr3t"), "the token is a bearer credential: " + rendered);
        assertTrue(rendered.contains("<redacted>"));
        assertTrue(rendered.contains("tok_123"), "the token id is not secret and is useful in logs");
        assertTrue(rendered.contains("gatekeeper"));

        assertTrue(BootstrapConfig.defaults().toString().contains("<unset>"),
                "an absent token should read differently from a present one");
    }

    @Test
    void builderRoundTripsEveryField() {
        BootstrapConfig original = configured();
        assertEquals(original, original.toBuilder().build());
        assertEquals(original.hashCode(), original.toBuilder().build().hashCode());
        assertNotEquals(original, original.toBuilder().serverId("creative").build());
    }

    @Test
    void roleParsingToleratesConfigSpellings() {
        assertEquals(ServerRole.GATEKEEPER, ServerRole.parse("gatekeeper", ServerRole.AUTO));
        assertEquals(ServerRole.GATEKEEPER, ServerRole.parse("  GateKeeper ", ServerRole.AUTO));
        assertEquals(ServerRole.STANDALONE, ServerRole.parse("stand-alone", ServerRole.AUTO));
        assertEquals(ServerRole.AUTO, ServerRole.parse("nonsense", ServerRole.AUTO));
        assertEquals(ServerRole.AUTO, ServerRole.parse(null, ServerRole.AUTO));
        assertEquals("enforcer", ServerRole.ENFORCER.wireName());
    }
}
