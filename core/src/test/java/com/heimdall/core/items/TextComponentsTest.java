package com.heimdall.core.items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The bounds on reading a text component: depth, spans, line length, and the work budget. */
class TextComponentsTest {

    /**
     * A translation bomb: each level's fallback repeats its one argument eight times, and the
     * argument is the level below. Ten levels is 8^10 copies of "x" unbounded.
     */
    static String bomb(int levels) {
        String node = "\"x\"";
        for (int i = 0; i < levels; i++) {
            node = "{translate:\"bomb\",fallback:\"%1$s%1$s%1$s%1$s%1$s%1$s%1$s%1$s\",with:["
                    + node + "]}";
        }
        return node;
    }

    private static Object snbt(String text) {
        try {
            return Snbt.parse(text);
        } catch (Snbt.SyntaxException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void aNestedTranslationBombIsBoundedAndFast() {
        final Object bomb = snbt(bomb(10));
        ItemText text = assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> TextComponents.read(bomb, ItemTranslations.NONE));
        assertEquals(TextComponents.MAX_LINE_CHARS, text.plain().length(),
                "expanded only as far as one line can show");
    }

    @Test
    void theBombInAHoverRelaysAsABoundedName() {
        final String line = "<hover:show_item:stone:1:custom_name:'" + bomb(10) + "'>[x]";
        assertTrue(line.length() < 1024, "a small input: " + line.length());
        HoverTags.Rewrite rewrite = assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> HoverTags.rewrite(line, ItemTranslations.NONE));
        assertNotNull(rewrite);
        assertTrue(rewrite.names().get(0).length() <= TextComponents.MAX_LINE_CHARS);
    }

    @Test
    void manyWideArgumentsShareOneBudget() {
        StringBuilder with = new StringBuilder();
        StringBuilder wide = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            wide.append('w');
        }
        for (int i = 0; i < 16; i++) {
            with.append(i == 0 ? "" : ",").append('"').append(wide).append('"');
        }
        Object component = snbt("[{translate:\"k\",fallback:\"%s\",with:[" + with + "]},"
                + "{translate:\"k\",fallback:\"%2$s\",with:[" + with + "]},\"tail\"]");
        ItemText text = TextComponents.read(component, ItemTranslations.NONE);
        assertTrue(text.plain().length() <= TextComponents.MAX_LINE_CHARS);
    }

    @Test
    void nestingDeeperThanTheCapIsCutAtTheCap() {
        String node = "{text:\"a\"}";
        for (int i = 0; i < 40; i++) {
            node = "{text:\"a\",extra:[" + node + "]}";
        }
        ItemText text = TextComponents.read(snbt(node), ItemTranslations.NONE);
        assertEquals(TextComponents.MAX_DEPTH + 1, text.plain().length(),
                "depths 0 to " + TextComponents.MAX_DEPTH + " inclusive, nothing deeper");
    }

    @Test
    void spansAreCappedExactly() {
        List<Object> many = new ArrayList<Object>();
        for (int i = 0; i < TextComponents.MAX_SPANS + 50; i++) {
            many.add("a");
        }
        ItemText text = TextComponents.read(many, ItemTranslations.NONE);
        assertEquals(TextComponents.MAX_SPANS, text.spans().size());
    }

    @Test
    void aLineIsCappedExactly() {
        StringBuilder long1 = new StringBuilder();
        for (int i = 0; i < 3000; i++) {
            long1.append('z');
        }
        assertEquals(TextComponents.MAX_LINE_CHARS,
                TextComponents.read(long1.toString(), ItemTranslations.NONE).plain().length());
        assertEquals(TextComponents.MAX_LINE_CHARS, TextComponents.read(
                snbt("{text:\"" + long1 + "\"}"), ItemTranslations.NONE).plain().length());
        assertEquals(TextComponents.MAX_LINE_CHARS,
                TextComponents.legacy("§a" + long1).plain().length());
    }

    @Test
    void theNodeBudgetStopsAGraphThatStaysUnderTheOtherCaps() {
        // Each level's two arguments are the same level below: 30 levels is 2^30 visits, every one
        // under the depth and line caps. Only the per-read node budget stops it.
        Object node = "q";
        for (int i = 0; i < 30; i++) {
            java.util.Map<String, Object> level = new java.util.LinkedHashMap<String, Object>();
            level.put("translate", "k");
            level.put("fallback", "%1$s%2$s");
            level.put("with", java.util.Arrays.asList(node, node));
            node = level;
        }
        final Object deep = node;
        ItemText text = assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> TextComponents.read(deep, ItemTranslations.NONE));
        assertTrue(text.plain().length() <= TextComponents.MAX_LINE_CHARS);
    }

    @Test
    void aWideArgumentFanOutCannotEmptyTheName() {
        // Each argument is itself a fan-out of 16 wide arguments: together far more than the
        // argument pool, all for a translation whose format shows none of them.
        StringBuilder wide = new StringBuilder();
        for (int i = 0; i < TextComponents.MAX_LINE_CHARS; i++) {
            wide.append('w');
        }
        StringBuilder inner = new StringBuilder("{translate:\"i\",fallback:\"%s\",with:[");
        for (int i = 0; i < 16; i++) {
            inner.append(i == 0 ? "" : ",").append('"').append(wide).append('"');
        }
        inner.append("]}");
        StringBuilder outer = new StringBuilder("{translate:\"o\",fallback:\"\",with:[");
        for (int i = 0; i < 16; i++) {
            outer.append(i == 0 ? "" : ",").append(inner);
        }
        outer.append("]}");
        ItemText text = TextComponents.read(snbt("[" + outer + ",\"Spoon\"]"),
                ItemTranslations.NONE);
        assertEquals("Spoon", text.plain(), "the name survives the arguments' spending");
    }

    @Test
    void anAbsurdArgumentIndexIsText() {
        assertEquals("%99999999999$s", TextComponents.format("%99999999999$s",
                new ArrayList<String>()));
    }
}
