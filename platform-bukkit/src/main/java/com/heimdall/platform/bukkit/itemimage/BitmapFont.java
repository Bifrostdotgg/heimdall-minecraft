package com.heimdall.platform.bukkit.itemimage;

import com.heimdall.core.items.Snbt;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Minecraft's bitmap font, read from the packs, drawn pixel by pixel.
 *
 * <h2>No system fonts</h2>
 *
 * <p>A headless server often has no fonts installed at all, and where it does they are not
 * Minecraft's. So text is drawn only from the client's own bitmap glyphs: the providers in
 * {@code font/default.json} (followed through {@code reference} providers to
 * {@code include/default.json} and {@code include/space.json} on newer versions), or, on a version
 * before 1.13 that has no font definitions, {@code textures/font/ascii.png} laid out on its fixed
 * 16 by 16 grid.
 *
 * <p>When neither exists (the vanilla download is switched off or has not finished, and no pack
 * supplies a font) a small 5 by 7 ASCII bitmap built into this class stands in, so a card is still
 * readable. It is the common public-domain 5x7 dot-matrix shape set, not Mojang's art. A character
 * no provider covers falls back to that set, and failing that to {@code ?}. Unifont, the client's
 * last-resort font for scripts the bitmaps do not cover, lives in the asset index rather than the
 * client jar and is not fetched.
 *
 * <h2>Metrics</h2>
 *
 * <p>A bitmap glyph's width is its rightmost non-transparent column; its advance is that width at
 * the provider's scale ({@code height / cell height}), rounded, plus one. The glyph's top sits
 * {@code 7 - ascent} GUI pixels below the line's top, so the standard ASCII sheet (ascent 7) starts
 * on it and taller accented glyphs reach above it, as in game. Bold is the glyph drawn again one GUI
 * pixel right, adding one to the advance; italic shears each row by {@code 1 - 0.25 * y}.
 *
 * <p>Render thread only.
 */
final class BitmapFont {

    /** One glyph: where it is on its sheet and how it sits on the line. */
    static final class Glyph {
        final BufferedImage sheet;
        final int x;
        final int y;
        final int cellWidth;
        final int cellHeight;
        /** GUI pixels per sheet pixel. */
        final double scale;
        final double ascent;
        final double advance;

        Glyph(BufferedImage sheet, int x, int y, int cellWidth, int cellHeight, double scale,
                double ascent, double advance) {
            this.sheet = sheet;
            this.x = x;
            this.y = y;
            this.cellWidth = cellWidth;
            this.cellHeight = cellHeight;
            this.scale = scale;
            this.ascent = ascent;
            this.advance = advance;
        }

        boolean blank() {
            return sheet == null;
        }
    }

    private final Map<Integer, Glyph> glyphs;
    private final boolean fromAssets;

    private BitmapFont(Map<Integer, Glyph> glyphs, boolean fromAssets) {
        this.glyphs = glyphs;
        this.fromAssets = fromAssets;
    }

    /** Whether the glyphs came from real assets rather than the built-in stand-in. */
    boolean fromAssets() {
        return fromAssets;
    }

    /** Loads {@code minecraft:default}; never {@code null}. */
    static BitmapFont load(PackStack stack, Textures textures, Deadline deadline) {
        Map<Integer, Glyph> glyphs = new HashMap<Integer, Glyph>();
        addFont(stack, textures, "minecraft:default", glyphs, new HashSet<String>(), deadline, 0);
        if (!glyphs.containsKey((int) 'A')) {
            BufferedImage ascii = textures.load("assets/minecraft/textures/font/ascii.png");
            if (ascii != null) {
                addLegacyAscii(ascii, glyphs);
            }
        }
        boolean fromAssets = glyphs.containsKey((int) 'A');
        if (!glyphs.containsKey((int) ' ')) {
            glyphs.put((int) ' ', new Glyph(null, 0, 0, 0, 0, 1, 0, 4));
        }
        return new BitmapFont(glyphs, fromAssets);
    }

