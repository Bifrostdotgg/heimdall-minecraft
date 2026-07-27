package com.heimdall.core.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Golden vectors for request signing — <strong>the same constants {@code stub-bot}'s {@code
 * HmacTest} pins</strong>.
 *
 * <p>That duplication is the point. The two sides of this contract are written independently (one
 * signs, one verifies), so if they hold each other to the same bytes, a drift in either is a
 * failure here rather than a connection refused on a customer's server with nothing in the log.
 *
 * <p>Provenance, from the stub's own test: none of these values came from the code under test. Each
 * was computed twice, in PowerShell against {@code System.Security.Cryptography} and in Node's
 * {@code crypto}, and the bot's {@code packages/shared/hmac.ts} was shown to accept a signature
 * built over this canonical form and to reject one whose path had a query string appended. So a
 * failure here means the Java side drifted from the wire format, not that a constant needs
 * regenerating.
 */
class HmacSignerTest {

    private static final String SECRET = "test-secret-key";
    private static final String TIMESTAMP = "1700000000";
    private static final String GUILD = "123456789012345678";

    @Nested
    @DisplayName("HTTP signs the path INCLUDING the query string")
    class HttpVectors {

        @Test
        void postWithBody() {
            String path = "/api/guilds/" + GUILD + "/minecraft/connection-attempt";
            String body = "{\"username\":\"steve\",\"uuid\":\"11111111-2222-3333-4444-555555555555\"}";

            assertEquals(
                    "e735f37c6caee5b2ca8809795c114e735f978f135aff66659e9695d782d3f891",
                    HmacSigner.sha256Hex(body),
                    "body hash");
            assertEquals(
                    "9a5b013496d6e835a067272b2d7e7856f0e071c415dd3086ae5725ec117b7654",
                    HmacSigner.sign(SECRET, TIMESTAMP, "POST", path, body));
        }

        @Test
        void getWithQueryString() {
            String path = "/api/guilds/" + GUILD + "/minecraft/whitelist/sync?since=42";
            assertEquals(
                    "cbaf8b0e3665a386221189e597f54cbd0fdf90779d159c0657849b52d7f752b6",
                    HmacSigner.sign(SECRET, TIMESTAMP, "GET", path, ""));
        }

        @Test
        void getWithoutQueryString() {
            String path = "/api/guilds/" + GUILD + "/minecraft/offense-types";
            assertEquals(
                    "c34e64c928c404380b8511078abc15167db357ccf2d441ec41d98247e204e915",
                    HmacSigner.sign(SECRET, TIMESTAMP, "GET", path, ""));
        }

        @Test
        @DisplayName("the instance method produces the same bytes as the static vector")
        void instanceMethodMatchesTheVector() {
            String path = "/api/guilds/" + GUILD + "/minecraft/offense-types";
            HmacSigner signer = new HmacSigner(SECRET, () -> 1_700_000_000_000L);

            Signature signature = signer.forHttp("GET", path, "");

            assertEquals(TIMESTAMP, signature.timestamp(), "the clock is seconds, not millis");
            assertEquals(
                    "c34e64c928c404380b8511078abc15167db357ccf2d441ec41d98247e204e915",
                    signature.signature());
        }

        @Test
        @DisplayName("a lower-case method still signs as upper-case")
        void methodIsUpperCased() {
            assertEquals(
                    HmacSigner.sign(SECRET, TIMESTAMP, "POST", "/x", ""),
                    HmacSigner.sign(SECRET, TIMESTAMP, "post", "/x", ""));
        }
    }

    @Nested
    @DisplayName("the WebSocket upgrade signs the path EXCLUDING the query string")
    class WebSocketVectors {

        @Test
        void pathOnly() {
            assertEquals(
                    "40738ba535de6dc0fc828033c57f2672eeedbd0a5e08adc4535729756605c71d",
                    HmacSigner.sign(SECRET, TIMESTAMP, "GET", "/ws/minecraft/" + GUILD, ""));
        }

        @Test
        void instanceMethodMatchesTheVector() {
            HmacSigner signer = new HmacSigner(SECRET, () -> 1_700_000_000_000L);

            Signature signature = signer.forWsHandshake("/ws/minecraft/" + GUILD);

            assertEquals(
                    "40738ba535de6dc0fc828033c57f2672eeedbd0a5e08adc4535729756605c71d",
                    signature.signature());
            assertEquals(TIMESTAMP, signature.timestamp());
        }

        @Test
        @DisplayName("signing the query string too produces a different, wrong signature")
        void includingQueryIsWrong() {
            String pathOnly = "/ws/minecraft/" + GUILD;
            HmacSigner signer = new HmacSigner(SECRET, () -> 1_700_000_000_000L);

            assertNotEquals(
                    signer.forWsHandshake(pathOnly).signature(),
                    signer.forHttp("GET", pathOnly + "?serverId=survival", "").signature(),
                    "if these ever matched, the asymmetry between the HTTP and WS rules would be "
                            + "untestable and a client could get it wrong undetected");
        }
    }

    @Test
    @DisplayName("a bodyless request signs over the SHA-256 of the empty string")
    void emptyBodyHash() {
        assertEquals(HmacSigner.EMPTY_BODY_SHA256, HmacSigner.sha256Hex(""));
    }

    @Test
    @DisplayName("the canonical string is timestamp, METHOD, path, body hash — newline separated")
    void canonicalLayout() {
        assertEquals(
                TIMESTAMP + "\nPOST\n/x\n" + HmacSigner.EMPTY_BODY_SHA256,
                HmacSigner.canonical(TIMESTAMP, "post", "/x", ""),
                "the method is upper-cased, and the body is hashed even when empty");
    }

    @Test
    void aNullBodyIsTheSameAsAnEmptyOne() {
        assertEquals(
                HmacSigner.sign(SECRET, TIMESTAMP, "POST", "/x", ""),
                HmacSigner.sign(SECRET, TIMESTAMP, "POST", "/x", null));
    }

    @Test
    void differentSecretsProduceDifferentSignatures() {
        assertNotEquals(
                HmacSigner.sign(SECRET, TIMESTAMP, "GET", "/x", ""),
                HmacSigner.sign("other-secret", TIMESTAMP, "GET", "/x", ""));
    }

    @Test
    void aNullSecretIsRejectedAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new HmacSigner(null));
    }
}
