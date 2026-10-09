package com.heimdall.platform.bukkit.itemimage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.items.ChatItem;
import com.heimdall.core.items.HoverTags;
import com.heimdall.core.items.ItemTranslations;
import com.heimdall.core.testing.ItemCaptures;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The card: its lines, and real PNGs drawn headless. */
class CardRendererTest {

    @TempDir
    Path temp;

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    private static ChatItem spoon() {
        return HoverTags.scan(ItemCaptures.spoon(), ItemTranslations.NONE).get(0).item();
    }

    private static ChatItem wardedJar() {
        return HoverTags.scan(ItemCaptures.wardedJar(), ItemTranslations.NONE).get(0).item();
    }

    private static List<String> plain(List<CardRenderer.Line> lines) {
        List<String> out = new ArrayList<String>();
        for (CardRenderer.Line line : lines) {
            out.add(line.text.plain());
        }
        return out;
    }

    /** A server that knows a netherite shovel's durability, as Material would answer. */
    private static final ItemDefaults SERVER = new ItemDefaults() {
        @Override
        public int maxDurability(String id) {
            return "minecraft:netherite_shovel".equals(id) ? 2031 : 0;
        }

        @Override
        public String rarity(String id) {
            return "minecraft:heart_of_the_sea".equals(id) ? "uncommon" : "common";
        }
    };

    @Test
    void spoonLines() {
        ChatItem spoon = spoon();
        List<CardRenderer.Line> lines = CardRenderer.lines(spoon, ItemTranslations.NONE, SERVER,
                SERVER.maxDurability(spoon.id()));

        List<String> expected = new ArrayList<String>();
        expected.add("Spoon");
        expected.add("Efficiency V");
        expected.add("Silk Touch");
        expected.add("Unbreakable");
        assertEquals(expected, plain(lines),
                "no Unbreakable line (hidden), no durability (unbreakable), the custom "
                        + "enchantment humanised without a numeral");
        assertEquals(CardRenderer.RARE, lines.get(0).color, "common, raised by enchantments");
        assertTrue(lines.get(0).italic, "a custom name is italic");
        assertEquals(CardRenderer.GRAY, lines.get(1).color);
    }

    @Test
    void wardedJarLines() {
        ChatItem jar = wardedJar();
        List<CardRenderer.Line> lines = CardRenderer.lines(jar, ItemTranslations.NONE, SERVER, 64);

        assertEquals("Warded Jar", lines.get(0).text.plain());
        assertEquals(Integer.valueOf(0xFFFFFF), lines.get(0).text.spans().get(0).color());
        assertEquals(Boolean.FALSE, lines.get(0).text.spans().get(0).italic());
        assertEquals("Throw it at a mob to catch it,", lines.get(1).text.plain());
        assertEquals("Repair it with echo shards on an anvil.", lines.get(4).text.plain());
        assertEquals("Durability: 12 / 64", lines.get(5).text.plain());
        assertEquals(6, lines.size());
    }

    @Test
    void translationsAndLevels() {
        ItemTranslations english = new ItemTranslations() {
            @Override
            public String translate(String key) {
                if ("enchantment.minecraft.efficiency".equals(key)) {
                    return "Efficiency!";
                }
                return null;
            }
        };
        ChatItem item = ChatItem.builder("diamond_pickaxe").enchantment("efficiency", 1)
                .enchantment("binding_curse", 1).enchantment("custom:zap", 3).build();
        List<String> lines = plain(CardRenderer.lines(item, english, ItemDefaults.NONE, 0));
        assertEquals("Efficiency! I", lines.get(1));
        assertEquals("Binding Curse", lines.get(2), "single-level at I: no numeral");
        assertEquals(CardRenderer.RED,
                CardRenderer.lines(item, english, ItemDefaults.NONE, 0).get(2).color);
        assertEquals("Zap III", lines.get(3));
        assertEquals("XIV", CardRenderer.roman(14));
    }

    @Test
    void aHiddenTooltipIsTheIconAlone() throws Exception {
        ChatItem hidden = ChatItem.builder("stone").hideTooltip(true).build();
        CardRenderer renderer = new CardRenderer(Fixtures.stack());
        BufferedImage image = renderer.draw(hidden, ItemTranslations.NONE, ItemDefaults.NONE, 2,
                Deadline.none());
        assertEquals((CardRenderer.ICON + 2 * CardRenderer.MARGIN) * 2, image.getWidth());
        assertEquals(image.getWidth(), image.getHeight());
    }

    @Test
    void rendersAPlausiblePngWithNoAssetsAtAll() throws Exception {
        byte[] png = new CardRenderer(Fixtures.stack()).render(wardedJar(), ItemTranslations.NONE,
                SERVER, 2, Deadline.none());
        BufferedImage image = decode(png);
        assertTrue(image.getWidth() > 120 && image.getWidth() < 1500, "width " + image.getWidth());
        assertTrue(image.getHeight() > 60 && image.getHeight() < 600, "height " + image.getHeight());
        assertTrue(png.length < 64 * 1024, "a card is small: " + png.length);
    }

