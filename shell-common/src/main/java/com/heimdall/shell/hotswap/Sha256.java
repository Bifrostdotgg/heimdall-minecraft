package com.heimdall.shell.hotswap;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * SHA-256 as 64 lowercase hex characters, which is the form GitHub reports an asset digest in and
 * the form the bot passes on.
 */
public final class Sha256 {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Sha256() {
    }

    /** The digest of {@code bytes}. */
    public static String of(byte[] bytes) {
        MessageDigest digest = newDigest();
        digest.update(bytes);
        return hex(digest.digest());
    }

    /** The digest of a file, streamed. */
    public static String of(Path file) throws IOException {
        MessageDigest digest = newDigest();
        InputStream in = Files.newInputStream(file);
        try {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        } finally {
            in.close();
        }
        return hex(digest.digest());
    }

    /**
     * Whether {@code value} is a well-formed digest: exactly 64 lowercase hex characters.
     *
     * <p>Strict on purpose. An uppercase or {@code sha256:}-prefixed value is a sign that something
     * between GitHub and here changed shape, and the place to find that out is a refusal, not a
     * comparison that quietly normalised it.
     */
    public static boolean isWellFormed(String value) {
        if (value == null || value.length() != 64) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean digit = c >= '0' && c <= '9';
            boolean lowerHex = c >= 'a' && c <= 'f';
            if (!digit && !lowerHex) {
                return false;
            }
        }
        return true;
    }

    /** Lowercases an incoming digest for display only; comparison stays strict. */
    static String display(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            // Every Java SE runtime is required to provide SHA-256.
            throw new IllegalStateException("this JVM has no SHA-256", impossible);
        }
    }

    private static String hex(byte[] digest) {
        char[] out = new char[digest.length * 2];
        for (int i = 0; i < digest.length; i++) {
            int b = digest[i] & 0xFF;
            out[i * 2] = HEX[b >>> 4];
            out[i * 2 + 1] = HEX[b & 0x0F];
        }
        return new String(out);
    }
}
