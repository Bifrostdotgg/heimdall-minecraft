package com.heimdall.core.punish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PunishmentParserTest {

    private static final long MINUTE = 60L;
    private static final long HOUR = 60L * 60L;
    private static final long DAY = 24L * HOUR;

    @Test
    void flagsAnywhereAndDurationThenReason() {
        PunishmentParser.Parsed parsed = PunishmentParser.parse(
                Arrays.asList("-s", "Steve", "7d", "cheating", "again"));
        assertTrue(parsed.silent);
        assertEquals("Steve", parsed.target);
        assertEquals(Long.valueOf(7 * DAY), parsed.durationSeconds);
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
        assertEquals(Long.valueOf(90 * MINUTE), PunishmentParser.parseDurationSeconds("1h30m"));
    }

    @Test
    void publicFlagAnywhere() {
        PunishmentParser.Parsed parsed = PunishmentParser.parse(
                Arrays.asList("Steve", "-p", "1h", "spam"));
        assertTrue(parsed.publicFlag);
        assertEquals(Long.valueOf(HOUR), parsed.durationSeconds);
        assertEquals("spam", parsed.reason);
    }

    @Test
    void flagsAfterTarget() {
        PunishmentParser.Parsed parsed = PunishmentParser.parse(
                Arrays.asList("Steve", "7d", "-s", "cheating"));
        assertTrue(parsed.silent);
        assertEquals(Long.valueOf(7 * DAY), parsed.durationSeconds);
        assertEquals("cheating", parsed.reason);
    }

    @Test
    void senderFlagIsParsedAndStrippedFromReason() {
        PunishmentParser.Parsed parsed = PunishmentParser.parse(
                Arrays.asList("Steve", "--sender=Console", "1d", "grief"));
        assertEquals("Console", parsed.senderOverride);
        assertEquals("grief", parsed.reason);
        assertEquals(Long.valueOf(DAY), parsed.durationSeconds);
    }

    @Test
    void combinedDurationTokens() {
        assertEquals(Long.valueOf(2 * DAY + 3 * HOUR), PunishmentParser.parseDurationSeconds("2d3h"));
        assertTrue(PunishmentParser.looksLikeDuration("permanent"));
        assertTrue(PunishmentParser.looksLikeDuration("perm"));
    }

    @Test
    @DisplayName("the duration is found after the reason, which is the order people type")
    void durationAfterTheReason() {
        PunishmentParser.Parsed parsed = PunishmentParser.parse(
                Arrays.asList("Steve", "griefing", "spawn", "1d"));

        assertEquals(Long.valueOf(DAY), parsed.durationSeconds);
        assertEquals("griefing spawn", parsed.reason);
    }

    @Test
    @DisplayName("and in the middle of it, without leaving a hole where it was")
    void durationInsideTheReason() {
        PunishmentParser.Parsed parsed = PunishmentParser.parse(
                Arrays.asList("Steve", "griefing", "7d", "at", "spawn"));

        assertEquals(Long.valueOf(7 * DAY), parsed.durationSeconds);
        assertEquals("griefing at spawn", parsed.reason);
    }

    @Test
    @DisplayName("seconds are seconds, not a minute rounded up")
    void secondsAreNotRounded() {
        PunishmentParser.Parsed parsed = PunishmentParser.parse(Arrays.asList("Steve", "30s", "spam"));

        assertEquals(Long.valueOf(30), parsed.durationSeconds);
        assertEquals("spam", parsed.reason);
        assertEquals(Long.valueOf(1), PunishmentParser.parseDurationSeconds("1s"));
        assertEquals(Long.valueOf(90), PunishmentParser.parseDurationSeconds("1m30s"));
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

        assertEquals(Long.valueOf(DAY), parsed.durationSeconds);
        assertEquals("griefed 2 houses 7d", parsed.reason);
    }

    @Test
    @DisplayName("every unit spelling, case-insensitively")
    void unitSpellings() {
        assertEquals(Long.valueOf(30), PunishmentParser.parseDurationSeconds("30s"));
        assertEquals(Long.valueOf(30), PunishmentParser.parseDurationSeconds("30sec"));
        assertEquals(Long.valueOf(30), PunishmentParser.parseDurationSeconds("30secs"));
        assertEquals(Long.valueOf(30), PunishmentParser.parseDurationSeconds("30second"));
        assertEquals(Long.valueOf(30), PunishmentParser.parseDurationSeconds("30seconds"));
        assertEquals(Long.valueOf(5 * MINUTE), PunishmentParser.parseDurationSeconds("5m"));
        assertEquals(Long.valueOf(5 * MINUTE), PunishmentParser.parseDurationSeconds("5min"));
        assertEquals(Long.valueOf(5 * MINUTE), PunishmentParser.parseDurationSeconds("5mins"));
        assertEquals(Long.valueOf(5 * MINUTE), PunishmentParser.parseDurationSeconds("5minute"));
        assertEquals(Long.valueOf(5 * MINUTE), PunishmentParser.parseDurationSeconds("5minutes"));
        assertEquals(Long.valueOf(2 * HOUR), PunishmentParser.parseDurationSeconds("2h"));
        assertEquals(Long.valueOf(2 * HOUR), PunishmentParser.parseDurationSeconds("2hr"));
        assertEquals(Long.valueOf(2 * HOUR), PunishmentParser.parseDurationSeconds("2hrs"));
        assertEquals(Long.valueOf(2 * HOUR), PunishmentParser.parseDurationSeconds("2hour"));
        assertEquals(Long.valueOf(2 * HOUR), PunishmentParser.parseDurationSeconds("2hours"));
        assertEquals(Long.valueOf(3 * DAY), PunishmentParser.parseDurationSeconds("3d"));
        assertEquals(Long.valueOf(3 * DAY), PunishmentParser.parseDurationSeconds("3day"));
        assertEquals(Long.valueOf(3 * DAY), PunishmentParser.parseDurationSeconds("3days"));
        assertEquals(Long.valueOf(2 * 7 * DAY), PunishmentParser.parseDurationSeconds("2w"));
        assertEquals(Long.valueOf(2 * 7 * DAY), PunishmentParser.parseDurationSeconds("2weeks"));
        assertEquals(Long.valueOf(30 * DAY), PunishmentParser.parseDurationSeconds("1mo"));
        assertEquals(Long.valueOf(2 * 30 * DAY), PunishmentParser.parseDurationSeconds("2months"));
        assertEquals(Long.valueOf(365 * DAY), PunishmentParser.parseDurationSeconds("1y"));
        assertEquals(Long.valueOf(365 * DAY), PunishmentParser.parseDurationSeconds("1year"));
        assertEquals(Long.valueOf(DAY), PunishmentParser.parseDurationSeconds("1D"));
        assertEquals(Long.valueOf(30 * DAY), PunishmentParser.parseDurationSeconds("1MO"));
        assertNull(PunishmentParser.parseDurationSeconds("PERMANENT"));
    }

    @Test
    @DisplayName("a compound token adds its parts up")
    void compoundTokens() {
        assertEquals(Long.valueOf(DAY + 12 * HOUR), PunishmentParser.parseDurationSeconds("1d12h"));
        assertEquals(Long.valueOf(DAY + 12 * HOUR + 30 * MINUTE + 5),
                PunishmentParser.parseDurationSeconds("1d12h30m5s"));
    }

    @Test
    @DisplayName("a length nobody can serve is refused, not clamped")
    void absurdDurationsAreRefused() {
        assertFalse(PunishmentParser.looksLikeDuration("99999y"));
        assertNull(PunishmentParser.parseDurationSeconds("99999y"));
        assertFalse(PunishmentParser.looksLikeDuration("999999999999999999999y"),
                "more digits than a long holds is still just a slip of the keyboard");

        PunishmentParser.Parsed parsed = PunishmentParser.parse(
                Arrays.asList("Steve", "999y", "nope"));
        assertNull(parsed.durationSeconds);
        assertEquals("999y nope", parsed.reason,
                "a refused token stays visible in the reason rather than vanishing into a "
                        + "permanent ban nobody typed");
    }

    @Test
    @DisplayName("the word taken as the duration is reported, even when it means no length")
    void theTakenTokenIsReported() {
        assertEquals("7d", PunishmentParser.parse(
                Arrays.asList("Steve", "7d", "griefing")).durationToken);
        assertEquals("perm", PunishmentParser.parse(
                Arrays.asList("Steve", "perm", "griefing")).durationToken,
                "perm resolves to no length and still removes a word, which is exactly the case "
                        + "a caller that refuses lengths has to be able to see");
        assertNull(PunishmentParser.parse(
                Arrays.asList("Steve", "perm", "griefing")).durationSeconds);
        assertNull(PunishmentParser.parse(
                Arrays.asList("Steve", "griefing")).durationToken);
        assertNull(PunishmentParser.parse(
                Arrays.asList("Steve", "999y", "nope")).durationToken,
                "a token the grammar refused was never taken, so it is reason text and there is "
                        + "nothing to report");
    }

    @Test
    @DisplayName("a zero-length punishment is refused too")
    void zeroIsRefused() {
        assertFalse(PunishmentParser.looksLikeDuration("0s"));
        assertFalse(PunishmentParser.looksLikeDuration("0d0h"));
        assertNull(PunishmentParser.parseDurationSeconds("0s"));
    }
}