    @Test
    void rendersWithPackTexturesSpritesAndABlock() throws Exception {
        Path pack = Fixtures.write(temp.resolve("pack"), Fixtures.files(
                "assets/minecraft/models/item/generated.json", "{\"parent\":\"builtin/generated\"}",
                "assets/minecraft/items/heart_of_the_sea.json",
                        "{\"model\":{\"type\":\"model\",\"model\":\"item/jar\"}}",
                "assets/minecraft/models/item/jar.json",
                        "{\"parent\":\"item/generated\",\"textures\":{\"layer0\":\"item/jar\"}}",
                "assets/minecraft/textures/item/jar.png", Fixtures.png(16, 16, 0xFF3366CC),
                "assets/minecraft/items/stone.json", "{\"model\":{\"type\":\"model\",\"model\":\"block/stone\"}}",
                "assets/minecraft/models/block/stone.json",
                        "{\"textures\":{\"all\":\"block/stone\"},\"elements\":[{\"from\":[0,0,0],"
                        + "\"to\":[16,16,16],\"faces\":{\"up\":{\"texture\":\"#all\"},"
                        + "\"north\":{\"texture\":\"#all\"},\"east\":{\"texture\":\"#all\"},"
                        + "\"south\":{\"texture\":\"#all\"},\"west\":{\"texture\":\"#all\"},"
                        + "\"down\":{\"texture\":\"#all\"}}}],\"display\":{\"gui\":{"
                        + "\"rotation\":[30,225,0],\"scale\":[0.625,0.625,0.625]}}}",
                "assets/minecraft/textures/block/stone.png", Fixtures.png(16, 16, 0xFF7F7F7F),
                "assets/minecraft/textures/gui/sprites/tooltip/background.png",
                        Fixtures.png(100, 100, 0xF0100010),
                "assets/minecraft/textures/gui/sprites/tooltip/background.png.mcmeta",
                        "{\"gui\":{\"scaling\":{\"type\":\"nine_slice\",\"width\":100,\"height\":100,"
                        + "\"border\":9}}}",
                "assets/minecraft/textures/gui/sprites/tooltip/frame.png",
                        Fixtures.png(100, 100, 0x505000FF),
                "assets/minecraft/textures/gui/sprites/tooltip/frame.png.mcmeta",
                        "{\"gui\":{\"scaling\":{\"type\":\"nine_slice\",\"width\":100,\"height\":100,"
                        + "\"border\":{\"left\":10,\"top\":10,\"right\":10,\"bottom\":10},"
                        + "\"stretch_inner\":true}}}"));
        CardRenderer renderer = new CardRenderer(Fixtures.stack(pack));

        BufferedImage jar = renderer.draw(wardedJar(), ItemTranslations.NONE, SERVER, 2,
                Deadline.none());
        // The icon column carries the pack texture's exact colour: the definition, the model and
        // the texture were all read from the pack.
        int textured = 0;
        for (int y = 0; y < jar.getHeight(); y++) {
            for (int x = 0; x < (CardRenderer.MARGIN + CardRenderer.ICON) * 2; x++) {
                if (jar.getRGB(x, y) == 0xFF3366CC) {
                    textured++;
                }
            }
        }
        assertEquals(32 * 32, textured, "the whole 16x16 sprite, at scale 2");
        assertNotNull(decode(renderer.render(wardedJar(), ItemTranslations.NONE, SERVER, 2,
                Deadline.none())));

        BufferedImage stone = renderer.draw(ChatItem.builder("stone").enchantment("unbreaking", 1)
                .build(), ItemTranslations.NONE, SERVER, 2, Deadline.none());
        int opaque = 0;
        for (int y = 0; y < 34; y++) {
            for (int x = 0; x < 34; x++) {
                if (((stone.getRGB(x, y) >>> 24) & 0xFF) != 0) {
                    opaque++;
                }
            }
        }
        assertTrue(opaque > 300, "an isometric cube covers much of the icon: " + opaque);
    }

    @Test
    void anOverlongLineIsCutWithAnEllipsis() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            text.append("wide ");
        }
        CardRenderer renderer = new CardRenderer(Fixtures.stack());
        ChatItem item = ChatItem.builder("paper")
                .addLore(com.heimdall.core.items.ItemText.plain(text.toString())).build();
        BufferedImage image = renderer.draw(item, ItemTranslations.NONE, ItemDefaults.NONE, 1,
                Deadline.none());
        assertTrue(image.getWidth() < CardRenderer.MAX_LINE_WIDTH + 60,
                "capped near " + CardRenderer.MAX_LINE_WIDTH + ": " + image.getWidth());
    }

    @Test
    void theBuiltInFontCoversPrintableAscii() {
        for (int cp = 33; cp < 127; cp++) {
            BitmapFont.Glyph glyph = BitmapFont.Builtin.glyph(cp);
            assertNotNull(glyph, "glyph " + (char) cp);
            assertFalse(glyph.blank());
            assertTrue(glyph.advance >= 2 && glyph.advance <= 6, "advance of " + (char) cp);
        }
    }

    @Test
    void anExpiredDeadlineFailsTheRenderRatherThanFinishingIt() throws Exception {
        Deadline spent = Deadline.in(-1);
        CardRenderer renderer = new CardRenderer(Fixtures.stack());
        boolean threw = false;
        try {
            renderer.render(wardedJar(), ItemTranslations.NONE, SERVER, 2, spent);
        } catch (Deadline.Exceeded expected) {
            threw = true;
        }
        assertTrue(threw);
    }

    private static BufferedImage decode(byte[] png) throws Exception {
        assertEquals((byte) 0x89, png[0]);
        assertEquals('P', png[1]);
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        assertNotNull(image);
        return image;
    }
}
