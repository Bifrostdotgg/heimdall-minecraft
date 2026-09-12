package com.heimdall.core.punish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PunishmentParserTest {

    private static final int MINUTE = 60;
    private static final int HOUR = 60 * 60;
    private static final int DAY = 24 * HOUR;

    @Test
    void flagsAnywhereAndDurationThenReason() {
        PunishmentParser.Parsed parsed = PunishmentParser.parse(
                Arrays.asList("-s", "Steve", "7d", "cheating", "again"));
        assertTrue(parsed.silent);
        assertEquals("Steve", parsed.target);
        assertEquals(Integer.valueOf(7 * DAY), parsed.durationSeconds);
        assertEquals("cheating again", parsed.reason);
    }

    @Test
    void omittedDurationIsPermanent() {
        PunishmentParser.Parsed parsed = PunishmentParser.parse(Arrays.asList("Steve", "never", "come", "back"));
        assertEquals("Steve", parsed.target);
        assertNull(parsed.durationSeconds);
        assertEquals("never come back", parsed.reason);
    }

    @Test
    void permToken() {
        assertNull(PunishmentParser.parseDurationSeconds("perm"));
        assertEquals(Integer.valueOf(90 * MINUTE), PunishmentParser.parseDurationSeconds("1h30m"));
    }

    @Test
    void publicFlagAnywhere() {
        PunishmentParser.Parsed parsed = PunishmentParser.parse(
                Arrays.asList("Steve", "-p", "1h", "spam"));
        assertTrue(parsed.publicFlag);
        assertEquals(Integer.valueOf(HOUR), parsed.durationSeconds);
        assertEquals("spam", parsed.reason);
    }

    @Test
    void flagsAfterTarget() {
        PunishmentParser.Parsed parsed = PunishmentParser.parse(
                Arrays.asList("Steve", "7d", "-s", "cheating"));
        assertTrue(parsed.silent);
        assertEquals(Integer.valueOf(7 * DAY), parsed.durationSeconds);
        assertEquals("cheating", parsed.reason);
    }

    @Test
    void senderFlagIsParsedAndStrippedFromReason() {
        PunishmentParser.Parsed parsed = PunishmentParser.parse(
                Arrays.asList("Steve", "--sender=Console", "1d", "grief"));
        assertEquals("Console", parsed.senderOverride);
        assertEquals("grief", parsed.reason);
        assertEquals(Integer.valueOf(DAY), parsed.durationSeconds);
    }

    @Test
    void combinedDurationTokens() {
        assertEquals(Integer.valueOf(2 * DAY + 3 * HOUR), PunishmentParser.parseDurationSeconds("2d3h"));
        assertTrue(PunishmentParser.looksLikeDuration("permanent"));
        assertTrue(PunishmentParser.looksLikeDuration("perm"));
    }

    @Test
    @DisplayName("the duration is found after the reason, which is the order people type")
    void durationAfterTheReason() {
        PunishmentParser.Parsed parsed = PunishmentParser.parse(
                Arrays.asList("Steve", "griefing", "spawn", "1d"));

        assertEquals(Integer.valueOf(DAY), parsed.durationSeconds);
        assertEquals("griefing spawn", parsed.reason);
    }

    @Test
    @DisplayName("and in the middle of it, without leaving a hole where it was")
    void durationInsideTheReason() {
        PunishmentParser.Parsed parsed = PunishmentParser.parse(
                Arrays.asList("Steve", "griefing", "7d", "at", "spawn"));

        assertEquals(Integer.valueOf(7 * DAY), parsed.durationSeconds);
        assertEquals("griefing at spawn", parsed.reason);
    }

    @Test
    @DisplayName("seconds are seconds, not a minute rounded up")
    void secondsAreNotRounded() {
        PunishmentParser.Parsed parsed = PunishmentParser.parse(Arrays.asList("Steve", "30s", "spam"));

        assertEquals(Integer.valueOf(30), parsed.durationSeconds);
        assertEquals("spam", parsed.reason);
        assertEquals(Integer.valueOf(1), PunishmentParser.parseDurationSeconds("1s"));
        assertEquals(Integer.valueOf(90), PunishmentParser.parseDurationSeconds("1m30s"));
    }

    @Test
    @DisplayName("a word with a duration buried in it is a word")
    void substringsAreNotDurations() {
        assertFalse(PunishmentParser.looksLikeDuration("abc5m"));
        assertFalse(PunishmentParser.looksLikeDuration("1day-old"));
        assertFalse(PunishmentParser.looksLikeDuration("x1d"));
        assertFalse(PunishmentParser.looksLikeDuration("1d!"));
        assertFalse(PunishmentParser.looksLikeDuration("5"));
        assertFalse(PunishmentParser.looksLikeDuration("d"));

        PunishmentParser.Parsed parsed = PunishmentParser.parse(
                Arrays.asList("Steve", "abc5m", "1day-old", "account"));
        assertNull(parsed.durationSeconds, "neither token is a duration");
        assertEquals("abc5m 1day-old account", parsed.reason);
    }

    @Test
    @DisplayName("the first duration wins and the second stays in the reason")
    void firstDurationWins() {
        PunishmentParser.Parsed parsed = PunishmentParser.parse(
                Arrays.asList("Steve", "1d", "griefed", "2", "houses", "7d"));

        assertEquals(Integer.valueOf(DAY), parsed.durationSeconds);
        assertEquals("griefed 2 houses 7d", parsed.reason);
    }

    @Test
    @DisplayName("every unit spelling, case-insensitively")
    void unitSpellings() {
        assertEquals(Integer.valueOf(30), PunishmentParser.parseDurationSeconds("30s"));
        assertEquals(Integer.valueOf(30), PunishmentParser.parseDurationSeconds("30sec"));
        assertEquals(Integer.valueOf(30), PunishmentParser.parseDurationSeconds("30secs"));
        assertEquals(Integer.valueOf(30), PunishmentParser.parseDurationSeconds("30second"));
        assertEquals(Integer.valueOf(30), PunishmentParser.parseDurationSeconds("30seconds"));
        assertEquals(Integer.valueOf(5 * MINUTE), PunishmentParser.parseDurationSeconds("5m"));
        assertEquals(Integer.valueOf(5 * MINUTE), PunishmentParser.parseDurationSeconds("5min"));
        assertEquals(Integer.valueOf(5 * MINUTE), PunishmentParser.parseDurationSeconds("5mins"));
        assertEquals(Integer.valueOf(5 * MINUTE), PunishmentParser.parseDurationSeconds("5minute"));
        assertEquals(Integer.valueOf(5 * MINUTE), PunishmentParser.parseDurationSeconds("5minutes"));
        assertEquals(Integer.valueOf(2 * HOUR), PunishmentParser.parseDurationSeconds("2h"));
        assertEquals(Integer.valueOf(2 * HOUR), PunishmentParser.parseDurationSeconds("2hr"));
        assertEquals(Integer.valueOf(2 * HOUR), PunishmentParser.parseDurationSeconds("2hrs"));
        assertEquals(Integer.valueOf(2 * HOUR), PunishmentParser.parseDurationSeconds("2hour"));
        assertEquals(Integer.valueOf(2 * HOUR), PunishmentParser.parseDurationSeconds("2hours"));
        assertEquals(Integer.valueOf(3 * DAY), PunishmentParser.parseDurationSeconds("3d"));
        assertEquals(Integer.valueOf(3 * DAY), PunishmentParser.parseDurationSeconds("3day"));
        assertEquals(Integer.valueOf(3 * DAY), PunishmentParser.parseDurationSeconds("3days"));
        assertEquals(Integer.valueOf(2 * 7 * DAY), PunishmentParser.parseDurationSeconds("2w"));
        assertEquals(Integer.valueOf(2 * 7 * DAY), PunishmentParser.parseDurationSeconds("2weeks"));
        assertEquals(Integer.valueOf(30 * DAY), PunishmentParser.parseDurationSeconds("1mo"));
        assertEquals(Integer.valueOf(2 * 30 * DAY), PunishmentParser.parseDurationSeconds("2months"));
        assertEquals(Integer.valueOf(365 * DAY), PunishmentParser.parseDurationSeconds("1y"));
        assertEquals(Integer.valueOf(365 * DAY), PunishmentParser.parseDurationSeconds("1year"));
        assertEquals(Integer.valueOf(DAY), PunishmentParser.parseDurationSeconds("1D"));
        assertEquals(Integer.valueOf(30 * DAY), PunishmentParser.parseDurationSeconds("1MO"));
        assertNull(PunishmentParser.parseDurationSeconds("PERMANENT"));
    }

    @Test
    @DisplayName("a compound token adds its parts up")
    void compoundTokens() {
        assertEquals(Integer.valueOf(DAY + 12 * HOUR), PunishmentParser.parseDurationSeconds("1d12h"));
        assertEquals(Integer.valueOf(DAY + 12 * HOUR + 30 * MINUTE + 5),
                PunishmentParser.parseDurationSeconds("1d12h30m5s"));
    }

    @Test
    @DisplayName("a duration nobody can serve saturates rather than wrapping negative")
    void absurdDurationsSaturate() {
        assertEquals(Integer.valueOf(Integer.MAX_VALUE),
                PunishmentParser.parseDurationSeconds("99999y"));
    }
}
