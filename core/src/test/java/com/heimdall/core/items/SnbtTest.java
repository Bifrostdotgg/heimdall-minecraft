package com.heimdall.core.items;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SnbtTest {

    @Test
    void compoundsKeepSourceOrderAndAcceptQuotedAndBareKeys() throws Exception {
        Map<String, Object> map = Snbt.asMap(Snbt.parse(
                "{b:1,\"minecraft:a\":2,'c d':3, nested:{x:\"y\"}}"));
        assertEquals(Arrays.asList("b", "minecraft:a", "c d", "nested"),
                Arrays.asList(map.keySet().toArray()));
        assertEquals(2, map.get("minecraft:a"));
        assertEquals("y", Snbt.asMap(map.get("nested")).get("x"));
        assertTrue(Snbt.asMap(Snbt.parse("{}")).isEmpty());
    }

    @Test
    void listsAndTypedArrays() throws Exception {
        List<Object> list = Snbt.asList(Snbt.parse("[1, 2b, \"three\", {four:4}, [5]]"));
        assertEquals(5, list.size());
        assertEquals(1, list.get(0));
        assertEquals((byte) 2, list.get(1));
        assertEquals("three", list.get(2));
        assertArrayEquals(new byte[] {1, -2, 3}, (byte[]) Snbt.parse("[B;1b,-2b,3]"));
        assertArrayEquals(new int[] {1, 2, 3}, (int[]) Snbt.parse("[I; 1, 2, 3]"));
        assertArrayEquals(new long[] {7L, 9L}, (long[]) Snbt.parse("[L;7l,9L]"));
        assertArrayEquals(new int[0], (int[]) Snbt.parse("[I;]"));
        assertTrue(Snbt.asList(Snbt.parse("[]")).isEmpty());
    }

    @Test
    void numbersTakeTheirTypeFromTheSuffix() throws Exception {
        assertEquals((byte) 1, Snbt.parse("1b"));
        assertEquals((byte) 0, Snbt.parse("0B"));
        assertEquals((short) 300, Snbt.parse("300s"));
        assertEquals(42, Snbt.parse("42"));
        assertEquals(-42, Snbt.parse("-42"));
        assertEquals(5_000_000_000L, Snbt.parse("5000000000"));
        assertEquals(12L, Snbt.parse("12L"));
        assertEquals(10002.0f, Snbt.parse("10002.0f"));
        assertEquals(1.5f, Snbt.parse("1.5F"));
        assertEquals(2.5d, Snbt.parse("2.5"));
        assertEquals(3.0d, Snbt.parse("3d"));
        assertEquals(1.0e3d, Snbt.parse("1e3"));
        assertEquals(-0.5d, Snbt.parse("-.5"));
    }

    @Test
    void booleansAreBooleansAndBytesReadAsBooleansToo() throws Exception {
        assertEquals(Boolean.TRUE, Snbt.parse("true"));
        assertEquals(Boolean.FALSE, Snbt.parse("false"));
        assertEquals(Boolean.TRUE, Snbt.asBoolean(Snbt.parse("1b"), null));
        assertEquals(Boolean.FALSE, Snbt.asBoolean(Snbt.parse("0b"), null));
        assertEquals(null, Snbt.asBoolean("maybe", null));
    }

    @Test
    void quotedAndUnquotedStrings() throws Exception {
        assertEquals("plain", Snbt.parse("plain"));
        assertEquals("minecraft.thing-1", Snbt.parse("minecraft.thing-1"));
        assertEquals("it's", Snbt.parse("\"it's\""));
        assertEquals("say \"hi\"", Snbt.parse("'say \"hi\"'"));
        assertEquals("say \"hi\"", Snbt.parse("\"say \\\"hi\\\"\""));
        assertEquals("back\\slash", Snbt.parse("'back\\\\slash'"));
        assertEquals("line\nbreak \u00e9", Snbt.parse("\"line\\nbreak \\u00e9\""));
        // Not numbers, so strings: an id-shaped word with digits, and a lone sign.
        assertEquals("1e", Snbt.parse("1e"));
        assertEquals("-", Snbt.parse("-"));
    }

    @Test
    void jsonParsesThroughTheSameReader() throws Exception {
        Map<String, Object> json = Snbt.asMap(Snbt.parse(
                "{\n  \"text\": \"Spoon\",\n  \"italic\": false,\n  \"extra\": [ {\"text\": \"!\"} ]\n}"));
        assertEquals("Spoon", json.get("text"));
        assertEquals(Boolean.FALSE, json.get("italic"));
        assertEquals(1, Snbt.asList(json.get("extra")).size());
    }

    @Test
    void trailingCommasAreTolerated() throws Exception {
        assertEquals(2, Snbt.asMap(Snbt.parse("{a:1,b:2,}")).size());
        assertEquals(2, Snbt.asList(Snbt.parse("[1,2,]")).size());
    }

    @Test
    void malformedInputIsASyntaxError() {
        String[] bad = {
            "", "{", "{a}", "{a:1", "[1,2", "\"open", "{a:1 b:2}", "[I;1.5]", "1 2", "{:1}",
        };
        for (String input : bad) {
            assertThrows(Snbt.SyntaxException.class, () -> Snbt.parse(input), input);
        }
    }

    @Test
    void depthAndLengthAreBounded() {
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < Snbt.MAX_DEPTH + 5; i++) {
            deep.append('[');
        }
        assertThrows(Snbt.SyntaxException.class, () -> Snbt.parse(deep.toString()));
        assertThrows(Snbt.SyntaxException.class, () -> Snbt.parse("\"0123456789\"", 5));
    }

    @Test
    void syntaxErrorsNeverQuoteTheInput() {
        Snbt.SyntaxException failure = assertThrows(Snbt.SyntaxException.class,
                () -> Snbt.parse("{secret:\"unterminated"));
        assertFalse(failure.getMessage().contains("secret"));
    }
}
