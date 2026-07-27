package com.heimdall.core.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The defaulting reader: never throws on bad data, and can still tell absent from zero. */
class PayloadTest {

    @Test
    @DisplayName("accessors fall back rather than throwing on absent, null or wrong-typed values")
    void accessorsNeverThrow() {
        Payload payload = Payload.parse("{\"name\":\"Steve\",\"nothing\":null,\"wrong\":{}}");

        assertEquals("Steve", payload.string("name", "fallback"));
        assertEquals("fallback", payload.string("missing", "fallback"));
        assertEquals("fallback", payload.string("nothing", "fallback"));
        assertEquals("fallback", payload.string("wrong", "fallback"));
        assertEquals(7, payload.intValue("missing", 7));
        assertEquals(7L, payload.longValue("wrong", 7L));
        assertEquals(1.5, payload.doubleValue("nothing", 1.5), 0.0001);
        assertTrue(payload.bool("missing", true));
    }

    @Test
    @DisplayName("optInt tells absent from zero — the queuePosition case")
    void optionalAccessorsDistinguishAbsentFromZero() {
        Payload present = Payload.parse("{\"queuePosition\":0}");
        Payload absent = Payload.parse("{}");

        assertEquals(Integer.valueOf(0), present.optInt("queuePosition"));
        assertNull(absent.optInt("queuePosition"),
                "a fallback-shaped API cannot express this, and the scheduled auto-whitelist branch "
                        + "omits the key entirely — reading it as 0 shows players a queue position "
                        + "that does not exist");
        assertEquals(0, absent.intValue("queuePosition", 0));
    }

    @Test
    @DisplayName("an explicit null is not the same as an absent key")
    void explicitNullIsDistinguishable() {
        Payload payload = Payload.parse("{\"roleSync\":null}");

        assertTrue(payload.has("roleSync"));
        assertTrue(payload.isExplicitNull("roleSync"));
        assertFalse(payload.has("absent"));
        assertFalse(payload.isExplicitNull("absent"));
    }

    @Test
    @DisplayName("string arrays skip unusable elements rather than failing the whole list")
    void stringArraysAreTolerant() {
        Payload payload = Payload.parse("{\"groups\":[\"vip\",null,{},\"member\"]}");

        assertEquals(Arrays.asList("vip", "member"), payload.strings("groups"));
        assertEquals(Collections.emptyList(), payload.strings("missing"));
        assertEquals(Collections.emptyList(), payload.strings("groups2"));
    }

    @Test
    void nestedObjectsAndArraysOfObjects() {
        Payload payload = Payload.parse(
                "{\"modules\":{\"whitelist\":{\"enabled\":true}},\"lines\":[{\"msg\":\"a\"},1,{\"msg\":\"b\"}]}");

        assertTrue(payload.child("modules").child("whitelist").bool("enabled", false));
        assertTrue(payload.child("missing").isEmpty());

        List<Payload> lines = payload.children("lines");
        assertEquals(2, lines.size());
        assertEquals("a", lines.get(0).string("msg", null));
        assertEquals("b", lines.get(1).string("msg", null));
    }

    @Test
    @DisplayName("unparseable, non-object and empty input all yield an empty payload")
    void parseIsTotal() {
        assertSame(Payload.empty(), Payload.parse(null));
        assertSame(Payload.empty(), Payload.parse(""));
        assertSame(Payload.empty(), Payload.parse("not json"));
        assertSame(Payload.empty(), Payload.parse("[1,2,3]"));
        assertSame(Payload.empty(), Payload.parse("null"));
    }

    @Test
    @DisplayName("a built payload shares no structure with its builder")
    void builtPayloadsAreDetachedFromTheirBuilder() {
        Payload.Builder builder = Payload.builder().put("a", 1);
        Payload first = builder.build();
        builder.put("b", 2);
        Payload second = builder.build();

        assertNull(first.optInt("b"));
        assertEquals(Integer.valueOf(2), second.optInt("b"));
    }

    @Test
    @DisplayName("a nested payload is copied in, not aliased")
    void nestedPayloadsAreCopied() {
        Payload.Builder inner = Payload.builder().put("enabled", true);
        Payload outer = Payload.builder().put("whitelist", inner.build()).build();
        inner.put("enabled", false);

        assertTrue(outer.child("whitelist").bool("enabled", false));
    }

    @Test
    @DisplayName("putAll overwrites colliding keys, which is what lets named fields win over extras")
    void putAllOverwrites() {
        Payload extras = Payload.builder().put("platform", "wrong").put("custom", "kept").build();
        Payload merged = Payload.builder().putAll(extras).put("platform", "bukkit").build();

        assertEquals("bukkit", merged.string("platform", null));
        assertEquals("kept", merged.string("custom", null));
    }

    @Test
    @DisplayName("value semantics, so change detection is a comparison rather than an identity check")
    void valueSemantics() {
        Payload a = Payload.builder().put("x", 1).build();
        Payload b = Payload.parse("{\"x\":1}");
        Payload c = Payload.builder().put("x", 2).build();

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode(),
                "Gson's own JsonPrimitive.hashCode branches on the Number subclass while its equals "
                        + "compares by value, so a parsed 1 and a built 1 are equal and hash "
                        + "differently — the exact contract violation that makes a HashMap lose "
                        + "entries");
        assertNotEquals(a, c);
    }

    @Test
    @DisplayName("hashCode is independent of key order, as equals is")
    void hashCodeIgnoresKeyOrder() {
        Payload a = Payload.builder().put("x", 1).put("y", "two").build();
        Payload b = Payload.builder().put("y", "two").put("x", 1).build();

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    @DisplayName("nested and array values hash consistently across wire and builder")
    void nestedValuesHashConsistently() {
        Payload built = Payload.builder()
                .put("modules", Payload.builder().put("enabled", true).put("window", 60).build())
                .putStrings("groups", Arrays.asList("vip", "member"))
                .build();
        Payload parsed = Payload.parse(built.toJson());

        assertEquals(built, parsed);
        assertEquals(built.hashCode(), parsed.hashCode());
    }

    @Test
    void keysPreserveInsertionOrder() {
        Payload payload = Payload.builder().put("z", 1).put("a", 2).put("m", 3).build();
        assertEquals(Arrays.asList("z", "a", "m"), new java.util.ArrayList<String>(payload.keys()));
    }

    @Test
    @DisplayName("an explicit null round-trips as null rather than being dropped")
    void explicitNullsSurviveSerialisation() {
        Payload payload = Payload.builder().putNull("uuid").build();

        assertTrue(payload.toJson().contains("\"uuid\":null"));
        assertTrue(Payload.parse(payload.toJson()).isExplicitNull("uuid"));
    }
}
