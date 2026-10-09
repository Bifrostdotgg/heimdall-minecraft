package com.heimdall.platform.bukkit.itemimage;

import com.heimdall.core.items.Snbt;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * One source of pack files: a directory, a zip, or the extracted vanilla assets.
 *
 * <p>Paths are pack-relative with forward slashes ({@code assets/minecraft/models/item/stone.json}).
 * A read never escapes the root, never returns more than {@code maxBytes}, and answers {@code null}
 * for anything missing or unreadable: a broken entry in somebody's resource pack is a missing
 * texture, not an exception that costs the whole picture.
 *
 * <p>A zip is read through {@link ZipFile}, which uses the central directory: the same index the
 * client uses, so an entry is found by name without streaming the archive.
 */
abstract class AssetRoot implements Closeable {

    /** The bytes at {@code path}, or {@code null}. */
    abstract byte[] read(String path, int maxBytes);

    /** For log lines: which source this is, never its contents. */
    abstract String describe();

    @Override
    public void close() {
    }

    /** {@code pack.mcmeta}'s overlay directories that apply to {@code packFormat}, highest priority first. */
    final List<String> overlays(int packFormat) {
        if (packFormat < 0) {
            return Collections.emptyList();
        }
        byte[] meta = read("pack.mcmeta", 256 * 1024);
        if (meta == null) {
            return Collections.emptyList();
        }
        try {
            Map<String, Object> root = Snbt.asMap(Snbt.parse(stripBom(
                    new String(meta, StandardCharsets.UTF_8))));
            Map<String, Object> overlays = Snbt.asMap(root == null ? null : root.get("overlays"));
            List<Object> entries = Snbt.asList(overlays == null ? null : overlays.get("entries"));
            if (entries == null) {
                return Collections.emptyList();
            }
            List<String> out = new ArrayList<String>();
            for (Object element : entries) {
                Map<String, Object> entry = Snbt.asMap(element);
                if (entry == null) {
                    continue;
                }
                String directory = Snbt.asString(entry.get("directory"));
                if (directory == null || directory.contains("..") || directory.contains("/")) {
                    continue;
                }
                if (formatMatches(entry, packFormat)) {
                    out.add(directory);
                }
            }
            // Later entries are applied on top of earlier ones.
            Collections.reverse(out);
            return out;
        } catch (Snbt.SyntaxException malformed) {
            return Collections.emptyList();
        }
    }

    /**
     * Whether an overlay entry covers {@code format}: {@code formats} as an int, {@code [min, max]}
     * or {@code {min_inclusive, max_inclusive}}, or 1.21.9's {@code min_format}/{@code max_format}
     * (an int or {@code [major, minor]}; the major is what is compared).
     */
    static boolean formatMatches(Map<String, Object> entry, int format) {
        Object formats = entry.get("formats");
        if (formats != null) {
            int[] range = range(formats);
            return range != null && format >= range[0] && format <= range[1];
        }
        Integer min = major(entry.get("min_format"));
        Integer max = major(entry.get("max_format"));
        if (min == null && max == null) {
            return false;
        }
        return (min == null || format >= min) && (max == null || format <= max);
    }

    private static int[] range(Object formats) {
        Integer single = Snbt.asInt(formats, null);
        if (single != null) {
            return new int[] {single, single};
        }
        List<Object> list = Snbt.asList(formats);
        if (list != null && list.size() == 2) {
            Integer a = Snbt.asInt(list.get(0), null);
            Integer b = Snbt.asInt(list.get(1), null);
            return a == null || b == null ? null : new int[] {a, b};
        }
        Map<String, Object> map = Snbt.asMap(formats);
        if (map != null) {
            Integer a = Snbt.asInt(map.get("min_inclusive"), null);
            Integer b = Snbt.asInt(map.get("max_inclusive"), null);
            return a == null || b == null ? null : new int[] {a, b};
        }
        return null;
    }

    private static Integer major(Object value) {
        Integer single = Snbt.asInt(value, null);
        if (single != null) {
            return single;
        }
        List<Object> list = Snbt.asList(value);
        return list != null && !list.isEmpty() ? Snbt.asInt(list.get(0), null) : null;
    }

    static String stripBom(String text) {
        return text.startsWith("﻿") ? text.substring(1) : text;
    }

    static boolean safePath(String path) {
        return path != null && !path.isEmpty() && !path.startsWith("/") && !path.contains("..")
                && !path.contains("\\") && !path.contains(":");
    }

    /** A pack (or the vanilla assets) on disk. */
    static final class Directory extends AssetRoot {

        private final Path root;

        Directory(Path root) {
            this.root = root.toAbsolutePath().normalize();
        }

        @Override
        byte[] read(String path, int maxBytes) {
            if (!safePath(path)) {
                return null;
            }
            Path file = root.resolve(path).normalize();
            if (!file.startsWith(root)) {
                return null;
            }
            try {
                if (!Files.isRegularFile(file) || Files.size(file) > maxBytes) {
                    return null;
                }
                return Files.readAllBytes(file);
            } catch (IOException | RuntimeException unreadable) {
                return null;
            }
        }

        @Override
        String describe() {
            return "directory " + root.getFileName();
        }
    }

    /** A pack zip, read through its central directory and kept open until closed. */
    static final class Zip extends AssetRoot {

        private final Path file;
        private final ZipFile zip;

        /** @throws IOException if the archive cannot be opened (corrupt, or deliberately mangled) */
        Zip(Path file) throws IOException {
            this.file = file;
            this.zip = new ZipFile(file.toFile());
        }

        @Override
        byte[] read(String path, int maxBytes) {
            if (!safePath(path)) {
                return null;
            }
            try {
                ZipEntry entry = zip.getEntry(path);
                if (entry == null || entry.isDirectory() || entry.getSize() > maxBytes) {
                    return null;
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream(
                            (int) Math.max(256, Math.min(maxBytes, entry.getSize())));
                    byte[] buffer = new byte[8192];
                    int total = 0;
                    int read;
                    while ((read = in.read(buffer)) > 0) {
                        total += read;
                        if (total > maxBytes) {
                            return null;
                        }
                        out.write(buffer, 0, read);
                    }
                    return out.toByteArray();
                }
            } catch (IOException | RuntimeException unreadable) {
                // A protected pack can open fine and still fail per entry. That is a missing file.
                return null;
            }
        }

        @Override
        String describe() {
            return "zip " + file.getFileName();
        }

        @Override
        public void close() {
            try {
                zip.close();
            } catch (IOException ignored) {
                // Read-only; nothing to lose.
            }
        }
    }

    /** Another root with its applicable overlay directories consulted first. */
    static final class WithOverlays extends AssetRoot {

        private final AssetRoot base;
        private final List<String> overlays;

        WithOverlays(AssetRoot base, List<String> overlays) {
            this.base = base;
            this.overlays = overlays;
        }

        static AssetRoot of(AssetRoot base, int packFormat) {
            List<String> overlays = base.overlays(packFormat);
            return overlays.isEmpty() ? base : new WithOverlays(base, overlays);
        }

        @Override
        byte[] read(String path, int maxBytes) {
            for (String overlay : overlays) {
                byte[] found = base.read(overlay + "/" + path, maxBytes);
                if (found != null) {
                    return found;
                }
            }
            return base.read(path, maxBytes);
        }

        @Override
        String describe() {
            return base.describe() + " (+" + overlays.size() + " overlay(s))";
        }

        @Override
        public void close() {
            base.close();
        }
    }
}
