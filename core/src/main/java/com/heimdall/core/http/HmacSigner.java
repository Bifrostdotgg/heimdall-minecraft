package com.heimdall.core.http;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.function.LongSupplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC-SHA256 request signing, byte-for-byte compatible with the bot's {@code packages/shared/hmac.ts}.
 *
 * <p>The canonical string is {@code ${timestampSeconds}\n${METHOD}\n${path}\n${sha256hex(body)}}:
 * timestamp in <em>seconds</em> as a decimal string, method upper-cased, and the body hashed even
 * when empty — so every bodyless request signs over {@link #EMPTY_BODY_SHA256}. The shared secret
 * itself never crosses the wire.
 *
 * <p><strong>What {@code path} means depends on the transport, and the two transports disagree.</strong>
 * That is the reason this class has two signing methods instead of one with a boolean:
 *
 * <ul>
 *   <li>{@link #forHttp} signs {@code req.originalUrl} — the path <em>including</em> the query
 *       string.
 *   <li>{@link #forWsHandshake} signs {@code url.pathname} — the path <em>excluding</em> the query
 *       string, even though the signature and timestamp themselves travel as query parameters on
 *       that request.
 * </ul>
 *
 * <p>Getting it the wrong way round produces a perfectly well-formed signature that the bot
 * rejects, with nothing in either log to say why. Naming the two cases in the API is the cheapest
 * available defence.
 */
public final class HmacSigner {

    /** SHA-256 of the empty string — what every bodyless request signs over. */
    public static final String EMPTY_BODY_SHA256 =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private final String secret;
    private final LongSupplier clockMillis;

    /** Signs with {@code secret}, timestamping against the system clock. */
    public HmacSigner(String secret) {
        this(secret, new LongSupplier() {
            @Override
            public long getAsLong() {
                return System.currentTimeMillis();
            }
        });
    }

    /**
     * Signs with {@code secret}, timestamping against an injected clock.
     *
     * <p>Exists so tests can pin the timestamp and assert on a fixed signature; production code
     * uses {@link #HmacSigner(String)}.
     */
    public HmacSigner(String secret, LongSupplier clockMillis) {
        if (secret == null) {
            throw new IllegalArgumentException("secret is required (use \"\" only for an unconfigured client)");
        }
        if (clockMillis == null) {
            throw new IllegalArgumentException("clock is required");
        }
        this.secret = secret;
        this.clockMillis = clockMillis;
    }

    /**
     * Signs an HTTP request.
     *
     * @param method the HTTP method; case-insensitive, upper-cased into the canonical string
     * @param pathWithQuery the request path <strong>including</strong> any query string, e.g.
     *     {@code /api/guilds/123/minecraft/whitelist/sync?since=42}
     * @param body the request body, or {@code ""} for a bodyless request
     */
    public Signature forHttp(String method, String pathWithQuery, String body) {
        return signNow(method, pathWithQuery, body == null ? "" : body);
    }

    /**
     * Signs a WebSocket upgrade.
     *
     * <p>Always {@code GET} with an empty body, and always the path <strong>without</strong> its
     * query string.
     *
     * @param pathWithoutQuery e.g. {@code /ws/minecraft/123456789012345678}
     */
    public Signature forWsHandshake(String pathWithoutQuery) {
        return signNow("GET", pathWithoutQuery, "");
    }

    private Signature signNow(String method, String path, String body) {
        String timestamp = String.valueOf(clockMillis.getAsLong() / 1000L);
        return new Signature(sign(secret, timestamp, method, path, body), timestamp);
    }

    /**
     * The canonical string that gets signed.
     *
     * <p>Public so the golden-vector tests can pin the layout itself, not just the resulting hex.
     */
    public static String canonical(String timestamp, String method, String path, String body) {
        return timestamp + "\n" + method.toUpperCase(Locale.ROOT) + "\n" + path + "\n"
                + sha256Hex(body == null ? "" : body);
    }

    /** Signs an explicit timestamp — the deterministic form the golden vectors use. */
    public static String sign(String secret, String timestamp, String method, String path, String body) {
        return hmacSha256Hex(secret, canonical(timestamp, method, path, body));
    }

    /** Lower-case hex SHA-256 of a UTF-8 string. */
    public static String sha256Hex(String data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return hex(digest.digest(data.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String hmacSha256Hex(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return hex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 signing failed", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(Character.forDigit((b >> 4) & 0xF, 16));
            out.append(Character.forDigit(b & 0xF, 16));
        }
        return out.toString();
    }
}
