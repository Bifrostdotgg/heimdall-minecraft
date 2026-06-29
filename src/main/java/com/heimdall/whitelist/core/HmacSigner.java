package com.heimdall.whitelist.core;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.InvalidKeyException;

/**
 * HMAC-SHA256 request signing compatible with the Heimdall bot API.
 *
 * The shared secret never crosses the wire — only a derived signature does.
 * The signed payload includes timestamp, method, path, and body hash to
 * provide integrity protection and replay resistance.
 *
 * Canonical string: {@code ${timestamp}\n${method}\n${path}\n${sha256(body)}}
 */
public final class HmacSigner {

  private HmacSigner() {
  }

  /**
   * Sign an outgoing request.
   *
   * @param secret Shared HMAC secret (INTERNAL_API_KEY)
   * @param method HTTP method (GET, POST, etc.)
   * @param path   Full request path including query string
   * @param body   Request body string (use "" for bodyless requests)
   * @return a two-element String array: [signature, timestamp]
   */
  public static String[] sign(String secret, String method, String path, String body) {
    String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
    String canonical = buildCanonical(timestamp, method, path, body);
    String signature = hmacSha256(secret, canonical);
    return new String[] { signature, timestamp };
  }

  private static String buildCanonical(String timestamp, String method, String path, String body) {
    return timestamp + "\n" + method.toUpperCase() + "\n" + path + "\n" + sha256(body);
  }

  private static String sha256(String data) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(data.getBytes(StandardCharsets.UTF_8));
      return hexEncode(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException("SHA-256 not available", e);
    }
  }

  private static String hmacSha256(String secret, String data) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
      return hexEncode(hash);
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new RuntimeException("HMAC-SHA256 signing failed", e);
    }
  }

  private static String hexEncode(byte[] bytes) {
    StringBuilder sb = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      sb.append(String.format("%02x", b & 0xFF));
    }
    return sb.toString();
  }
}
