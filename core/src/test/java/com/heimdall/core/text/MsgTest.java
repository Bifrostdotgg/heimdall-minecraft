package com.heimdall.core.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.log.LogLevel;
import com.heimdall.core.log.RecordingLogger;
import com.heimdall.core.testing.TestText;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The one place a string becomes something a player can be shown.
 *
 * <p>Assertions go through {@link Msg#toLegacy} and the component model rather than a plain-text
 * serializer: {@code adventure-text-serializer-plain} is a separate artifact at the pinned version,
 * and pulling a whole dependency into the build so a test can flatten a string would be paying for
 * the test rather than for the code.
 */
class MsgTest {

    @Test
    @DisplayName("section codes become real formatting, not literal characters")
    void legacyParsesSectionCodes() {
        Component parsed = Msg.legacy("§cDenied");

        assertEquals("§cDenied", Msg.toLegacy(parsed));
        assertEquals(NamedTextColor.RED, colourOf(parsed),
                "if the code were left as a literal, the component would carry no colour at all");
        assertEquals("Denied", contentOf(parsed));
    }

    @Test
    @DisplayName("plain() does NOT interpret codes — untrusted text must not colour itself")
    void plainLeavesCodesAlone() {
        Component parsed = Msg.plain("§cnot actually red");

        assertEquals("§cnot actually red", ((TextComponent) parsed).content(),
                "running player-supplied text through the legacy parser lets whoever wrote it inject "
                        + "colour codes into a message Heimdall is attributing to itself");
        assertNotEquals(NamedTextColor.RED, parsed.color());
    }

    @Test
    void nullRendersEmptyRatherThanThrowing() {
        assertEquals(Component.empty(), Msg.legacy(null));
        assertEquals(Component.empty(), Msg.plain(null));
        assertEquals("", Msg.toLegacy(null));
    }

    @Test
    @DisplayName("ampersand codes are left alone — the bot resolves templates to § before sending")
    void ampersandCodesAreNotFormatting() {
        assertEquals("&cDenied", Msg.toLegacy(Msg.legacy("&cDenied")));
    }

    @Test
    @DisplayName("multiple codes in one message all survive the round trip")
    void multipleCodesRoundTrip() {
        assertEquals("§cDenied: §7you are not whitelisted",
                Msg.toLegacy(Msg.legacy("§cDenied: §7you are not whitelisted")));
    }

    // ── Hex round trip ───────────────────────────────────────────────────────

    /**
     * The exact bytes a Minecraft client speaks for #ff8800.
     *
     * <p>Written out rather than built from a helper on purpose: the whole bug this pins was a
     * serializer that read this form and wrote a different one, and a test that asked the same
     * serializer to produce its own expectation could not have caught it.
     */
    private static final String HEX_ORANGE_IN = "§x§f§f§8§8§0§0Denied";

    @Test
    @DisplayName("hex survives the round trip in the form the client understands")
    void hexRoundTripsInTheClientForm() {
        String out = Msg.toLegacy(Msg.legacy(HEX_ORANGE_IN));

        assertEquals(HEX_ORANGE_IN, out,
                "the writer must emit the repeated-character form the reader accepts. Without "
                        + "useUnusualXRepeatedCharacterHexFormat() this comes back as the "
                        + "compact §#ff8800 form, which no client understands — it renders as "
                        + "those literal characters on the kick screen");
    }

    @Test
    @DisplayName("the hex colour is a real colour, not literal text")
    void hexParsesToAColour() {
        Component parsed = Msg.legacy(HEX_ORANGE_IN);

        assertEquals(TextColor.color(0xff, 0x88, 0x00), anyColourOf(parsed),
                "if the §x form were not parsed, the code would survive as content");
        assertEquals("Denied", contentOf(parsed));
    }

    @Test
    @DisplayName("a component built in code serialises to the client form too")
    void hexFromComponentSerialisesToTheClientForm() {
        // The direction that actually matters in production: a dashboard template becomes a
        // Component somewhere upstream, and toLegacy is the last thing that touches it.
        Component built = Component.text("Denied").color(TextColor.color(0xff, 0x88, 0x00));

        assertEquals(HEX_ORANGE_IN, Msg.toLegacy(built));
    }

    @Test
    @DisplayName("named colours are unaffected by the hex flags")
    void namedColoursStillRoundTrip() {
        assertEquals("§cDenied", Msg.toLegacy(Msg.legacy("§cDenied")));
        assertEquals("§a§lBold green", Msg.toLegacy(Msg.legacy("§a§lBold green")));
    }

    @Test
    @DisplayName("MiniMessage tags become real colour, not literal angle brackets")
    void miniMessageParsesTags() {
        Component parsed = Msg.mini("<red>You are banned.</red>");
        assertEquals(NamedTextColor.RED, colourOf(parsed));
        assertEquals("You are banned.", contentOf(parsed));
    }

    @Test
    @DisplayName("brace placeholders are substituted and reason tags cannot restyle the screen")
    void miniTemplateEscapesReason() {
        Component parsed = Msg.miniTemplate(
                "<red>Banned.</red> <gray>{reason}</gray>",
                "reason", "<red>injected</red>",
                "player", "Steve",
                "appeal_url", "https://bans.example/abc");
        String plain = contentTree(parsed);
        assertTrue(plain.contains("injected"), "the reason text still appears");
        assertEquals(NamedTextColor.RED, colourOf(parsed),
                "a reason containing MiniMessage tags must not restyle the template");
    }

    @Test
    @DisplayName("a value ending in a backslash cannot escape the tag that follows it")
    void trailingBackslashCannotEscapeTheNextTag() {
        // /hd ban Steve 1d cheating\ - the reason ends in the character MiniMessage escapes
        // with. escapeTags does not double it, so the closing tag the template wrote became
        // literal text and yellow stayed open for the rest of the screen.
        Component parsed = Msg.miniTemplate(
                "<yellow>{reason}</yellow><red>after</red>", "reason", "cheating\\");

        String plain = TestText.plain(parsed);
        assertEquals("cheating\\after", plain,
                "the backslash is text, and the tag after it is still a tag");
        assertFalse(plain.contains("</yellow>"),
                "an unescaped trailing backslash prints the next tag instead of closing: " + plain);
    }

    @Test
    @DisplayName("a backslash in a value is still only a backslash")
    void backslashesStayLiteral() {
        assertEquals("C:\\path and <red>",
                TestText.plain(Msg.miniTemplate("{reason}", "reason", "C:\\path and <red>")),
                "doubling for MiniMessage must not double what the player reads");
    }

    // ── Broken templates ────────────────────────────────────────────

    @Test
    @DisplayName("the unparseable fallback strips tags rather than printing them at the player")
    void strippedFallbackKeepsTheWords() {
        assertEquals("Reason » griefing",
                Msg.stripTags("<gray>Reason</gray> » <yellow>griefing</yellow>"),
                "a disconnect screen full of raw tags answers none of the questions it exists for");
    }

    @Test
    @DisplayName("stripping keeps an escaped bracket, because that one is text")
    void strippingKeepsEscapedBrackets() {
        assertEquals("a <red> b",
                Msg.stripTags("a \\<red\\> b"),
                "the value was escaped on its way in, so its angle brackets are the reader's");
        assertEquals("unclosed <tag",
                Msg.stripTags("unclosed <tag"),
                "an opening bracket with no partner is a character somebody typed");
    }

    @Test
    @DisplayName("a template with a legacy code warns once, however many times it renders")
    void sectionCodeInATemplateWarnsOnce() {
        RecordingLogger logger = new RecordingLogger();
        Msg.forgetReportedTemplates();
        Msg.diagnostics(logger);
        try {
            String template = "<gray>Reason</gray> §c{reason}";
            for (int i = 0; i < 3; i++) {
                Template.fill(template, Template.values().put("reason", "griefing " + i));
            }
            Template.fill("<gray>{reason}</gray>", Template.values().put("reason", "clean"));

            List<String> warnings = logger.messagesAt(LogLevel.WARN);
            assertEquals(1, warnings.size(), warnings.toString());
            assertTrue(warnings.get(0).contains("section colour code"), warnings.get(0));
        } finally {
            Msg.diagnostics(null);
            Msg.forgetReportedTemplates();
        }
    }

    private static String contentTree(Component component) {
        StringBuilder out = new StringBuilder();
        appendContent(component, out);
        return out.toString();
    }

    private static void appendContent(Component component, StringBuilder out) {
        if (component instanceof TextComponent) {
            out.append(((TextComponent) component).content());
        }
        for (Component child : component.children()) {
            appendContent(child, out);
        }
    }

    /** Any colour in the tree, named or hex. */
    private static TextColor anyColourOf(Component component) {
        if (component.color() != null) {
            return component.color();
        }
        for (Component child : component.children()) {
            TextColor colour = anyColourOf(child);
            if (colour != null) {
                return colour;
            }
        }
        return null;
    }

    /** The colour the rendered text actually carries, whether it landed on the root or a child. */
    private static NamedTextColor colourOf(Component component) {
        if (component.color() != null) {
            return NamedTextColor.namedColor(component.color().value());
        }
        for (Component child : component.children()) {
            NamedTextColor colour = colourOf(child);
            if (colour != null) {
                return colour;
            }
        }
        return null;
    }

    /** The first non-empty text content, wherever the serializer chose to put it. */
    private static String contentOf(Component component) {
        if (component instanceof TextComponent && !((TextComponent) component).content().isEmpty()) {
            return ((TextComponent) component).content();
        }
        for (Component child : component.children()) {
            String content = contentOf(child);
            if (content != null) {
                return content;
            }
        }
        return null;
    }
}
