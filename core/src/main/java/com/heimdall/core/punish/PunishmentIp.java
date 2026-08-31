package com.heimdall.core.punish;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC-SHA256 of a canonical IP, matching Heimdall {@code bot/plugins/minecraft/lib/punishmentIp.ts}.
 *
 * <p>The guild salt is pushed in {@code config.push}. The digest is what leaves this process; a raw
 * address never belongs in an HTTP body or a Mongo row.
 */
public final class PunishmentIp {

    private PunishmentIp() {
    }

    /**
     * Lower-case, trimmed, with an IPv4-mapped IPv6 prefix stripped.
     *
     * <p>{@code ::ffff:1.2.3.4} and {@code 1.2.3.4} hash alike. IPv6 is not expanded or compressed
     * beyond that; the TypeScript side does the same.
     */
    public static String canonical(String ip) {
        if (ip == null) {
            return "";
        }
        String trimmed = ip.trim().toLowerCase(Locale.ROOT);
        if (trimmed.startsWith("::ffff:")) {
            return trimmed.substring("::ffff:".length());
        }
        return trimmed;
    }

    /**
     * Lower-case hex HMAC-SHA256 of {@link #canonical(String)} with {@code salt} as the key.
     *
     * @return {@code ""} when {@code ip} is blank or hashing is unavailable
     */
    public static String hash(String ip, String salt) {
        String canonical = canonical(ip);
        if (canonical.isEmpty() || salt == null) {
            return "";
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(salt.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return hex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            return "";
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
