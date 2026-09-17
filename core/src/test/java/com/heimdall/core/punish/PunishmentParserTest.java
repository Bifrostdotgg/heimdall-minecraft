package com.heimdall.core.punish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void publicFlagAnywhere() {
        PunishmentParser.Parsed parsed = PunishmentParser.parse(
                Arrays.asList("Steve", "-p", "1h", "spam"));
        assertTrue(parsed.publicFlag);
        assertEquals(Integer.valueOf(60), parsed.durationMinutes);
        assertEquals("spam", parsed.reason);
    }

    @Test
    void flagsAfterTarget() {
        PunishmentParser.Parsed parsed = PunishmentParser.parse(
                Arrays.asList("Steve", "7d", "-s", "cheating"));
        assertTrue(parsed.silent);
        assertEquals(Integer.valueOf(7 * 24 * 60), parsed.durationMinutes);
        assertEquals("cheating", parsed.reason);
    }

    @Test
    void senderFlagIsParsedAndStrippedFromReason() {
        PunishmentParser.Parsed parsed = PunishmentParser.parse(
                Arrays.asList("Steve", "--sender=Console", "1d", "grief"));
        assertEquals("Console", parsed.senderOverride);
        assertEquals("grief", parsed.reason);
        assertEquals(Integer.valueOf(24 * 60), parsed.durationMinutes);
    }

    @Test
    void combinedDurationTokens() {
        assertEquals(Integer.valueOf(2 * 24 * 60 + 3 * 60), PunishmentParser.parseDurationMinutes("2d3h"));
        assertTrue(PunishmentParser.looksLikeDuration("permanent"));
        assertTrue(PunishmentParser.looksLikeDuration("perm"));
    }

    @Test
    void hiddenFlagAnywhere() {
        PunishmentParser.Parsed parsed = PunishmentParser.parse(
                Arrays.asList("Steve", "7d", "-h", "ban", "evasion"));
        assertTrue(parsed.hidden);
        assertEquals("Steve", parsed.target);
        assertEquals(Integer.valueOf(7 * 24 * 60), parsed.durationMinutes);
        assertEquals("ban evasion", parsed.reason, "the flag is stripped out of the reason");

        PunishmentParser.Parsed upper = PunishmentParser.parse(Arrays.asList("-H", "Steve", "alting"));
        assertTrue(upper.hidden, "flags are case-insensitive, like -s and -p");
        assertEquals("alting", upper.reason);
    }

    @Test
    void hiddenForcesSilent() {
        PunishmentParser.Parsed parsed = PunishmentParser.parse(Arrays.asList("Steve", "-h", "alting"));
        assertTrue(parsed.hidden);
        assertTrue(parsed.silent,
                "a punishment hidden from staff lookups cannot be announced in chat, so the one "
                        + "flag decides both and no caller has to remember the implication");
        assertFalse(parsed.publicFlag);

        PunishmentParser.Flags flags = PunishmentParser.flags(Arrays.asList("Steve", "-h"));
        assertTrue(flags.hidden);
        assertTrue(flags.silent, "the revoke path reads the same implication off Flags");
    }

    @Test
    void hiddenWithPublicFlagStaysHiddenAndSilent() {
        PunishmentParser.Parsed parsed = PunishmentParser.parse(
                Arrays.asList("Steve", "-p", "-h", "1h", "alting"));
        assertTrue(parsed.hidden);
        assertTrue(parsed.silent, "-p does not undo the silence -h implies");
        assertTrue(parsed.publicFlag, "the -p is still reported; the caller decides hidden wins");
        assertEquals(Integer.valueOf(60), parsed.durationMinutes);
        assertEquals("alting", parsed.reason);
    }

    @Test
    void noFlagsMeansNothingHidden() {
        PunishmentParser.Parsed parsed = PunishmentParser.parse(Arrays.asList("Steve", "griefing"));
        assertFalse(parsed.hidden);
        assertFalse(parsed.silent);
        assertFalse(PunishmentParser.flags(null).hidden);
    }
}
