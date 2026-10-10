package com.heimdall.core.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The wire frame, including the truthiness guard the bot applies to id and type. */
class EnvelopeTest {

    @Test
    void roundTripsThroughJson() {
        Envelope original = Envelope.of("abc123", "role_sync",
                Payload.builder().put("username", "Steve").build());

        Envelope parsed = Envelope.parse(original.toJson());

        assertNotNull(parsed);
        assertEquals(original, parsed);
        assertEquals("abc123", parsed.id());
        assertEquals("role_sync", parsed.type());
        assertEquals("Steve", parsed.payload().string("username", null));
    }

    @Test
    @DisplayName("a frame with a falsy id or type is rejected, exactly as the bot rejects it")
    void falsyIdOrTypeIsRejected() {
        // The bot's guard is `if (!msg.type || !msg.id)` — a JavaScript truthiness check. Accepting
        // {"id":""} here would register a correlation against an id no reply can ever carry.
        assertNull(Envelope.parse("{\"id\":\"\",\"type\":\"ping\"}"));
        assertNull(Envelope.parse("{\"id\":\"a\",\"type\":\"\"}"));
        assertNull(Envelope.parse("{\"id\":0,\"type\":\"ping\"}"));
        assertNull(Envelope.parse("{\"id\":false,\"type\":\"ping\"}"));
        assertNull(Envelope.parse("{\"type\":\"ping\"}"));
        assertNull(Envelope.parse("{\"id\":\"a\"}"));
    }

    @Test
    @DisplayName("garbage is dropped rather than unwinding the socket read loop")
    void garbageParsesToNull() {
        assertNull(Envelope.parse(null));
        assertNull(Envelope.parse(""));
        assertNull(Envelope.parse("not json at all"));
        assertNull(Envelope.parse("[1,2,3]"));
        assertNull(Envelope.parse("\"a string\""));
    }

    @Test
    void anAbsentOrNonObjectPayloadBecomesEmpty() {
        assertTrue(Envelope.parse("{\"id\":\"a\",\"type\":\"ping\"}").payload().isEmpty());
        assertTrue(Envelope.parse("{\"id\":\"a\",\"type\":\"ping\",\"payload\":null}").payload().isEmpty());
        assertTrue(Envelope.parse("{\"id\":\"a\",\"type\":\"ping\",\"payload\":[]}").payload().isEmpty());
    }

    @Test
    void idAndTypeAreRequiredWhenBuilding() {
        assertThrows(IllegalArgumentException.class, () -> Envelope.of(null, "ping", Payload.empty()));
        assertThrows(IllegalArgumentException.class, () -> Envelope.of("", "ping", Payload.empty()));
        assertThrows(IllegalArgumentException.class, () -> Envelope.of("a", null, Payload.empty()));
        assertThrows(IllegalArgumentException.class, () -> Envelope.of("a", "", Payload.empty()));
    }

    @Test
    @DisplayName("fresh ids are eight hex characters, and distinct across a handful")
    void freshIdsHaveTheRightShapeAndVary() {
        // Asserting distinctness over a thousand ids would be asserting that 32 bits of entropy
        // beat the birthday bound, which it does not: the collision probability there is about one
        // in 8,600, so the test would fail roughly once every few thousand CI runs and be blamed on
        // something else. Eight hex characters is the actual contract — the id only has to be
        // distinguishable among the handful of requests outstanding on one socket at one moment.
        Set<String> ids = new HashSet<String>();
        for (int i = 0; i < 32; i++) {
            String id = Envelope.newId();
            assertTrue(id.matches("[0-9a-f]{8}"), "unexpected id shape: " + id);
            ids.add(id);
        }
        assertTrue(ids.size() >= 30, "ids should vary; got " + ids.size() + " distinct out of 32");
    }

    @Test
    void valueSemantics() {
        Envelope a = Envelope.of("1", "ping", Payload.empty());
        Envelope b = Envelope.of("1", "ping", Payload.empty());
        Envelope c = Envelope.of("2", "ping", Payload.empty());

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
    }
}
