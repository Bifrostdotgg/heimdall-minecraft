package com.heimdall.core.items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.testing.ItemCaptures;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The item model, built from Adam's two captures and from the older shapes. */
class ShowItemTest {

    private static final int GRAY = 0xAAAAAA;
    private static final int DARK_GRAY = 0x555555;
    private static final int WHITE = 0xFFFFFF;

    private static ChatItem only(String line) {
        List<HoverTags.Found> found = HoverTags.scan(line, ItemTranslations.NONE);
        assertEquals(1, found.size());
        return found.get(0).item();
    }

    @Test
    void spoon() {
        ChatItem spoon = only(ItemCaptures.spoon());
        assertEquals("minecraft:netherite_shovel", spoon.id());
        assertEquals(1, spoon.count());
        assertEquals("Spoon", spoon.displayName(ItemTranslations.NONE));
        assertTrue(spoon.hasCustomName());
        // An unstyled custom name: colour and italic are left to the renderer (rarity, italic).
        ItemText.Span name = spoon.customName().spans().get(0);
        assertNull(name.color());
        assertNull(name.italic());

        Map<String, Integer> expected = new LinkedHashMap<String, Integer>();
        expected.put("minecraft:efficiency", 5);
        expected.put("minecraft:silk_touch", 1);
        expected.put("vane_enchantments:unbreakable", 1);
        assertEquals(expected, spoon.enchantments());
        assertEquals(Arrays.asList("minecraft:efficiency", "minecraft:silk_touch",
                "vane_enchantments:unbreakable"),
                Arrays.asList(spoon.enchantments().keySet().toArray()));

        assertTrue(spoon.unbreakable());
        assertTrue(spoon.isHidden("unbreakable"));
        assertTrue(spoon.isHidden("minecraft:unbreakable"));
        assertFalse(spoon.isHidden("enchantments"));
        assertFalse(spoon.hideTooltip());
        assertNull(spoon.damage());
        assertNull(spoon.maxDamage());
        assertTrue(spoon.lore().isEmpty());
        assertTrue(spoon.customModelData().isEmpty());
    }

    @Test
    void wardedJar() {
        ChatItem jar = only(ItemCaptures.wardedJar());
        assertEquals("minecraft:heart_of_the_sea", jar.id());
        assertEquals("Warded Jar", jar.displayName(ItemTranslations.NONE));
        ItemText.Span name = jar.customName().spans().get(0);
        assertEquals(Integer.valueOf(WHITE), name.color());
        assertEquals(Boolean.FALSE, name.italic());
        assertEquals("Warded Jar", jar.itemName().plain());

        List<ItemText> lore = jar.lore();
        assertEquals(4, lore.size());
        assertEquals("Throw it at a mob to catch it,", lore.get(0).plain());
        assertEquals("then throw it again to let it out.", lore.get(1).plain());
        assertEquals("Every throw wears it down.", lore.get(2).plain());
        assertEquals("Repair it with echo shards on an anvil.", lore.get(3).plain());
        int[] colours = {GRAY, GRAY, DARK_GRAY, DARK_GRAY};
        for (int i = 0; i < 4; i++) {
            ItemText.Span span = lore.get(i).spans().get(0);
            assertEquals(Integer.valueOf(colours[i]), span.color(), "lore line " + i);
            assertEquals(Boolean.FALSE, span.italic(), "lore line " + i);
        }

        // 12 / 64: max damage 64, damage 52.
        assertEquals(Integer.valueOf(64), jar.maxDamage());
        assertEquals(Integer.valueOf(52), jar.damage());
        assertEquals(12, jar.maxDamage() - jar.damage());

        assertEquals(Arrays.asList(10002.0f), jar.customModelData().floats());
        assertEquals(Float.valueOf(10002.0f), jar.customModelData().floatAt(0));
        assertTrue(jar.enchantments().isEmpty());
        assertFalse(jar.unbreakable());
    }

    @Test
    void pre1215ComponentShapes() {
        ChatItem item = ShowItem.parse(Arrays.asList(
                "diamond_sword", "1",
                "custom_name", "'{\"text\":\"Old\",\"italic\":false}'",
                "lore", "['{\"text\":\"line\",\"color\":\"gold\"}']",
                "enchantments", "{levels:{\"minecraft:sharpness\":3},show_in_tooltip:0b}",
                "unbreakable", "{show_in_tooltip:false}",
                "custom_model_data", "7",
                "dyed_color", "{rgb:16711680,show_in_tooltip:1b}"), ItemTranslations.NONE);
        assertNotNull(item);
        assertEquals("Old", item.customName().plain());
        assertEquals(Boolean.FALSE, item.customName().spans().get(0).italic());
        assertEquals(Integer.valueOf(0xFFAA00), item.lore().get(0).spans().get(0).color());
        assertEquals(Integer.valueOf(3), item.enchantments().get("minecraft:sharpness"));
        assertTrue(item.isHidden("enchantments"));
        assertTrue(item.isHidden("unbreakable"));
        assertEquals(Arrays.asList(7.0f), item.customModelData().floats());
        assertEquals(Integer.valueOf(0xFF0000), item.dyedColor());
    }

