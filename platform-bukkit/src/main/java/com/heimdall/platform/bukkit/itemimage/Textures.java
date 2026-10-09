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

/**
 * Decoded pack textures, by reference, with a small LRU in front.
 *
 * <p>Pack PNGs are operator- and plugin-supplied, so every one is bounded twice: the file size
 * before it is read, and the image dimensions, read from the header, before a single pixel is
 * decoded (a 64 KB PNG can declare a 50,000 x 50,000 canvas). An animated texture is a vertical
 * strip of frames; only the first frame is kept, sized by its {@code .mcmeta} or, without one, as a
 * square.
 *
 * <p>Render thread only.
 */
final class Textures {

    static final int MAX_FILE_BYTES = 4 * 1024 * 1024;
    static final int MAX_SIDE = 4096;
    static final long MAX_PIXELS = 4L * 1024 * 1024;
    private static final int CACHE_ENTRIES = 256;

    private final PackStack stack;
    private final Map<String, BufferedImage> cache =
            new LinkedHashMap<String, BufferedImage>(64, 0.75f, true) {
                private static final long serialVersionUID = 1L;

                @Override
                protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> eldest) {
                    return size() > CACHE_ENTRIES;
                }
            };

    Textures(PackStack stack) {
        this.stack = stack;
    }

    /** The texture {@code reference} names ({@code minecraft:item/stick}), or {@code null}. */
    BufferedImage get(String reference) {
        if (reference == null) {
            return null;
        }
        if (cache.containsKey(reference)) {
            return cache.get(reference);
        }
        BufferedImage image = load(ModelResolver.path(reference, "textures", ".png"));
        cache.put(reference, image);
        return image;
    }

    /** A pack-relative PNG path, decoded and bounded; {@code null} if missing or unacceptable. */
    BufferedImage load(String path) {
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

    /** Decodes a PNG after checking its declared size; always {@code TYPE_INT_ARGB}. */
    static BufferedImage decode(byte[] bytes) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (in == null) {
                return null;
            }
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
        BufferedImage out = new BufferedImage(source.getWidth(), source.getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                out.setRGB(x, y, source.getRGB(x, y));
            }
        }
        return out;
    }

    /** The first frame of an animation strip; the image itself if it is not one. */
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
                || frameHeight > height) {
            return image;
        }
        return image.getSubimage(0, 0, frameWidth, frameHeight);
    }
}
