package com.heimdall.core.punish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The duration grammar, against the table the bot side parses with.
 *
 * <p>{@code duration.fixtures.json} is committed in {@code packages/shared} on the Heimdall side
 * and copied here verbatim. Two implementations of one grammar - a slash command and a dashboard
 * form in TypeScript, {@code /hd ban} in Java - will drift, and the way that drift shows up is a
 * moderator typing the same thing in two places and getting two punishments. A shared table is
 * the cheapest thing that can notice.
 *
 * <p>Three outcomes, and the difference between the last two is the point: a number is a length,
 * {@code "permanent"} is forever, and {@code null} is <strong>refused</strong> - not a duration
 * at all, so the token stays in the reason rather than quietly becoming a permanent punishment.
 */
class DurationGrammarFixtureTest {

    @Test
    @DisplayName("every row of the shared fixture table parses the same way in Java")
    void fixtures() {
        JsonArray rows = load();
        assertTrue(rows.size() > 50, "the table should be the committed one, not a stub");
        List<String> mismatches = new ArrayList<String>();
        for (JsonElement element : rows) {
            JsonObject row = element.getAsJsonObject();
            String input = row.get("input").getAsString();
            JsonElement expected = row.get("seconds");
            Long actual = PunishmentParser.parseDurationSeconds(input);
            boolean accepted = PunishmentParser.looksLikeDuration(input);
            if (expected.isJsonNull()) {
                if (accepted || actual != null) {
                    mismatches.add(quote(input) + " should be refused, got "
                            + (accepted ? "accepted" : "rejected") + " / " + actual);
                }
            } else if (expected.getAsJsonPrimitive().isString()) {
                if (!"permanent".equals(expected.getAsString())) {
                    mismatches.add(quote(input) + " has an unknown expectation "
                            + expected);
                } else if (!accepted || actual != null) {
                    mismatches.add(quote(input) + " should be permanent, got "
                            + (accepted ? "accepted" : "rejected") + " / " + actual);
                }
            } else {
                Long want = Long.valueOf(expected.getAsLong());
                if (!accepted || !want.equals(actual)) {
                    mismatches.add(quote(input) + " should be " + want + ", got " + actual);
                }
            }
        }
        assertTrue(mismatches.isEmpty(), "the Java grammar disagrees with the shared table:\n  "
                + String.join("\n  ", mismatches));
    }

    @Test
    @DisplayName("the three outcomes are three outcomes, not two")
    void refusedIsNotPermanent() {
        assertTrue(PunishmentParser.looksLikeDuration("perm"));
        assertNull(PunishmentParser.parseDurationSeconds("perm"));

        assertFalse(PunishmentParser.looksLikeDuration("0s"),
                "a refused token stays in the reason, where the moderator can see it went wrong");
        assertNull(PunishmentParser.parseDurationSeconds("0s"));

        assertNotNull(PunishmentParser.parseDurationSeconds("100y"), "the cap is inclusive");
        assertNull(PunishmentParser.parseDurationSeconds("101y"));
    }

    @Test
    @DisplayName("a hundred years does not fit in an int, which is why the type is Long")
    void theCapDoesNotFitInAnInt() {
        assertEquals(Long.valueOf(3_153_600_000L), PunishmentParser.parseDurationSeconds("100y"));
        assertTrue(3_153_600_000L > Integer.MAX_VALUE);
    }

    private static JsonArray load() {
        InputStream stream = DurationGrammarFixtureTest.class
                .getResourceAsStream("/duration.fixtures.json");
        assertNotNull(stream, "duration.fixtures.json is missing from the test resources");
        try {
            return JsonParser.parseReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonArray();
        } finally {
            try {
                stream.close();
            } catch (java.io.IOException ignored) {
                // Reading a classpath resource that is already fully parsed.
            }
        }
    }

    private static String quote(String value) {
        return "\"" + value + "\"";
    }
}