    @Test
    void legacyNbtHolderWithHideFlagsAndNumericEnchantments() {
        ChatItem item = ShowItem.parse(Arrays.asList("diamond_pickaxe", "1",
                "{display:{Name:\"§bShiny\",Lore:[\"§5§oold lore\"]},"
                        + "ench:[{id:32s,lvl:5s},{id:34s,lvl:3s}],HideFlags:4,Unbreakable:1b,"
                        + "CustomModelData:12}"), ItemTranslations.NONE);
        assertNotNull(item);
        assertEquals("Shiny", item.customName().plain());
        assertEquals(Integer.valueOf(0x55FFFF), item.customName().spans().get(0).color());
        assertEquals(Boolean.TRUE, item.lore().get(0).spans().get(0).italic());
        assertEquals(Integer.valueOf(5), item.enchantments().get("minecraft:efficiency"));
        assertEquals(Integer.valueOf(3), item.enchantments().get("minecraft:unbreaking"));
        assertTrue(item.unbreakable());
        assertTrue(item.isHidden("unbreakable"));
        assertFalse(item.isHidden("enchantments"));
        assertEquals(Arrays.asList(12.0f), item.customModelData().floats());
    }

    @Test
    void wholeStackHolder() {
        ChatItem item = ShowItem.parse(Arrays.asList("iron_sword", "1",
                "{id:\"minecraft:iron_sword\",Count:1b,Damage:40s,tag:{display:{Name:"
                        + "'{\"text\":\"Rusty\"}'}}}"), ItemTranslations.NONE);
        assertNotNull(item);
        assertEquals(Integer.valueOf(40), item.damage());
        assertEquals("Rusty", item.customName().plain());
    }

    @Test
    void newerShapes() {
        ChatItem item = ShowItem.parse(Arrays.asList("paper", "3",
                "item_model", "'custom:ticket'",
                "rarity", "epic",
                "enchantment_glint_override", "true",
                "custom_model_data",
                        "{floats:[1.5f],flags:[1b,0b],strings:[\"gold\"],colors:[I;255]}",
                "tooltip_display", "{hide_tooltip:1b}",
                "!damage", "{}"), ItemTranslations.NONE);
        assertNotNull(item);
        assertEquals(3, item.count());
        assertEquals("custom:ticket", item.itemModel());
        assertEquals("epic", item.rarity());
        assertEquals(Boolean.TRUE, item.glintOverride());
        assertTrue(item.customModelData().flagAt(0));
        assertFalse(item.customModelData().flagAt(1));
        assertFalse(item.customModelData().flagAt(5));
        assertEquals("gold", item.customModelData().stringAt(0));
        assertEquals(Integer.valueOf(255), item.customModelData().colorAt(0));
        assertTrue(item.hideTooltip());
        assertNull(item.damage());
    }

    @Test
    void aBadComponentValueCostsOnlyThatComponent() {
        ChatItem item = ShowItem.parse(Arrays.asList("stone", "1",
                "lore", "[{unterminated",
                "custom_name", "'\"Kept\"'"), ItemTranslations.NONE);
        assertNotNull(item);
        assertEquals("Kept", item.displayName(ItemTranslations.NONE));
        assertTrue(item.lore().isEmpty());
    }

    @Test
    void namespacedIds() {
        assertEquals("custom:jar",
                ShowItem.parse(Arrays.asList("custom:jar", "1"), ItemTranslations.NONE).id());
        assertEquals("minecraft:stone",
                ShowItem.parse(Arrays.asList("minecraft", "stone", "1"), ItemTranslations.NONE)
                        .id());
    }

    @Test
    void anOddPairCountIsNotAnItem() {
        assertNull(ShowItem.parse(Arrays.asList("stone", "1", "damage", "1", "lore"),
                ItemTranslations.NONE));
    }

    @Test
    void translatedLoreAndNames() {
        ItemTranslations english = new ItemTranslations() {
            @Override
            public String translate(String key) {
                if ("greeting".equals(key)) {
                    return "Hello %s, from %2$s";
                }
                return null;
            }
        };
        ChatItem item = ShowItem.parse(Arrays.asList("stone", "1",
                "lore", "[{translate:\"greeting\",with:[\"Adam\",{text:\"Bifrost\"}]},"
                        + "{translate:\"missing\",fallback:\"Fallback\"},{translate:\"raw.key\"}]"),
                english);
        assertNotNull(item);
        assertEquals("Hello Adam, from Bifrost", item.lore().get(0).plain());
        assertEquals("Fallback", item.lore().get(1).plain());
        assertEquals("raw.key", item.lore().get(2).plain());
    }

    @Test
    void listComponentsInheritTheHeadStyle() {
        ItemText text = TextComponents.read(
                Arrays.<Object>asList(
                        snbt("{text:\"A\",color:\"red\",bold:1b}"), "B",
                        snbt("{text:\"C\",color:\"blue\"}")),
                ItemTranslations.NONE);
        assertEquals("ABC", text.plain());
        assertEquals(Integer.valueOf(0xFF5555), text.spans().get(1).color());
        assertEquals(Boolean.TRUE, text.spans().get(1).bold());
        assertEquals(Integer.valueOf(0x5555FF), text.spans().get(2).color());
        assertEquals(Boolean.TRUE, text.spans().get(2).bold());
    }

    @Test
    void aBracketedPlainNameIsTextNotAList() {
        assertEquals("[Epic] Sword",
                TextComponents.read("[Epic] Sword", ItemTranslations.NONE).plain());
        assertEquals("{not json",
                TextComponents.read("{not json", ItemTranslations.NONE).plain());
    }

    @Test
    void hexColours() {
        ItemText text = TextComponents.read(snbt("{text:\"x\",color:\"#123456\"}"),
                ItemTranslations.NONE);
        assertEquals(Integer.valueOf(0x123456), text.spans().get(0).color());
    }

    private static Object snbt(String text) {
        try {
            return Snbt.parse(text);
        } catch (Snbt.SyntaxException e) {
            throw new AssertionError(e);
        }
    }
}
