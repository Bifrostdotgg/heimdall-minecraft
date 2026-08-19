package com.heimdall.platform.common;

import com.heimdall.core.json.Payload;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * MOTD and favicon fields on a health snapshot, written once so the three platforms cannot
 * disagree about stripping, the 64 KiB cap, or the base64 dialect.
 *
 * <p>{@code motdClean} is always present. {@code motdRaw} is omitted when there is nothing to
 * send. {@code iconPngBase64} is omitted when the PNG is missing, empty, or larger than
 * {@link #MAX_ICON_BYTES} decoded. The encoded form is standard base64 with no
 * {@code data:image/png;base64,} prefix.
 */
public final class StatusHealth {

    /** Decoded PNG larger than this is treated as missing. Favicons are 64x64. */
    public static final int MAX_ICON_BYTES = 64 * 1024;

    private static final String DATA_URI_PREFIX = "data:";

    private static final Pattern MINIMESSAGE = Pattern.compile("<[^>]*>");
    private static final Pattern LEGACY_HEX =
            Pattern.compile("(?i)\u00A7x(?:\u00A7[0-9a-f]){6}");
    private static final Pattern LEGACY_CODE = Pattern.compile("(?i)\u00A7[0-9a-fk-or]");

    private StatusHealth() {
    }

    /**
     * Adds status fields derived from a raw MOTD string (legacy section codes and/or
     * MiniMessage) and optional PNG bytes.
     */
    public static Payload.Builder apply(Payload.Builder builder, String rawMotd, byte[] iconPng) {
        String raw = rawMotd == null ? "" : rawMotd;
        return apply(builder, clean(raw), raw, iconPng);
    }

    /**
     * Adds status fields when the caller already has a clean string (a Component serialised
     * to plain text) and an optional raw form.
     */
    public static Payload.Builder apply(
            Payload.Builder builder, String motdClean, String motdRaw, byte[] iconPng) {
        builder.put("motdClean", motdClean == null ? "" : motdClean);
        if (motdRaw != null && !motdRaw.isEmpty()) {
            builder.put("motdRaw", motdRaw);
        }
        String encoded = encodeIcon(iconPng);
        if (encoded != null) {
            builder.put("iconPngBase64", encoded);
        }
        return builder;
    }

    /**
     * Plain text: MiniMessage tags, {@code §x} hex runs, colour/format codes, then leftover
     * section signs.
     */
    public static String clean(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String withoutTags = MINIMESSAGE.matcher(raw).replaceAll("");
        String withoutHex = LEGACY_HEX.matcher(withoutTags).replaceAll("");
        String withoutCodes = LEGACY_CODE.matcher(withoutHex).replaceAll("");
        return withoutCodes.replace("\u00A7", "");
    }

    /**
     * Standard base64 of {@code png}, or {@code null} when it is missing or over the cap.
     * Never a data-URI.
     */
    public static String encodeIcon(byte[] png) {
        if (png == null || png.length == 0 || png.length > MAX_ICON_BYTES) {
            return null;
        }
        return Base64.getEncoder().encodeToString(png);
    }

    /**
     * PNG bytes from a data-URI or a raw standard-base64 string. Oversize, empty, or
     * unreadable input is missing.
     */
    public static byte[] decodeIcon(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return null;
        }
        String payload = encoded;
        if (startsWithIgnoreCase(encoded, DATA_URI_PREFIX)) {
            int comma = encoded.indexOf(',');
            if (comma < 0) {
                return null;
            }
            payload = encoded.substring(comma + 1);
        }
        if (payload.isEmpty()) {
            return null;
        }
        try {
            byte[] png = Base64.getDecoder().decode(payload);
            if (png.length == 0 || png.length > MAX_ICON_BYTES) {
                return null;
            }
            return png;
        } catch (IllegalArgumentException bad) {
            return null;
        }
    }

    private static boolean startsWithIgnoreCase(String value, String prefix) {
        return value.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    /**
     * A PNG on disk, re-read only when its mtime changes. Heartbeats are ~30s; a 64 KiB
     * file does not need to come off disk every tick.
     */
    public static final class IconFile {

        private final File file;
        private long mtime = Long.MIN_VALUE;
        private boolean loaded;
        private byte[] png;

        public IconFile(File file) {
            this.file = file;
        }

        /** The current bytes, or {@code null} when the file is missing, empty, or over the cap. */
        public byte[] read() {
            if (file == null) {
                return null;
            }
            boolean exists = file.isFile();
            long now = exists ? file.lastModified() : Long.MIN_VALUE;
            if (loaded && now == mtime) {
                return png;
            }
            loaded = true;
            mtime = now;
            if (!exists) {
                png = null;
                return null;
            }
            try {
                byte[] data = Files.readAllBytes(file.toPath());
                if (data.length == 0 || data.length > MAX_ICON_BYTES) {
                    png = null;
                } else {
                    png = data;
                }
            } catch (IOException failed) {
                png = null;
            }
            return png;
        }
    }
}
