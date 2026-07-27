package com.heimdall.core.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
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
    @DisplayName("equals is TRANSITIVE across the parser and the builder, for 64-bit ids")
    void equalsIsTransitiveForLargeIntegers() {
        // Gson's own JsonPrimitive.equals is not. It compares two numbers as integers only when
        // BOTH are Long/Integer/Short/Byte/BigInteger, and the parser produces a LazilyParsedNumber
        // — so a parsed value falls to the double branch, where near 1.2e18 the gap between
        // representable doubles is 256 and two snowflakes minted seconds apart collapse onto one.
        long id = 1234567890123456789L;
        long neighbour = 1234567890123456788L;

        Payload parsed = Payload.parse("{\"x\":" + id + "}");
        Payload built = Payload.builder().put("x", id).build();
        Payload builtNeighbour = Payload.builder().put("x", neighbour).build();

        assertEquals(parsed, built);
        assertNotEquals(parsed, builtNeighbour,
                "if this passes while the line above does too, equals is intransitive — and "
                        + "RemoteConfig decides whether to fire a module's listeners with it, so a "
                        + "setting carrying a snowflake silently stops reporting changes");
        assertNotEquals(built, builtNeighbour);
        assertEquals(parsed.hashCode(), built.hashCode());
    }

    @Test
    @DisplayName("0 and -0.0 are equal AND hash alike")
    void negativeZeroIsCanonicalised() {
        Payload positive = Payload.parse("{\"x\":0}");
        Payload negative = Payload.parse("{\"x\":-0.0}");

        assertEquals(positive, negative);
        assertEquals(positive.hashCode(), negative.hashCode(),
                "equal-but-differently-hashed is the exact contract violation that loses entries "
                        + "from a HashMap; hashing raw double bits sets the sign bit for -0.0");
    }

    @Test
    @DisplayName("1 and 1.0 are one number, however they were produced")
    void integerAndDecimalFormsAgree() {
        Payload asInt = Payload.parse("{\"x\":1}");
        Payload asDecimal = Payload.parse("{\"x\":1.0}");
        Payload built = Payload.builder().put("x", 1).build();

        assertEquals(asInt, asDecimal);
        assertEquals(asInt.hashCode(), asDecimal.hashCode());
        assertEquals(asInt, built);
        assertEquals(asInt.hashCode(), built.hashCode());
    }

    @Test
    @DisplayName("a string is never equal to the number that renders the same")
    void kindsAreComparedBeforeContents() {
        assertNotEquals(Payload.parse("{\"x\":\"1\"}"), Payload.parse("{\"x\":1}"));
        assertNotEquals(Payload.parse("{\"x\":true}"), Payload.parse("{\"x\":\"true\"}"));
    }

    // ── Numbers on the wire ──────────────────────────────────────────────────

    @Test
    @DisplayName("a long round-trips exactly, and reaches the wire without E-notation")
    void longsSurviveTheWire() {
        long timestamp = 1_700_000_000_000L;
        Payload built = Payload.builder().put("ts", timestamp).build();

        assertTrue(built.toJson().contains("\"ts\":1700000000000"),
                "E-notation is valid JSON that the bot would read back as a different number: "
                        + built.toJson());
        assertEquals(timestamp, Payload.parse(built.toJson()).longValue("ts", -1L));
    }

    @Test
    @DisplayName("snowflake precision survives a round trip")
    void snowflakesSurviveARoundTrip() {
        long snowflake = 1234567890123456789L;
        Payload parsed = Payload.parse(Payload.builder().put("id", snowflake).build().toJson());

        assertEquals(snowflake, parsed.longValue("id", -1L));
        assertEquals(String.valueOf(snowflake), parsed.string("id", null));
    }

    @Test
    @DisplayName("an out-of-range int is ABSENT, not truncated into a plausible wrong answer")
    void outOfRangeIntegersAreNotTruncated() {
        Payload payload = Payload.parse("{\"v\":5000000000}");

        assertNull(payload.optInt("v"),
                "getAsInt() on a parsed number is a narrowing cast that never complains — this used "
                        + "to come back as 705032704, a wrong answer indistinguishable from a right "
                        + "one, and departure D1's nullable queuePosition rests on optInt being "
                        + "trustworthy");
        assertEquals(-1, payload.intValue("v", -1));
        assertEquals(5_000_000_000L, payload.longValue("v", -1L), "but it fits a long perfectly");
    }

    @Test
    @DisplayName("an out-of-range long falls back rather than wrapping")
    void outOfRangeLongsFallBack() {
        Payload payload = Payload.parse("{\"v\":99999999999999999999999}");
        assertEquals(-1L, payload.longValue("v", -1L));
    }

    @Test
    @DisplayName("optBool and bool are type-strict — a number is not FALSE")
    void booleanAccessorsAreStrict() {
        assertNull(Payload.parse("{\"num\":5}").optBool("num"),
                "answering FALSE for a number defeats the entire absent-versus-value purpose");
        assertNull(Payload.parse("{\"s\":\"yes\"}").optBool("s"));
        assertTrue(Payload.parse("{\"s\":\"yes\"}").bool("s", true),
                "and the fallback is used rather than a confident wrong answer about, say, whether "
                        + "a module is enabled");
        assertTrue(Payload.parse("{\"b\":true}").bool("b", false));
        assertEquals(Boolean.FALSE, Payload.parse("{\"b\":false}").optBool("b"));
    }

    @Test
    @DisplayName("string() is deliberately coercive, so a numeric code is still usable")
    void stringIsCoercive() {
        assertEquals("135790", Payload.parse("{\"code\":135790}").string("code", null));
    }

    // ── Immutability across threads ──────────────────────────────────────────

    @Test
    @DisplayName("mutating the object a payload was built from cannot alter the payload")
    void adoptedObjectsAreCopiedAtTheBoundary() {
        JsonObject source = new JsonObject();
        source.addProperty("mode", "websocket");
        Payload adopted = Payload.wrap(source);

        source.addProperty("mode", "rcon");
        source.addProperty("injected", true);

        assertEquals("websocket", adopted.string("mode", null),
                "every inbound frame takes this path, and the payload it produces is handed to "
                        + "handler executors on other threads");
        assertFalse(adopted.has("injected"));
    }

    @Test
    @DisplayName("serialising a frame cannot corrupt the shared empty payload")
    void theEmptySingletonIsNeverLent() {
        Envelope.of("a", "ping", Payload.empty()).toJson();
        Envelope.of("b", "pong", Payload.empty()).toJson();

        assertTrue(Payload.empty().isEmpty());
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