    private static void addFont(PackStack stack, Textures textures, String id,
            Map<Integer, Glyph> glyphs, Set<String> visited, Deadline deadline, int depth) {
        if (depth > 8 || !visited.add(id)) {
            return;
        }
        Map<String, Object> font = Snbt.asMap(stack.json(ModelResolver.path(id, "font", ".json")));
        List<Object> providers = Snbt.asList(font == null ? null : font.get("providers"));
        if (providers == null) {
            return;
        }
        for (Object element : providers) {
            deadline.check();
            Map<String, Object> provider = Snbt.asMap(element);
            if (provider == null) {
                continue;
            }
            String type = ModelResolver.strip(Snbt.asString(provider.get("type")));
            if ("bitmap".equals(type)) {
                addBitmap(textures, provider, glyphs);
            } else if ("space".equals(type)) {
                Map<String, Object> advances = Snbt.asMap(provider.get("advances"));
                if (advances != null) {
                    for (Map.Entry<String, Object> entry : advances.entrySet()) {
                        if (entry.getKey().isEmpty()) {
                            continue;
                        }
                        int cp = entry.getKey().codePointAt(0);
                        Double advance = Snbt.asDouble(entry.getValue(), null);
                        if (advance != null && !glyphs.containsKey(cp)) {
                            glyphs.put(cp, new Glyph(null, 0, 0, 0, 0, 1, 0, advance));
                        }
                    }
                }
            } else if ("reference".equals(type) || "include".equals(type)) {
                String reference = Snbt.asString(provider.get("id"));
                if (reference != null) {
                    addFont(stack, textures, reference, glyphs, visited, deadline, depth + 1);
                }
            }
            // ttf, unihex, legacy_unicode: not drawn; the built-in set covers ASCII gaps.
        }
    }

    private static void addBitmap(Textures textures, Map<String, Object> provider,
            Map<Integer, Glyph> glyphs) {
        String file = Snbt.asString(provider.get("file"));
        List<Object> rows = Snbt.asList(provider.get("chars"));
        if (file == null || rows == null || rows.isEmpty()) {
            return;
        }
        BufferedImage sheet = textures.load(ModelResolver.path(file, "textures", ""));
        if (sheet == null) {
            return;
        }
        int columns = 0;
        int[][] codepoints = new int[rows.size()][];
        for (int r = 0; r < rows.size(); r++) {
            String row = Snbt.asString(rows.get(r));
            codepoints[r] = row == null ? new int[0] : row.codePoints().toArray();
            columns = Math.max(columns, codepoints[r].length);
        }
        if (columns == 0) {
            return;
        }
        int cellWidth = sheet.getWidth() / columns;
        int cellHeight = sheet.getHeight() / rows.size();
        if (cellWidth <= 0 || cellHeight <= 0) {
            return;
        }
        double height = Snbt.asDouble(provider.get("height"), 8.0);
        double ascent = Snbt.asDouble(provider.get("ascent"), 7.0);
        double scale = height / cellHeight;
        for (int r = 0; r < codepoints.length; r++) {
            for (int c = 0; c < codepoints[r].length; c++) {
                int cp = codepoints[r][c];
                if (cp == 0 || glyphs.containsKey(cp)) {
                    continue;
                }
                int x = c * cellWidth;
                int y = r * cellHeight;
                int width = contentWidth(sheet, x, y, cellWidth, cellHeight);
                double advance = (int) (0.5 + width * scale) + 1;
                glyphs.put(cp, new Glyph(sheet, x, y, cellWidth, cellHeight, scale, ascent, advance));
            }
        }
    }

    /** {@code textures/font/ascii.png} before 1.13: a 16 by 16 grid indexed by character code. */
    private static void addLegacyAscii(BufferedImage sheet, Map<Integer, Glyph> glyphs) {
        int cellWidth = sheet.getWidth() / 16;
        int cellHeight = sheet.getHeight() / 16;
        if (cellWidth <= 0 || cellHeight <= 0) {
            return;
        }
        double scale = 8.0 / cellHeight;
        for (int cp = 33; cp < 127; cp++) {
            int x = (cp % 16) * cellWidth;
            int y = (cp / 16) * cellHeight;
            int width = contentWidth(sheet, x, y, cellWidth, cellHeight);
            glyphs.put(cp, new Glyph(sheet, x, y, cellWidth, cellHeight, scale, 7,
                    (int) (0.5 + width * scale) + 1));
        }
    }

    private static int contentWidth(BufferedImage sheet, int x, int y, int w, int h) {
        for (int col = w - 1; col >= 0; col--) {
            for (int row = 0; row < h; row++) {
                if (((sheet.getRGB(x + col, y + row) >>> 24) & 0xFF) != 0) {
                    return col + 1;
                }
            }
        }
        return 0;
    }

    /** The glyph for {@code cp}: this font's, else the built-in one, else {@code ?}. */
    Glyph glyph(int cp) {
        Glyph glyph = glyphs.get(cp);
        if (glyph != null) {
            return glyph;
        }
        glyph = Builtin.glyph(cp);
        if (glyph != null) {
            return glyph;
        }
        Glyph question = glyphs.get((int) '?');
        return question != null ? question : Builtin.glyph('?');
    }

    /** Advance of one character, in GUI pixels. */
    double advance(int cp, boolean bold) {
        return glyph(cp).advance + (bold ? 1 : 0);
    }

