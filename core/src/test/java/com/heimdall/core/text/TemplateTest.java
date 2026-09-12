package com.heimdall.core.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.testing.TestText;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The optional-segment rule, the token rule, and the four ways they interact. */
class TemplateTest {

    @Test
    @DisplayName("a token is replaced by its value")
    void tokensAreSubstituted() {
        assertEquals("banned Steve",
                Template.fill("banned {player}", Template.values().put("player", "Steve")));
    }

    @Test
    @DisplayName("a segment survives when every placeholder in it has a value")
    void filledSegmentSurvives() {
        assertEquals("Steve: griefing",
                Template.fill("{player}[: {reason}]",
                        Template.values().put("player", "Steve").put("reason", "griefing")));
    }

    @Test
    @DisplayName("a segment is dropped when any placeholder in it is empty")
    void emptySegmentIsDropped() {
        assertEquals("Steve",
                Template.fill("{player}[: {reason}]",
                        Template.values().put("player", "Steve").put("reason", "")));
        assertEquals("Steve",
                Template.fill("{player}[ for {duration} ({remaining} left)]",
                        Template.values().put("player", "Steve").put("duration", "7d")
                                .put("remaining", "")),
                "any, not all: a half-filled clause is worse than no clause");
    }

    @Test
    @DisplayName("an unknown token is empty, and takes its segment with it")
    void unknownTokensAreEmpty() {
        assertEquals("Steve",
                Template.fill("{player}[ {nosuchtoken}]", Template.values().put("player", "Steve")));
    }

    @Test
    @DisplayName("a segment with no placeholders at all is never dropped")
    void constantSegmentSurvives() {
        assertEquals("Steve is gone",
                Template.fill("{player}[ is gone]", Template.values().put("player", "Steve")));
    }

    @Test
    @DisplayName("a line left blank by a drop is removed; a blank line that was typed is not")
    void emptiedLinesAreRemoved() {
        String template = "Header\n[Reason: {reason}]\nFooter";
        assertEquals("Header\nFooter",
                Template.fill(template, Template.values().put("reason", "")));
        assertEquals("Header\nReason: grief\nFooter",
                Template.fill(template, Template.values().put("reason", "grief")));

        assertEquals("Header\n\nFooter",
                Template.fill("Header\n\nFooter", Template.values()),
                "a blank line nothing was dropped from is a decision somebody made");
    }

    @Test
    @DisplayName("a line left holding only tags is removed like a blank one")
    void linesLeftWithOnlyTagsAreRemoved() {
        // The shape the dashboard editor makes natural: the guild wrapped the whole row rather
        // than only its value, so the drop leaves <gray></gray> rather than "".
        assertEquals("Header\nFooter",
                Template.fill("Header\n<gray>[Reason: {reason}]</gray>\nFooter",
                        Template.values().put("reason", "")),
                "a pair of tags with nothing between them renders as a blank line");
        assertEquals("Header\n<gray>Reason: grief</gray>\nFooter",
                Template.fill("Header\n<gray>[Reason: {reason}]</gray>\nFooter",
                        Template.values().put("reason", "grief")));
    }

    @Test
    @DisplayName("a line of pure formatting that lost nothing is a decision and stays")
    void formattingOnlyLinesSurvive() {
        String divider = "<gradient:#c278b0:#a292dc><st>      </st></gradient>";
        assertEquals("Header\n" + divider + "\nFooter",
                Template.fill("Header\n" + divider + "\nFooter", Template.values()),
                "the rule only ever looks at a line something was dropped from");
    }

    @Test
    @DisplayName("a line with text left on it stays, however much was dropped")
    void partiallyEmptiedLinesStay() {
        assertEquals("Length: forever",
                Template.fill("Length: forever[ ({remaining} remaining)]",
                        Template.values().put("remaining", "")));
    }

