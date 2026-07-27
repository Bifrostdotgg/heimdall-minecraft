package com.heimdall.stubbot;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC-SHA256 request signing, byte-for-byte compatible with the bot's {@code packages/shared/hmac.ts}.
 *
 * <p>Canonical string: {@code ${timestamp}\n${METHOD}\n${path}\n${sha256hex(body)}} — timestamp in
 * <em>seconds</em> as a decimal string, method upper-cased, body hashed even when empty (so a
 * bodyless request always signs over {@code e3b0c442…}, the SHA-256 of the empty string).
 *
 * <p><strong>What {@code path} means depends on the transport, and the two disagree.</strong> That
 * asymmetry is not a bug to be tidied up — it is the contract, and getting it wrong is invisible
 * until a real connection is refused:
 *
 * <ul>
 *   <li><strong>HTTP:</strong> {@code path} is Express's {@code req.originalUrl} — the path
 *       <em>including</em> the query string.
 *   <li><strong>WebSocket upgrade:</strong> {@code path} is {@code url.pathname} — the path
 *       <em>excluding</em> the query string, even though the signature and timestamp themselves
 *       travel as query parameters. See {@code MinecraftConnectionManager.verifyWsAuth}.
 * </ul>
 *
 * <p>Callers pass the correct {@code path} for their transport; this class does not guess.
 */
public final class Hmac {

    /** Requests older (or newer) than this are rejected as replays. Matches {@code MAX_AGE_MS}. */
    public static final long MAX_AGE_MS = 5L * 60L * 1000L;

    /** SHA-256 of the empty string — the body hash every bodyless request signs over. */
    public static final String EMPTY_BODY_SHA256 =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private Hmac() {
    }

    /** Builds the canonical string that gets signed. */
    public static String canonical(String timestamp, String method, String path, String body) {
        return timestamp + "\n" + method.toUpperCase(java.util.Locale.ROOT) + "\n" + path + "\n"
                + sha256Hex(body);
    }

    /** Signs a request, returning the lower-case hex signature for the {@code X-Signature} header. */
    public static String sign(String secret, String timestamp, String method, String path, String body) {
        return hmacSha256Hex(secret, canonical(timestamp, method, path, body));
    }

    /**
     * Verifies a signature against the current wall clock.
     *
     * @see #verify(String, String, String, String, String, String, long)
     */
    public static boolean verify(
            String secret, String method, String path, String body, String signature, String timestamp) {
        return verify(secret, method, path, body, signature, timestamp, System.currentTimeMillis());
    }

    /**
     * Verifies a signature, with the clock injected so tests can drive the replay window.
     *
     * <p>Mirrors the reference implementation's failure modes exactly: a non-numeric timestamp, a
     * timestamp outside ±5 minutes, a signature that is not valid hex, and a signature of the wrong
     * length all fail before any comparison. The comparison itself is constant-time over the decoded
     * bytes, matching {@code crypto.timingSafeEqual}.
     */
    public static boolean verify(
            String secret,
            String method,
            String path,
            String body,
            String signature,
            String timestamp,
            long nowMs) {
        if (signature == null || timestamp == null) {
            return false;
        }

        double seconds;
        try {
            seconds = Double.parseDouble(timestamp.trim());
        } catch (NumberFormatException | NullPointerException e) {
            return false;
        }
        if (Double.isNaN(seconds) || Double.isInfinite(seconds)) {
            return false;
        }
        if (Math.abs(nowMs - seconds * 1000.0) > MAX_AGE_MS) {
            return false;
        }

        byte[] provided = decodeHex(signature);
        if (provided == null) {
            return false;
        }
        byte[] expected = decodeHex(sign(secret, timestamp, method, path, body));
        if (expected == null || provided.length != expected.length) {
            return false;
        }
        return MessageDigest.isEqual(provided, expected);
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

    /**
     * Lower-case hex SHA-1 of a UTF-8 string.
     *
     * <p>SHA-1 is not a security choice here — it is the algorithm the bot's whitelist-sync ETag
     * uses, and the fixture has to reproduce it to be useful.
     */
    public static String sha1Hex(String data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            return hex(digest.digest(data.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 unavailable", e);
        }
    }

    private static String hmacSha256Hex(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return hex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 failed", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /** Decodes lower/upper-case hex, or returns null if the input is not valid hex. */
    private static byte[] decodeHex(String hex) {
        int length = hex.length();
        if (length % 2 != 0) {
            return null;
        }
        byte[] out = new byte[length / 2];
        for (int i = 0; i < length; i += 2) {
            int hi = Character.digit(hex.charAt(i), 16);
            int lo = Character.digit(hex.charAt(i + 1), 16);
            if (hi < 0 || lo < 0) {
                return null;
            }
            out[i / 2] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}