    /**
     * Draws one character with its top-left at GUI ({@code x}, {@code y}) in {@code argb}.
     *
     * @return the advance, in GUI pixels
     */
    double draw(Canvas canvas, int cp, double x, double y, int argb, boolean bold,
            boolean italic) {
        Glyph glyph = glyph(cp);
        if (!glyph.blank()) {
            paint(canvas, glyph, x, y, argb, italic);
            if (bold) {
                paint(canvas, glyph, x + 1, y, argb, italic);
            }
        }
        return glyph.advance + (bold ? 1 : 0);
    }

    private static void paint(Canvas canvas, Glyph glyph, double x, double y, int argb,
            boolean italic) {
        int s = canvas.scale;
        double top = y + 7 - glyph.ascent;
        double guiWidth = glyph.cellWidth * glyph.scale;
        double guiHeight = glyph.cellHeight * glyph.scale;
        int outWidth = (int) Math.round(guiWidth * s);
        int outHeight = (int) Math.round(guiHeight * s);
        int baseX = (int) Math.round(x * s);
        int baseY = (int) Math.round(top * s);
        double perOut = 1.0 / (glyph.scale * s);
        for (int oy = 0; oy < outHeight; oy++) {
            int sy = glyph.y + Math.min(glyph.cellHeight - 1, (int) (oy * perOut));
            int shift = 0;
            if (italic) {
                double rowGui = oy / (double) s;
                shift = (int) Math.round((1.0 - 0.25 * rowGui) * s);
            }
            for (int ox = 0; ox < outWidth; ox++) {
                int sx = glyph.x + Math.min(glyph.cellWidth - 1, (int) (ox * perOut));
                int alpha = (glyph.sheet.getRGB(sx, sy) >>> 24) & 0xFF;
                if (alpha == 0) {
                    continue;
                }
                int a = (((argb >>> 24) & 0xFF) * alpha) / 255;
                canvas.blend(baseX + ox + shift, baseY + oy, (a << 24) | (argb & 0xFFFFFF));
            }
        }
    }

    /** The built-in 5x7 ASCII set, used only where no real glyph exists. */
    static final class Builtin {

