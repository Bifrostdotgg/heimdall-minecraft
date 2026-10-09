package com.heimdall.platform.bukkit.itemimage;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;

/** Small packs, PNGs, zips and a fake network, built in memory for the item-image tests. */
final class Fixtures {

    private Fixtures() {
    }

    /** A solid {@code w} x {@code h} PNG. */
    static byte[] png(int w, int h, int argb) {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                image.setRGB(x, y, argb);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** Writes {@code files} (pack path to bytes) under {@code root}. */
    static Path write(Path root, Map<String, byte[]> files) {
        try {
            for (Map.Entry<String, byte[]> entry : files.entrySet()) {
                Path file = root.resolve(entry.getKey());
                Files.createDirectories(file.getParent());
                Files.write(file, entry.getValue());
            }
            return root;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A zip holding {@code files}, in order. */
    static byte[] zip(Map<String, byte[]> files) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> entry : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    /** An ordered map literal: key, value, key, value. */
    static Map<String, byte[]> files(Object... pairs) {
        Map<String, byte[]> out = new LinkedHashMap<String, byte[]>();
        for (int i = 0; i < pairs.length; i += 2) {
            Object value = pairs[i + 1];
            out.put((String) pairs[i], value instanceof String ? utf8((String) value) : (byte[]) value);
        }
        return out;
    }

    /** A stack over directories, highest priority first, no overlays. */
    static PackStack stack(Path... roots) {
        List<AssetRoot> list = new java.util.ArrayList<AssetRoot>();
        for (Path root : roots) {
            list.add(new AssetRoot.Directory(root));
        }
        return new PackStack(list, "test");
    }

    /** A network that serves fixed bytes by URL and records every request. */
    static final class FakeHttp implements HttpSource {

        final Map<String, byte[]> responses = new HashMap<String, byte[]>();
        final List<String> requests = Collections.synchronizedList(new java.util.ArrayList<String>());

        FakeHttp serve(String url, byte[] body) {
            responses.put(url, body);
            return this;
        }

        @Override
        public byte[] get(String url, long maxBytes) throws IOException {
            requests.add(url);
            byte[] body = responses.get(url);
            if (body == null) {
                throw new IOException("HTTP 404");
            }
            if (body.length > maxBytes) {
                throw new IOException("too large");
            }
            return body;
        }

        @Override
        public long download(String url, Path target, long maxBytes) throws IOException {
            byte[] body = get(url, maxBytes);
            Files.write(target, body);
            return body.length;
        }
    }
}
