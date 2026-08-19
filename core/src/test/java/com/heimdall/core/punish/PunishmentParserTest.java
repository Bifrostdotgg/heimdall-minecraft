package com.heimdall.core.punish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class PunishmentParserTest {

    @Test
    void flagsAnywhereAndDurationThenReason() {
        PunishmentParser.Parsed parsed = PunishmentParser.parse(
                Arrays.asList("-s", "Steve", "7d", "cheating", "again"));
        assertTrue(parsed.silent);
        assertEquals("Steve", parsed.target);
        assertEquals(Integer.valueOf(7 * 24 * 60), parsed.durationMinutes);
        assertEquals("cheating again", parsed.reason);
    }

    @Test
    void omittedDurationIsPermanent() {
        PunishmentParser.Parsed parsed = PunishmentParser.parse(Arrays.asList("Steve", "never", "come", "back"));
        assertEquals("Steve", parsed.target);
        assertNull(parsed.durationMinutes);
        assertEquals("never come back", parsed.reason);
    }

    @Test
    void permToken() {
        assertNull(PunishmentParser.parseDurationMinutes("perm"));
        assertEquals(Integer.valueOf(90), PunishmentParser.parseDurationMinutes("1h30m"));
    }
}
