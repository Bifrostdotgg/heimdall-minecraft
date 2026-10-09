package com.heimdall.core.items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.testing.ItemCaptures;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class HoverTagsTest {

    @Nested
    class Tokenizer {

        @Test
        void splitsOnColonsAndStopsAtTheClosingBracket() {
            String text = "<hover:show_item:stone:5>rest";
            HoverTags.Tag tag = HoverTags.tokenize(text, 1);
            assertNotNull(tag);
            assertEquals(Arrays.asList("hover", "show_item", "stone", "5"), tag.args);
            assertEquals(text.indexOf('>') + 1, tag.end);
        }

        @Test
        void quotedArgumentsMayHoldColonsAndBrackets() {
            HoverTags.Tag tag = HoverTags.tokenize(
                    "<a:'x:y>z':\"p'q\":plain>", 1);
            assertNotNull(tag);
            assertEquals(Arrays.asList("a", "x:y>z", "p'q", "plain"), tag.args);
        }

        @Test
        void backslashEscapesTheActiveQuoteAndItself() {
            HoverTags.Tag tag = HoverTags.tokenize("<a:'it\\'s':\"say \\\"hi\\\"\":'a\\\\b':'k\\\"'>", 1);
            assertNotNull(tag);
            // \" inside '...' is not the active quote, so it survives for the SNBT reader.
            assertEquals(Arrays.asList("a", "it's", "say \"hi\"", "a\\b", "k\\\""), tag.args);
        }

        @Test
        void bothCapturesTokenizeIntoTheirComponentPairs() {
            String spoon = ItemCaptures.spoon();
            HoverTags.Tag tag = HoverTags.tokenize(spoon, 1);
            assertNotNull(tag);
            assertEquals(Arrays.asList("hover", "show_item", "netherite_shovel", "1",
                    "unbreakable", "{}",
                    "enchantments", "{\"minecraft:efficiency\":5,\"minecraft:silk_touch\":1,"
                            + "\"vane_enchantments:unbreakable\":1}",
                    "repair_cost", "7",
                    "custom_name", "\"Spoon\"",
                    "tooltip_display", "{hidden_components:[\"minecraft:unbreakable\"]}"),
                    tag.args);
            assertEquals("<gray>[<white>Spoon<gray>]", spoon.substring(tag.end));

            String jar = ItemCaptures.wardedJar();
            HoverTags.Tag jarTag = HoverTags.tokenize(jar, 1);
            assertNotNull(jarTag);
            assertEquals(4 + 2 * 9, jarTag.args.size());
            assertEquals("<gray>[<white><white>Warded Jar<gray>]", jar.substring(jarTag.end));
        }

        @Test
        void malformedTagsDoNotTokenize() {
            assertNull(HoverTags.tokenize("<hover:show_item:stone", 1), "no closing bracket");
            assertNull(HoverTags.tokenize("<hover:show_item:'open>", 1), "unterminated quote");
            assertNull(HoverTags.tokenize("<hover:'a'b>", 1), "text after a closing quote");
            assertNull(HoverTags.tokenize("<hover:show<item>", 1), "bare < inside a tag");
        }
    }

    @Nested
    class Rewrite {

        @Test
        void theSpoonCaptureBecomesItsName() {
            HoverTags.Rewrite rewrite = HoverTags.rewrite(ItemCaptures.spoon(), ItemTranslations.NONE);
            assertNotNull(rewrite);
            assertEquals("[Spoon]", rewrite.text());
            assertEquals(Arrays.asList("Spoon"), rewrite.names());
            assertEquals("minecraft:netherite_shovel", rewrite.items().get(0).id());
        }

        @Test
        void theWardedJarCaptureBecomesItsName() {
            HoverTags.Rewrite rewrite =
                    HoverTags.rewrite(ItemCaptures.wardedJar(), ItemTranslations.NONE);
            assertNotNull(rewrite);
            assertEquals("[Warded Jar]", rewrite.text());
            assertEquals(Arrays.asList("Warded Jar"), rewrite.names());
        }

        @Test
        void surroundingTextIsKeptByteForByte() {
            String before = "<white>look at this  \\<b>not a tag</b> ";
            String after = " and that is all ";
            String line = before + ItemCaptures.spoon() + "</hover>" + after;
            HoverTags.Rewrite rewrite = HoverTags.rewrite(line, ItemTranslations.NONE);
            assertNotNull(rewrite);
            assertEquals(before + "[Spoon]" + after, rewrite.text());
        }

        @Test
        void severalTagsInOneLineAreEachReplaced() {
            String line = "trade " + ItemCaptures.spoon() + "</hover> for "
                    + ItemCaptures.wardedJar();
            HoverTags.Rewrite rewrite = HoverTags.rewrite(line, ItemTranslations.NONE);
            assertNotNull(rewrite);
            assertEquals("trade [Spoon] for [Warded Jar]", rewrite.text());
            assertEquals(2, rewrite.items().size());
        }

        @Test
        void aHoverWithoutAClosingTagStopsWhereTheNextHoverStarts() {
            String line = "<hover:show_item:stone:1>[Stone]<hover:show_item:dirt:2>[Dirt]";
            HoverTags.Rewrite rewrite = HoverTags.rewrite(line, ItemTranslations.NONE);
            assertNotNull(rewrite);
            assertEquals("[Stone][Dirt]", rewrite.text());
            assertEquals(2, rewrite.items().get(1).count());
        }

        @Test
        void bareFormsAreItems() {
            List<HoverTags.Found> found = HoverTags.scan(
                    "<hover:show_item:stone>x</hover> <hover:show_item:stone:5>y",
                    ItemTranslations.NONE);
            assertEquals(2, found.size());
            assertEquals("minecraft:stone", found.get(0).item().id());
            assertEquals(1, found.get(0).item().count());
            assertEquals(5, found.get(1).item().count());
        }

        @Test
        void aDefaultNameUsesTranslationsWhenKnownAndTheIdOtherwise() {
            String line = "<hover:show_item:netherite_shovel:1>[x]";
            assertEquals("[Netherite Shovel]",
                    HoverTags.rewrite(line, ItemTranslations.NONE).text());
            ItemTranslations english = new ItemTranslations() {
                @Override
                public String translate(String key) {
                    return "item.minecraft.netherite_shovel".equals(key) ? "Netherite Shovel!" : null;
                }
            };
            assertEquals("[Netherite Shovel!]", HoverTags.rewrite(line, english).text());
        }

        @Test
        void aLineWithoutItemsIsNotRewritten() {
            assertNull(HoverTags.rewrite("just chatting about show_item", ItemTranslations.NONE));
            assertNull(HoverTags.rewrite("<hover:show_text:'hi'>hello", ItemTranslations.NONE));
            assertFalse(HoverTags.mightContainItem("hello"));
            assertTrue(HoverTags.mightContainItem("<HOVER:SHOW_ITEM:stone>"));
        }

        @Test
        void malformedInputReturnsNoItemAndLeavesTheTextAsIs() {
            String[] malformed = {
                "<hover:show_item:stone",
                "<hover:show_item:'stone>[x]",
                "<hover:show_item:>[x]",
                "<hover:show_item:Not An Id!>[x]",
                "<hover:show_item:stone:1:custom_name>[x]",
                "<hover:show_item:stone:1:'{unterminated'>[x]",
                "\\<hover:show_item:stone>[escaped]",
            };
            for (String line : malformed) {
                assertNull(HoverTags.rewrite(line, ItemTranslations.NONE), line);
                assertTrue(HoverTags.scan(line, ItemTranslations.NONE).isEmpty(), line);
            }
        }

        @Test
        void aMalformedTagNextToAGoodOneOnlyCostsItself() {
            String line = "<hover:show_item:'open>bad " + ItemCaptures.spoon();
            HoverTags.Rewrite rewrite = HoverTags.rewrite(line, ItemTranslations.NONE);
            assertNotNull(rewrite);
            assertEquals("<hover:show_item:'open>bad [Spoon]", rewrite.text());
        }

        @Test
        void legacyHolderFormIsAnItem() {
            String line = "<hover:show_item:diamond_sword:1:'{Damage:5,display:{Name:\\'"
                    + "{\"text\":\"Blade\",\"color\":\"red\"}\\'}}'>[Blade]</hover>!";
            HoverTags.Rewrite rewrite = HoverTags.rewrite(line, ItemTranslations.NONE);
            assertNotNull(rewrite);
            assertEquals("[Blade]!", rewrite.text());
            ChatItem item = rewrite.items().get(0);
            assertEquals(Integer.valueOf(5), item.damage());
            assertEquals(Integer.valueOf(0xFF5555),
                    item.customName().spans().get(0).color());
        }

        @Test
        void toStringNeverCarriesTheText() {
            HoverTags.Rewrite rewrite = HoverTags.rewrite(ItemCaptures.spoon(), ItemTranslations.NONE);
            assertFalse(rewrite.toString().contains("Spoon"));
            assertFalse(rewrite.items().get(0).toString().contains("Spoon"));
        }
    }
}
