package com.heimdall.core.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The endpoint validation that stands between an operator's typo and remote code execution (B2).
 *
 * <p>{@code /hd setup [endpoint]} writes whatever it is given to {@code bootstrap.yml}, and from then
 * on that URL is the bot: it chooses the token, answers the login gate, and dispatches the console
 * commands the offenses module runs. So a hostile or fat-fingered endpoint is RCE on the server, and
 * a plain {@code http://} one leaks the token. This class pins that a public {@code http://} host is
 * refused, that a private/loopback host over http is allowed (the harness needs
 * {@code http://stub-bot:8080}), and that anything past a bare base URL is rejected — the same shape
 * of guard {@code DownloadPolicy} already applies to a jar.
 */
class BotEndpointTest {

    private static void assertRejected(String raw, String because) {
        BotEndpoint.Result result = BotEndpoint.validate(raw);
        assertFalse(result.valid(), "should reject " + raw + ": " + because);
        assertFalse(result.error() == null || result.error().isEmpty(),
                "a rejection must carry a reason the command can print");
    }

    private static void assertAccepted(String raw, String normalised) {
        BotEndpoint.Result result = BotEndpoint.validate(raw);
        assertTrue(result.valid(), "should accept " + raw + " but said: " + result.error());
        assertEquals(normalised, result.endpoint());
    }

    @Nested
    @DisplayName("accepts")
    class Accepts {

        @Test
        @DisplayName("an https public host")
        void httpsPublic() {
            assertAccepted("https://api.bifrost.gg", "https://api.bifrost.gg");
        }

        @Test
        @DisplayName("a trailing slash, which it normalises away")
        void trailingSlash() {
            assertAccepted("https://api.bifrost.gg/", "https://api.bifrost.gg");
        }

        @Test
        @DisplayName("an https host with a port")
        void withPort() {
            assertAccepted("https://mc.example.test:8443", "https://mc.example.test:8443");
        }

        @Test
        @DisplayName("http to a single-label container name — the harness needs stub-bot")
        void httpContainerName() {
            assertAccepted("http://stub-bot:8080", "http://stub-bot:8080");
        }

        @Test
        @DisplayName("http to loopback and RFC1918 hosts — a self-hosted bot has no certificate")
        void httpPrivate() {
            assertAccepted("http://localhost:3001", "http://localhost:3001");
            assertAccepted("http://127.0.0.1:3001", "http://127.0.0.1:3001");
            assertAccepted("http://192.168.1.50:8080", "http://192.168.1.50:8080");
            assertAccepted("http://10.0.0.5:8080", "http://10.0.0.5:8080");
            assertAccepted("http://172.16.4.9:8080", "http://172.16.4.9:8080");
            assertAccepted("http://bot.internal:8080", "http://bot.internal:8080");
        }
    }

    @Nested
    @DisplayName("rejects")
    class Rejects {

        @Test
        @DisplayName("a plain http:// public host — the token would travel in cleartext")
        void httpPublic() {
            assertRejected("http://api.bifrost.gg", "public http leaks the token");
            assertRejected("http://evil.example.com:8080", "public http leaks the token");
        }

        @Test
        @DisplayName("a URL with a path, query, fragment or userinfo")
        void notABaseUrl() {
            assertRejected("https://api.bifrost.gg/minecraft", "a path means a full URL was pasted");
            assertRejected("https://api.bifrost.gg/?x=1", "a query string");
            assertRejected("https://api.bifrost.gg/#frag", "a fragment");
            assertRejected("https://user:pass@api.bifrost.gg", "credentials in the authority");
        }

        @Test
        @DisplayName("a non-http(s) scheme, a relative reference, and gibberish")
        void malformed() {
            assertRejected("ftp://api.bifrost.gg", "only http/https");
            assertRejected("api.bifrost.gg", "no scheme");
            assertRejected("not a url at all", "unparseable");
            assertRejected("", "empty");
            assertRejected(null, "null");
        }

        @Test
        @DisplayName("the classic allowlist-bypass host is not private")
        void notFooledByPrefix() {
            // A public host that merely CONTAINS a private-looking label is still public.
            assertRejected("http://10.0.0.5.evil.com", "a public FQDN, not an IP");
            assertRejected("http://localhost.evil.com", "not loopback");
        }
    }
}
