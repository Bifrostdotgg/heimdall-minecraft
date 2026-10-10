package com.heimdall.shell.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * State crossing a swap: plain JDK values only, copied on the way in, unmodifiable on the way out
 * (departure D87). Anything else could keep the outgoing core's classloader alive, or hand the next
 * core an object whose class it cannot see.
 */
class HandoffTest {

    @Test
    @DisplayName("plain values round-trip intact, nested and in order")
    void roundTrip() {
        Map<String, Object> cooldowns = new LinkedHashMap<String, Object>();
        cooldowns.put("11111111-2222-3333-4444-555555555555", Long.valueOf(1_700_000_000_000L));
        Map<String, Object> state = new LinkedHashMap<String, Object>();
        state.put("link.cooldowns", cooldowns);
        state.put("names", Arrays.asList("alpha", "beta"));
        state.put("enabled", Boolean.TRUE);
        state.put("ratio", Double.valueOf(0.5));
        state.put("count", Integer.valueOf(3));
        state.put("nothing", null);

        Map<String, Object> copy = Handoff.copyOf(state);

        assertEquals(state, copy);
        assertEquals(Long.valueOf(1_700_000_000_000L),
                ((Map<?, ?>) copy.get("link.cooldowns")).get("11111111-2222-3333-4444-555555555555"));
    }

    @Test
    @DisplayName("the copy is deep: changing the source afterwards changes nothing handed over")
    void deepCopy() {
        List<Object> names = new ArrayList<Object>(Arrays.asList("alpha"));
        Map<String, Object> state = new HashMap<String, Object>();
        state.put("names", names);

        Map<String, Object> copy = Handoff.copyOf(state);
        names.add("beta");

        assertEquals(Arrays.asList("alpha"), copy.get("names"));
    }

    @Test
    @DisplayName("the copy is unmodifiable all the way down")
    void unmodifiable() {
        Map<String, Object> inner = new HashMap<String, Object>();
        inner.put("k", "v");
        Map<String, Object> state = new HashMap<String, Object>();
        state.put("inner", inner);
        state.put("list", new ArrayList<Object>(Arrays.asList("a")));

        Map<String, Object> copy = Handoff.copyOf(state);

        assertThrows(UnsupportedOperationException.class, () -> copy.put("x", "y"));
        assertThrows(UnsupportedOperationException.class,
                () -> ((Map<String, Object>) copy.get("inner")).put("x", "y"));
        assertThrows(UnsupportedOperationException.class,
                () -> ((List<Object>) copy.get("list")).add("b"));
    }

    @Test
    @DisplayName("a value that is not a plain JDK type is refused, naming where it was")
    void refusesForeignTypes() {
        Map<String, Object> inner = new HashMap<String, Object>();
        inner.put("who", UUID.randomUUID());
        Map<String, Object> state = new HashMap<String, Object>();
        state.put("players", Arrays.<Object>asList(inner));

        IllegalArgumentException refused =
                assertThrows(IllegalArgumentException.class, () -> Handoff.copyOf(state));

        assertTrue(refused.getMessage().contains("players[0].who"), refused.getMessage());
        assertTrue(refused.getMessage().contains("java.util.UUID"), refused.getMessage());
    }

    @Test
    @DisplayName("a map with a non-string key is refused")
    void refusesNonStringKeys() {
        Map<Object, Object> inner = new HashMap<Object, Object>();
        inner.put(Integer.valueOf(1), "one");
        Map<String, Object> state = new HashMap<String, Object>();
        state.put("inner", inner);

        assertThrows(IllegalArgumentException.class, () -> Handoff.copyOf(state));
    }

    @Test
    @DisplayName("nesting past the bound is refused rather than recursing forever")
    void boundedDepth() {
        Map<String, Object> root = new HashMap<String, Object>();
        Map<String, Object> cursor = root;
        for (int i = 0; i < Handoff.MAX_DEPTH + 2; i++) {
            Map<String, Object> next = new HashMap<String, Object>();
            cursor.put("next", next);
            cursor = next;
        }

        assertThrows(IllegalArgumentException.class, () -> Handoff.copyOf(root));
    }

    @Test
    @DisplayName("null is an empty handoff")
    void nullIsEmpty() {
        assertTrue(Handoff.copyOf(null).isEmpty());
    }
}
