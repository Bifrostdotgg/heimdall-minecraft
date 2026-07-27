package com.heimdall.core.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
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
