package com.heimdall.core.tunnel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.http.HmacSigner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The upgrade URL, and the signing asymmetry that is invisible until a connection is refused. */
class TunnelUrlsTest {

    private static final String GUILD = "123456789012345678";
    private static final String SECRET = "test-secret-key";

    private static TunnelSettings settings(String endpoint, String serverId) {
        return TunnelSettings.builder()
                .endpoint(endpoint)
                .guildId(GUILD)
                .serverId(serverId)
                .apiKey(SECRET)
                .build();
    }

    @Test
    @DisplayName("http becomes ws and https becomes wss, keeping the port")
    void schemeIsRewrittenAndPortPreserved() {
        HmacSigner signer = new HmacSigner(SECRET, () -> 1_700_000_000_000L);

        assertTrue(TunnelUrls.upgradeUrl(settings("http://localhost:3001", "survival"), signer)
                .startsWith("ws://localhost:3001/ws/minecraft/" + GUILD + "?"));
        assertTrue(TunnelUrls.upgradeUrl(settings("https://api.bifrost.gg", "survival"), signer)
                .startsWith("wss://api.bifrost.gg/ws/minecraft/" + GUILD + "?"));
    }

    @Test
    @DisplayName("the upgrade signature covers the path WITHOUT the query it travels in")
    void signatureCoversThePathWithoutTheQuery() {
        HmacSigner signer = new HmacSigner(SECRET, () -> 1_700_000_000_000L);

        String url = TunnelUrls.upgradeUrl(settings("http://localhost:3001", "survival"), signer);

        // The golden vector from stub-bot's README, computed independently in PowerShell and Node.
        // Signing the path WITH the query instead produces a perfectly well-formed signature that
        // the bot rejects, and there is nothing in either log to say why.
        assertTrue(url.contains(
                "signature=40738ba535de6dc0fc828033c57f2672eeedbd0a5e08adc4535729756605c71d"),
                "expected the query-less path signature, got: " + url);
        assertTrue(url.contains("timestamp=1700000000"));
    }

    @Test
    @DisplayName("a server id with characters that need escaping is url-encoded")
    void serverIdIsEncoded() {
        HmacSigner signer = new HmacSigner(SECRET, () -> 1_700_000_000_000L);

        String url = TunnelUrls.upgradeUrl(settings("http://localhost:3001", "sky block&test"), signer);

        assertTrue(url.contains("serverId=sky+block%26test"),
                "an unescaped ampersand would silently split into a second query parameter: " + url);
    }

    @Test
    @DisplayName("an absent server id defaults to 'default', as the bot's route does")
    void absentServerIdDefaults() {
        HmacSigner signer = new HmacSigner(SECRET, () -> 1_700_000_000_000L);

        assertTrue(TunnelUrls.upgradeUrl(settings("http://localhost:3001", "  "), signer)
                .contains("serverId=default"));
    }

    @Test
    @DisplayName("log lines carry the path but never the signature")
    void sanitizeStripsTheQueryString() {
        String sanitized = TunnelUrls.sanitize(
                "ws://localhost:3001/ws/minecraft/" + GUILD + "?serverId=a&signature=deadbeef&timestamp=1");

        assertEquals("ws://localhost:3001/ws/minecraft/" + GUILD + "?…", sanitized);
        assertFalse(sanitized.contains("deadbeef"),
                "a valid signature in a log file is a credential in a log file, for as long as the "
                        + "timestamp stays inside the bot's replay window");
    }

    @Test
    void pathIsTheRouteTheBotMatches() {
        assertEquals("/ws/minecraft/" + GUILD, TunnelUrls.path(GUILD));
    }
}
