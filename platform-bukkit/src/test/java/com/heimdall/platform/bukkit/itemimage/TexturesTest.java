package com.heimdall.platform.bukkit.itemimage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Texture bounds: pixel count, animation frames, downscaling, and the byte-bounded cache. */
class TexturesTest {

    @TempDir
    Path temp;

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    @Test
    void thePixelCapIsExact() {
        assertNotNull(Textures.decode(Fixtures.png(2048, 2048, 0xFF00FF00)),
                "exactly " + Textures.MAX_PIXELS + " pixels is allowed");
        assertNull(Textures.decode(Fixtures.png(2049, 2048, 0xFF00FF00)), "one column more is not");
        assertNull(Textures.decode(Fixtures.png(Textures.MAX_SIDE + 1, 4, 0xFF00FF00)),
                "a side over the cap is refused whatever the area");
    }

    @Test
    void theFirstAnimationFrameGetsItsOwnRaster() {
        BufferedImage strip = Textures.decode(Fixtures.png(16, 64, 0xFF123456));
        BufferedImage frame = Textures.firstFrame(strip, null);
        assertEquals(16, frame.getWidth());
        assertEquals(16, frame.getHeight());
        assertNull(frame.getRaster().getParent(), "a copy, not a view pinning the whole strip");

        BufferedImage meta = Textures.firstFrame(strip,
                Fixtures.utf8("{\"animation\":{\"width\":16,\"height\":8}}"));
        assertEquals(8, meta.getHeight());
    }

    @Test
    void modelTexturesAreShrunkToTheIconAndFontSheetsAreNot() {
        Path pack = Fixtures.write(temp.resolve("hd"), Fixtures.files(
                "assets/minecraft/textures/item/hd.png", Fixtures.png(128, 64, 0xFFABCDEF),
                "assets/minecraft/textures/font/sheet.png", Fixtures.png(256, 256, 0xFFFFFFFF)));
        Textures textures = new Textures(Fixtures.stack(pack));

        BufferedImage hd = textures.get("item/hd");
        assertEquals(Textures.MAX_MODEL_SIDE, hd.getWidth());
        assertEquals(Textures.MAX_MODEL_SIDE / 2, hd.getHeight(), "aspect kept");
        assertEquals(0xFFABCDEF, hd.getRGB(5, 5));

        assertEquals(256, textures.load("assets/minecraft/textures/font/sheet.png").getWidth());
    }

    @Test
    void theCacheIsBoundedInDecodedBytes() {
        java.util.Map<String, byte[]> files = new java.util.LinkedHashMap<String, byte[]>();
        for (int i = 0; i < 6; i++) {
            files.put("assets/minecraft/textures/font/big" + i + ".png",
                    Fixtures.png(2048, 1024, 0xFF000000 | i));
        }
        Textures textures = new Textures(Fixtures.stack(Fixtures.write(temp.resolve("big"), files)));
        for (int i = 0; i < 6; i++) {
            assertNotNull(textures.load("assets/minecraft/textures/font/big" + i + ".png"));
            assertTrue(textures.cachedBytes() <= Textures.MAX_CACHE_BYTES,
                    "after " + (i + 1) + ": " + textures.cachedBytes());
        }
        assertTrue(textures.cachedBytes() > 0);
    }

    @Test
    void manyFontSheetsStayInsideTheTextureBudget() {
        java.util.Map<String, byte[]> files = new java.util.LinkedHashMap<String, byte[]>();
        StringBuilder providers = new StringBuilder("{\"providers\":[");
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            int cp = 0xE000 + i;
            files.put("assets/minecraft/textures/font/gui" + i + ".png",
                    Fixtures.png(2048, 1024, 0xFFFFFFFF));
            providers.append(i == 0 ? "" : ",").append("{\"type\":\"bitmap\",\"file\":")
                    .append("\"minecraft:font/gui").append(i).append(".png\",\"ascent\":7,")
                    .append("\"height\":8,\"chars\":[\"").append(new String(Character.toChars(cp)))
                    .append("\"]}");
            line.appendCodePoint(cp);
        }
        files.put("assets/minecraft/font/default.json", Fixtures.utf8(providers + "]}"));
        PackStack stack = Fixtures.stack(Fixtures.write(temp.resolve("sheets"), files));
        Textures textures = new Textures(stack);
        BitmapFont font = BitmapFont.load(stack, textures);
        Canvas canvas = new Canvas(200, 12, 1);

        double x = 0;
        for (int i = 0; i < line.length(); i++) {
            x += font.draw(canvas, line.charAt(i), x, 0, 0xFFFFFFFF, false, false);
            assertTrue(textures.cachedBytes() <= Textures.MAX_CACHE_BYTES,
                    "after sheet " + i + ": " + textures.cachedBytes());
        }
        assertTrue(x > 0);
        for (int i = 0; i < line.length(); i++) {
            BitmapFont.Glyph glyph = font.glyph(line.charAt(i));
            assertNull(glyph.sheet, "a pack glyph pins no decoded sheet outside the cache");
            assertNotNull(glyph.path);
        }
    }

    @Test
    void charactersNoFontHasAreNotMemoised() {
        BitmapFont font = BitmapFont.load(Fixtures.stack(), new Textures(Fixtures.stack()));
        for (int cp = 0xE000; cp < 0xE000 + 5000; cp++) {
            font.glyph(cp);
        }
        assertTrue(font.memoisedGlyphs() < 200, "memoised: " + font.memoisedGlyphs());
    }

    @Test
    void fontSheetsAreDecodedOnFirstUseAndAFailureIsRemembered() {
        Path pack = Fixtures.write(temp.resolve("font"), Fixtures.files(
                "assets/minecraft/font/default.json",
                        "{\"providers\":[{\"type\":\"bitmap\",\"file\":\"minecraft:font/ok.png\","
                        + "\"ascent\":7,\"chars\":[\"AB\"]},{\"type\":\"bitmap\","
                        + "\"file\":\"minecraft:font/missing.png\",\"ascent\":7,\"chars\":[\"CD\"]}]}",
                "assets/minecraft/textures/font/ok.png", Fixtures.png(16, 8, 0xFFFFFFFF)));
        Textures textures = new Textures(Fixtures.stack(pack));
        BitmapFont font = BitmapFont.load(Fixtures.stack(pack), textures);

        assertTrue(font.fromAssets());
        assertEquals(0, textures.cachedBytes(), "indexing decodes nothing");
        BitmapFont.Glyph a = font.glyph('A');
        assertFalse(a.blank());
        assertEquals(9.0, a.advance, 0.0, "an 8-wide solid cell: width 8, plus one");
        assertTrue(textures.cachedBytes() > 0, "decoded on first use");

        BitmapFont.Glyph c = font.glyph('C');
        assertEquals(BitmapFont.Builtin.glyph('C').advance, c.advance, 0.0,
                "a sheet that will not load falls back to the built-in glyph");
        assertEquals(c.advance, font.glyph('D').advance, 0.0);
    }
}