        /** Column bytes, least significant bit at the top, for ' ' through '~'. */
        private static final int[] COLUMNS = {
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x5F, 0x00, 0x00,
            0x00, 0x07, 0x00, 0x07, 0x00, 0x14, 0x7F, 0x14, 0x7F, 0x14,
            0x24, 0x2A, 0x7F, 0x2A, 0x12, 0x23, 0x13, 0x08, 0x64, 0x62,
            0x36, 0x49, 0x55, 0x22, 0x50, 0x00, 0x05, 0x03, 0x00, 0x00,
            0x00, 0x1C, 0x22, 0x41, 0x00, 0x00, 0x41, 0x22, 0x1C, 0x00,
            0x08, 0x2A, 0x1C, 0x2A, 0x08, 0x08, 0x08, 0x3E, 0x08, 0x08,
            0x00, 0x50, 0x30, 0x00, 0x00, 0x08, 0x08, 0x08, 0x08, 0x08,
            0x00, 0x60, 0x60, 0x00, 0x00, 0x20, 0x10, 0x08, 0x04, 0x02,
            0x3E, 0x51, 0x49, 0x45, 0x3E, 0x00, 0x42, 0x7F, 0x40, 0x00,
            0x42, 0x61, 0x51, 0x49, 0x46, 0x21, 0x41, 0x45, 0x4B, 0x31,
            0x18, 0x14, 0x12, 0x7F, 0x10, 0x27, 0x45, 0x45, 0x45, 0x39,
            0x3C, 0x4A, 0x49, 0x49, 0x30, 0x01, 0x71, 0x09, 0x05, 0x03,
            0x36, 0x49, 0x49, 0x49, 0x36, 0x06, 0x49, 0x49, 0x29, 0x1E,
            0x00, 0x36, 0x36, 0x00, 0x00, 0x00, 0x56, 0x36, 0x00, 0x00,
            0x08, 0x14, 0x22, 0x41, 0x00, 0x14, 0x14, 0x14, 0x14, 0x14,
            0x00, 0x41, 0x22, 0x14, 0x08, 0x02, 0x01, 0x51, 0x09, 0x06,
            0x32, 0x49, 0x79, 0x41, 0x3E, 0x7E, 0x11, 0x11, 0x11, 0x7E,
            0x7F, 0x49, 0x49, 0x49, 0x36, 0x3E, 0x41, 0x41, 0x41, 0x22,
            0x7F, 0x41, 0x41, 0x22, 0x1C, 0x7F, 0x49, 0x49, 0x49, 0x41,
            0x7F, 0x09, 0x09, 0x09, 0x01, 0x3E, 0x41, 0x49, 0x49, 0x7A,
            0x7F, 0x08, 0x08, 0x08, 0x7F, 0x00, 0x41, 0x7F, 0x41, 0x00,
            0x20, 0x40, 0x41, 0x3F, 0x01, 0x7F, 0x08, 0x14, 0x22, 0x41,
            0x7F, 0x40, 0x40, 0x40, 0x40, 0x7F, 0x02, 0x0C, 0x02, 0x7F,
            0x7F, 0x04, 0x08, 0x10, 0x7F, 0x3E, 0x41, 0x41, 0x41, 0x3E,
            0x7F, 0x09, 0x09, 0x09, 0x06, 0x3E, 0x41, 0x51, 0x21, 0x5E,
            0x7F, 0x09, 0x19, 0x29, 0x46, 0x46, 0x49, 0x49, 0x49, 0x31,
            0x01, 0x01, 0x7F, 0x01, 0x01, 0x3F, 0x40, 0x40, 0x40, 0x3F,
            0x1F, 0x20, 0x40, 0x20, 0x1F, 0x3F, 0x40, 0x38, 0x40, 0x3F,
            0x63, 0x14, 0x08, 0x14, 0x63, 0x07, 0x08, 0x70, 0x08, 0x07,
            0x61, 0x51, 0x49, 0x45, 0x43, 0x00, 0x7F, 0x41, 0x41, 0x00,
            0x02, 0x04, 0x08, 0x10, 0x20, 0x00, 0x41, 0x41, 0x7F, 0x00,
            0x04, 0x02, 0x01, 0x02, 0x04, 0x40, 0x40, 0x40, 0x40, 0x40,
            0x00, 0x01, 0x02, 0x04, 0x00, 0x20, 0x54, 0x54, 0x54, 0x78,
            0x7F, 0x48, 0x44, 0x44, 0x38, 0x38, 0x44, 0x44, 0x44, 0x20,
            0x38, 0x44, 0x44, 0x48, 0x7F, 0x38, 0x54, 0x54, 0x54, 0x18,
            0x08, 0x7E, 0x09, 0x01, 0x02, 0x0C, 0x52, 0x52, 0x52, 0x3E,
            0x7F, 0x08, 0x04, 0x04, 0x78, 0x00, 0x44, 0x7D, 0x40, 0x00,
            0x20, 0x40, 0x44, 0x3D, 0x00, 0x7F, 0x10, 0x28, 0x44, 0x00,
            0x00, 0x41, 0x7F, 0x40, 0x00, 0x7C, 0x04, 0x18, 0x04, 0x78,
            0x7C, 0x08, 0x04, 0x04, 0x78, 0x38, 0x44, 0x44, 0x44, 0x38,
            0x7C, 0x14, 0x14, 0x14, 0x08, 0x08, 0x14, 0x14, 0x18, 0x7C,
            0x7C, 0x08, 0x04, 0x04, 0x08, 0x48, 0x54, 0x54, 0x54, 0x20,
            0x04, 0x3F, 0x44, 0x40, 0x20, 0x3C, 0x40, 0x40, 0x20, 0x7C,
            0x1C, 0x20, 0x40, 0x20, 0x1C, 0x3C, 0x40, 0x30, 0x40, 0x3C,
            0x44, 0x28, 0x10, 0x28, 0x44, 0x0C, 0x50, 0x50, 0x50, 0x3C,
            0x44, 0x64, 0x54, 0x4C, 0x44, 0x00, 0x08, 0x36, 0x41, 0x00,
            0x00, 0x00, 0x7F, 0x00, 0x00, 0x00, 0x41, 0x36, 0x08, 0x00,
            0x08, 0x04, 0x08, 0x10, 0x08,
        };

        private static volatile BufferedImage sheet;

        private Builtin() {
        }

        static Glyph glyph(int cp) {
            if (cp < 32 || cp > 126) {
                return null;
            }
            if (cp == ' ') {
                return new Glyph(null, 0, 0, 0, 0, 1, 0, 4);
            }
            BufferedImage image = sheet();
            int index = cp - 32;
            int width = 0;
            for (int c = 4; c >= 0; c--) {
                if (COLUMNS[index * 5 + c] != 0) {
                    width = c + 1;
                    break;
                }
            }
            // Cell 6 wide, 8 tall; the 7 dot rows sit on the line like ASCII (ascent 7).
            return new Glyph(image, index * 6, 0, 6, 8, 1.0, 7, width + 1);
        }

        private static BufferedImage sheet() {
            BufferedImage built = sheet;
            if (built != null) {
                return built;
            }
            int count = COLUMNS.length / 5;
            built = new BufferedImage(count * 6, 8, BufferedImage.TYPE_INT_ARGB);
            for (int g = 0; g < count; g++) {
                for (int c = 0; c < 5; c++) {
                    int bits = COLUMNS[g * 5 + c];
                    for (int r = 0; r < 7; r++) {
                        if ((bits & (1 << r)) != 0) {
                            built.setRGB(g * 6 + c, r, 0xFFFFFFFF);
                        }
                    }
                }
            }
            sheet = built;
            return built;
        }
    }
}