    @Test
    @DisplayName("an empty token on its own line takes the line with it")
    void emptyTokenAloneOnALineRemovesIt() {
        assertEquals("Header\nFooter",
                Template.fill("Header\n{base}\nFooter", Template.values().putRaw("base", "")),
                "an empty base renders nothing, not a blank line at the top of every screen");
    }

    @Test
    @DisplayName("a backslash makes a bracket a bracket")
    void escapedBracketsAreLiteral() {
        assertEquals("[Heimdall] Steve",
                Template.fill("\\[Heimdall\\] {player}", Template.values().put("player", "Steve")));
        assertEquals("[keep me]",
                Template.fill("[\\[keep me\\]]", Template.values()),
                "an escaped bracket inside a segment does not close it");
    }

    @Test
    @DisplayName("an opening bracket with no partner is printed rather than swallowing the rest")
    void unmatchedBracketIsLiteral() {
        assertEquals("[not a segment Steve",
                Template.fill("[not a segment {player}", Template.values().put("player", "Steve")));
    }

    @Test
    @DisplayName("nesting is not supported: the first unescaped bracket closes the segment")
    void nestingIsNotSupported() {
        // Documented rather than fixed. The segment runs from the first [ to the FIRST ], the
        // inner [ is a literal character, and what follows the closing bracket is text. So an
        // empty {x} drops "a [b {x}" and leaves " c]" behind.
        assertEquals(" c]",
                Template.fill("[a [b {x}] c]", Template.values().put("x", "")));
        assertEquals("a [b 1 c]",
                Template.fill("[a [b {x}] c]", Template.values().put("x", "1")));
    }

    @Test
    @DisplayName("a brace that is not a token is left alone, so MiniMessage keeps its own braces")
    void nonTokenBracesSurvive() {
        assertEquals("{ not a token } {}",
                Template.fill("{ not a token } {}", Template.values()));
    }

    @Test
    @DisplayName("a value cannot introduce formatting, however it is spelled")
    void valuesAreEscaped() {
        String filled = Template.fill("Reason: {reason}",
                Template.values().put("reason", "<red>very</red> bad"));

        assertFalse(filled.contains("Reason: <red>"),
                "the tag has to be escaped on the way in, or the rest of the screen turns red: "
                        + filled);
        assertEquals("Reason: <red>very</red> bad", TestText.plain(Msg.mini(filled)),
                "the words survive, as text");
    }

    @Test
    @DisplayName("a raw value is inserted as MiniMessage, because it already is some")
    void rawValuesAreNotEscaped() {
        String filled = Template.fill("{base}\nBody",
                Template.values().putRaw("base", "<red>Header</red>"));

        assertEquals("<red>Header</red>\nBody", filled);
        assertEquals("Header\nBody", TestText.plain(Msg.mini(filled)));
    }

    @Test
    @DisplayName("{base} inside the base is not recursive")
    void baseIsNotRecursive() {
        // The base is rendered with a value set that has no "base" in it, so the token resolves
        // empty and the line it was alone on goes. A template that referred to itself would
        // otherwise need a depth limit, and a depth limit is a number somebody has to defend.
        assertEquals("Punishments",
                Template.fill("{base}\nPunishments", Template.values()));
    }

    @Test
    @DisplayName("render parses the finished string once")
    void renderParsesOnce() {
        assertEquals("Steve is banned",
                TestText.plain(Template.render("<red>{player}</red> is banned",
                        Template.values().put("player", "Steve"))));
    }

    @Test
    @DisplayName("an empty or null template renders nothing rather than throwing")
    void emptyTemplates() {
        assertEquals("", Template.fill(null, Template.values()));
        assertEquals("", Template.fill("", Template.values()));
        assertTrue(TestText.plain(Template.render("", Template.values())).isEmpty());
        assertEquals("Steve", Template.fill("{player}", Template.values().put("player", "Steve")));
        assertEquals("", Template.fill("{player}", null));
    }
}
