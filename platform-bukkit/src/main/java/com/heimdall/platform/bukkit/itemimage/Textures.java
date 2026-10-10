package com.heimdall.platform.bukkit.itemimage;

import com.heimdall.core.items.Snbt;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;

/**
 * Decoded pack textures, by reference, with a byte-bounded LRU in front.
 *
 * <p>Pack PNGs are operator- and plugin-supplied, so every one is bounded before it costs memory:
 * the file size before it is read, and the image dimensions, read from the header, before a single
 * pixel is decoded (a 64 KB PNG can declare a 50,000 x 50,000 canvas). An animated texture is a
 * vertical strip of frames; only the first frame is kept, copied into its own raster so the strip
 * can be collected. A model texture is then shrunk to the icon's own pixel size,
 * {@value #MAX_MODEL_SIDE} pixels, nearest-neighbour: a 512-pixel HD texture carries nothing a
 * 32-pixel icon can show, and keeping it would cost 64 times the memory. Font sheets and tooltip
 * sprites ({@link #load}) are kept at full size, since glyphs are cut from them.
 *
 * <p>The cache counts decoded bytes (4 per pixel) and evicts down to {@value #MAX_CACHE_BYTES}.
 * Decoding goes through an in-memory image stream, never the global ImageIO disk cache, so nothing
 * here writes a temporary file or changes ImageIO's settings for the rest of the server.
 *
 * <p>Render thread only.
 */
final class Textures {

    static final int MAX_FILE_BYTES = 4 * 1024 * 1024;
    static final int MAX_SIDE = 4096;
    static final long MAX_PIXELS = 4L * 1024 * 1024;
    static final long MAX_CACHE_BYTES = 32L * 1024 * 1024;

    /** The largest model texture kept: the 16-pixel icon at the card's scale of 2. */
    static final int MAX_MODEL_SIDE = 32;

    private final PackStack stack;
    private final LinkedHashMap<String, BufferedImage> cache =
            new LinkedHashMap<String, BufferedImage>(64, 0.75f, true);
    private long cachedBytes;

    Textures(PackStack stack) {
        this.stack = stack;
    }

    /** The texture {@code reference} names ({@code minecraft:item/stick}), shrunk for an icon. */
    BufferedImage get(String reference) {
        if (reference == null) {
            return null;
        }
        String key = "ref:" + reference;
        if (cache.containsKey(key)) {
            return cache.get(key);
        }
        BufferedImage image = fit(decodePath(ModelResolver.path(reference, "textures", ".png")),
                MAX_MODEL_SIDE);
        remember(key, image);
        return image;
    }

    /** A pack-relative PNG at full size (font sheets, sprites); {@code null} if missing or refused. */
    BufferedImage load(String path) {
        String key = "path:" + path;
        if (cache.containsKey(key)) {
            return cache.get(key);
        }
        BufferedImage image = decodePath(path);
        remember(key, image);
        return image;
    }

    /** Decoded bytes currently held. */
    long cachedBytes() {
        return cachedBytes;
    }

    private BufferedImage decodePath(String path) {
        byte[] bytes = stack.read(path, MAX_FILE_BYTES);
        if (bytes == null) {
            return null;
        }
        BufferedImage image = decode(bytes);
        if (image == null) {
            return null;
        }
        return firstFrame(image, stack.read(path + ".mcmeta", 64 * 1024));
    }

    private void remember(String key, BufferedImage image) {
        cache.put(key, image);
        cachedBytes += bytes(image);
        Iterator<Map.Entry<String, BufferedImage>> eldest = cache.entrySet().iterator();
        while (cachedBytes > MAX_CACHE_BYTES && eldest.hasNext()) {
            Map.Entry<String, BufferedImage> entry = eldest.next();
            if (entry.getKey().equals(key)) {
                continue;
            }
            cachedBytes -= bytes(entry.getValue());
            eldest.remove();
        }
    }

    private static long bytes(BufferedImage image) {
        return image == null ? 0L : 4L * image.getWidth() * image.getHeight();
    }

    /** Decodes a PNG after checking its declared size; always {@code TYPE_INT_ARGB}. */
    static BufferedImage decode(byte[] bytes) {
        try (ImageInputStream in = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || width > MAX_SIDE || height > MAX_SIDE * 4
                        || (long) width * height > MAX_PIXELS) {
                    return null;
                }
                return argb(reader.read(0));
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException | OutOfMemoryError unreadable) {
            return null;
        }
    }

    static BufferedImage argb(BufferedImage source) {
        if (source == null || source.getType() == BufferedImage.TYPE_INT_ARGB) {
            return source;
        }
        return copy(source, 0, 0, source.getWidth(), source.getHeight());
    }

    /** A region of {@code source} in a raster of its own (a sub-image would pin the whole source). */
    static BufferedImage copy(BufferedImage source, int x, int y, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int row = 0; row < h; row++) {
            for (int col = 0; col < w; col++) {
                out.setRGB(col, row, source.getRGB(x + col, y + row));
            }
        }
        return out;
    }

    /** {@code image} shrunk, nearest-neighbour and keeping its aspect, to at most {@code side}. */
    static BufferedImage fit(BufferedImage image, int side) {
        if (image == null || (image.getWidth() <= side && image.getHeight() <= side)) {
            return image;
        }
        int longest = Math.max(image.getWidth(), image.getHeight());
        int w = Math.max(1, (int) ((long) image.getWidth() * side / longest));
        int h = Math.max(1, (int) ((long) image.getHeight() * side / longest));
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            int sy = (int) ((long) y * image.getHeight() / h);
            for (int x = 0; x < w; x++) {
                int sx = (int) ((long) x * image.getWidth() / w);
                out.setRGB(x, y, image.getRGB(sx, sy));
            }
        }
        return out;
    }

    /** The first frame of an animation strip, in its own raster; the image itself if not one. */
    static BufferedImage firstFrame(BufferedImage image, byte[] mcmeta) {
        int width = image.getWidth();
        int height = image.getHeight();
        int frameWidth = width;
        int frameHeight = height;
        boolean animated = false;
        if (mcmeta != null) {
            try {
                Map<String, Object> meta = Snbt.asMap(Snbt.parse(AssetRoot.stripBom(
                        new String(mcmeta, StandardCharsets.UTF_8))));
                Map<String, Object> animation = Snbt.asMap(meta == null ? null
                        : meta.get("animation"));
                if (animation != null) {
                    animated = true;
                    Integer w = Snbt.asInt(animation.get("width"), null);
                    Integer h = Snbt.asInt(animation.get("height"), null);
                    frameWidth = w != null ? w : Math.min(width, height);
                    frameHeight = h != null ? h : frameWidth;
                }
            } catch (Snbt.SyntaxException ignored) {
                // A malformed mcmeta: treat as a plain image.
            }
        }
        if (!animated && height > width && height % width == 0) {
            animated = true;
            frameWidth = width;
            frameHeight = width;
        }
        if (!animated || frameWidth <= 0 || frameHeight <= 0 || frameWidth > width
                || frameHeight > height || (frameWidth == width && frameHeight == height)) {
            return image;
        }
        return copy(image, 0, 0, frameWidth, frameHeight);
    }
}
