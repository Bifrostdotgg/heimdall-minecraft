package com.heimdall.stubbot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Golden-vector tests for the request signing.
 *
 * <p><strong>Provenance of the expected values.</strong> None of them were produced by the code
 * under test — that would only prove the implementation agrees with itself. Each was computed twice,
 * independently: once in PowerShell against {@code System.Security.Cryptography}, and once in Node's
 * {@code crypto}. Both agree, and the bot's own {@code verifyRequest} from
 * {@code packages/shared/hmac.ts} was additionally shown to accept a signature built over this
 * canonical form and to reject one whose path had a query string appended.
 *
 * <p>So a failure here means the Java side drifted from the wire format, not that a constant needs
 * regenerating.
 */
class HmacTest {

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
                    Hmac.sha256Hex(body),
                    "body hash");
            assertEquals(
                    "9a5b013496d6e835a067272b2d7e7856f0e071c415dd3086ae5725ec117b7654",
                    Hmac.sign(SECRET, TIMESTAMP, "POST", path, body));
        }

        @Test
        void getWithQueryString() {
            String path = "/api/guilds/" + GUILD + "/minecraft/whitelist/sync?since=42";
            assertEquals(
                    "cbaf8b0e3665a386221189e597f54cbd0fdf90779d159c0657849b52d7f752b6",
                    Hmac.sign(SECRET, TIMESTAMP, "GET", path, ""));
        }

        @Test
        void getWithoutQueryString() {
            String path = "/api/guilds/" + GUILD + "/minecraft/offense-types";
            assertEquals(
                    "c34e64c928c404380b8511078abc15167db357ccf2d441ec41d98247e204e915",
                    Hmac.sign(SECRET, TIMESTAMP, "GET", path, ""));
        }
    }

    @Nested
    @DisplayName("the WebSocket upgrade signs the path EXCLUDING the query string")
    class WebSocketVectors {

        @Test
        void pathOnly() {
            assertEquals(
                    "40738ba535de6dc0fc828033c57f2672eeedbd0a5e08adc4535729756605c71d",
                    Hmac.sign(SECRET, TIMESTAMP, "GET", "/ws/minecraft/" + GUILD, ""));
        }

        @Test
        @DisplayName("signing the query string too produces a different, wrong signature")
        void includingQueryIsWrong() {
            String pathOnly = "/ws/minecraft/" + GUILD;
            String withQuery = pathOnly + "?serverId=survival";
            assertNotEquals(
                    Hmac.sign(SECRET, TIMESTAMP, "GET", pathOnly, ""),
                    Hmac.sign(SECRET, TIMESTAMP, "GET", withQuery, ""),
                    "if these ever matched, the asymmetry between the HTTP and WS rules would be "
                            + "untestable and a client could get it wrong undetected");
        }
    }

    @Test
    @DisplayName("a bodyless request signs over the SHA-256 of the empty string")
    void emptyBodyHash() {
        assertEquals(Hmac.EMPTY_BODY_SHA256, Hmac.sha256Hex(""));
    }

    @Test
    @DisplayName("the canonical string is timestamp, METHOD, path, body hash — newline separated")
    void canonicalLayout() {
        assertEquals(
                TIMESTAMP + "\nPOST\n/x\n" + Hmac.EMPTY_BODY_SHA256,
                Hmac.canonical(TIMESTAMP, "post", "/x", ""),
                "the method is upper-cased, and the body is hashed even when empty");
    }

    @Nested
    @DisplayName("verification")
    class Verification {

        private static final long NOW = 1_700_000_000_000L;

        @Test
        void acceptsItsOwnSignature() {
            String signature = Hmac.sign(SECRET, TIMESTAMP, "GET", "/x", "");
            assertTrue(Hmac.verify(SECRET, "GET", "/x", "", signature, TIMESTAMP, NOW));
        }

        @Test
        void rejectsAWrongSecret() {
            String signature = Hmac.sign("other-secret", TIMESTAMP, "GET", "/x", "");
            assertFalse(Hmac.verify(SECRET, "GET", "/x", "", signature, TIMESTAMP, NOW));
        }

        @Test
        void rejectsATamperedPath() {
            String signature = Hmac.sign(SECRET, TIMESTAMP, "GET", "/x", "");
            assertFalse(Hmac.verify(SECRET, "GET", "/y", "", signature, TIMESTAMP, NOW));
        }

        @Test
        void rejectsATamperedBody() {
            String signature = Hmac.sign(SECRET, TIMESTAMP, "POST", "/x", "{\"a\":1}");
            assertFalse(Hmac.verify(SECRET, "POST", "/x", "{\"a\":2}", signature, TIMESTAMP, NOW));
        }

        @Test
        @DisplayName("the replay window is ±5 minutes, in both directions")
        void replayWindow() {
            String signature = Hmac.sign(SECRET, TIMESTAMP, "GET", "/x", "");
            long signedAt = 1_700_000_000_000L;

            assertTrue(Hmac.verify(SECRET, "GET", "/x", "", signature, TIMESTAMP,
                    signedAt + Hmac.MAX_AGE_MS - 1000), "4m59s old is still fresh");
            assertFalse(Hmac.verify(SECRET, "GET", "/x", "", signature, TIMESTAMP,
                    signedAt + Hmac.MAX_AGE_MS + 1000), "5m01s old is a replay");
            assertFalse(Hmac.verify(SECRET, "GET", "/x", "", signature, TIMESTAMP,
                    signedAt - Hmac.MAX_AGE_MS - 1000), "a timestamp from the future is rejected too");
        }

        @Test
        void rejectsMalformedInputsWithoutThrowing() {
            String signature = Hmac.sign(SECRET, TIMESTAMP, "GET", "/x", "");
            assertFalse(Hmac.verify(SECRET, "GET", "/x", "", signature, "not-a-number", NOW));
            assertFalse(Hmac.verify(SECRET, "GET", "/x", "", signature, null, NOW));
            assertFalse(Hmac.verify(SECRET, "GET", "/x", "", null, TIMESTAMP, NOW));
            assertFalse(Hmac.verify(SECRET, "GET", "/x", "", "zzzz", TIMESTAMP, NOW), "not valid hex");
            assertFalse(Hmac.verify(SECRET, "GET", "/x", "", "abc", TIMESTAMP, NOW), "odd hex length");
            assertFalse(Hmac.verify(SECRET, "GET", "/x", "", "ab", TIMESTAMP, NOW), "right hex, wrong length");
        }

        @Test
        @DisplayName("the timestamp parse is plain decimal only — deliberately stricter")
        void timestampParsingIsStrictDecimal() {
            // Every one of these would be accepted by a looser parse, and none is a timestamp any
            // real client sends. Java's Double.parseDouble takes "5d" and hex float literals;
            // JavaScript's Number() takes hex, a leading +, exponents and surrounding whitespace.
            // The divergence is one-directional and fail-closed: the stub can refuse what the bot
            // would accept, never accept what the bot would refuse.
            for (String bad : new String[] {
                    TIMESTAMP + "d", TIMESTAMP + "f", "0x65a0bc00", "+" + TIMESTAMP,
                    "1.7e9", " " + TIMESTAMP, TIMESTAMP + " ", "", "Infinity", "NaN"}) {
                String signature = Hmac.sign(SECRET, bad, "GET", "/x", "");
                assertFalse(Hmac.verify(SECRET, "GET", "/x", "", signature, bad, NOW),
                        "should have rejected timestamp '" + bad + "'");
            }

            // Plain decimal, with or without a fraction, still verifies.
            assertTrue(Hmac.verify(SECRET, "GET", "/x", "",
                    Hmac.sign(SECRET, TIMESTAMP, "GET", "/x", ""), TIMESTAMP, NOW));
            String fractional = TIMESTAMP + ".5";
            assertTrue(Hmac.verify(SECRET, "GET", "/x", "",
                    Hmac.sign(SECRET, fractional, "GET", "/x", ""), fractional, NOW));
        }
    }
}
