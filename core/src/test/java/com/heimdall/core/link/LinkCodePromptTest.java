package com.heimdall.core.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.testing.TestText;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The words a player is shown with a link code, and the expiry they are computed from. */
class LinkCodePromptTest {

    /** 2026-10-19T12:00:00Z. */
    private static final long NOON = 1_792_411_200_000L;

    @Test
    @DisplayName("the bot's ISO expiry is read with Z, with an offset, and as epoch digits")
    void expiryForms() {
        assertEquals(NOON + 300_000L,
                LinkCodePrompt.of("123456", "2026-10-19T12:05:00.000Z").expiresAtMs());
        assertEquals(NOON + 300_000L,
                LinkCodePrompt.of("123456", "2026-10-19T13:05:00+01:00").expiresAtMs(),
                "Instant.parse on Java 8 refuses an offset; the prompt must not");
        assertEquals(NOON, LinkCodePrompt.of("123456", String.valueOf(NOON)).expiresAtMs());
    }

    @Test
    @DisplayName("an unreadable or missing expiry is unknown, never expired")
    void unknownExpiry() {
        LinkCodePrompt garbled = LinkCodePrompt.of("123456", "next tuesday");
        assertEquals(0L, garbled.expiresAtMs());
        assertFalse(garbled.isExpired(Long.MAX_VALUE),
                "a code with no readable expiry is still a code the player can use");
        assertEquals("", garbled.expiryText(NOON));
        assertEquals(0L, LinkCodePrompt.of("123456", null).expiresAtMs());
    }

    @Test
    @DisplayName("the expiry sentence is relative and rounded, with its own wording for the last minute")
    void expiryWording() {
        LinkCodePrompt prompt = LinkCodePrompt.of("123456", "2026-10-19T12:05:00Z");
        assertEquals("This code expires in about 5 minutes.", prompt.expiryText(NOON));
        assertEquals("This code expires in about 5 minutes.", prompt.expiryText(NOON + 1_000L));
        assertEquals("This code expires in about 1 minute.", prompt.expiryText(NOON + 230_000L));
        assertEquals("This code expires in less than a minute.", prompt.expiryText(NOON + 250_000L));
        assertFalse(prompt.isExpired(NOON + 299_999L));
        assertTrue(prompt.isExpired(NOON + 300_000L));
    }

    @Test
    @DisplayName("a blank code is refused, and a section sign cannot restyle what follows it")
    void codeIsCleaned() {
        assertThrows(IllegalArgumentException.class, () -> LinkCodePrompt.of("  ", null));
        assertThrows(IllegalArgumentException.class, () -> LinkCodePrompt.of(null, null));
        // Only the sign goes: the character after it may be a digit of the code, so it stays.
        assertEquals("12k3456", LinkCodePrompt.of(" 12\u00A7k3456 ", null).code());
        assertEquals("123456", LinkCodePrompt.of("12\u00A73456", null).code());
    }

    @Test
    @DisplayName("chat: the code to click and copy, the instruction and the expiry, then the warning")
    void chatLines() {
        LinkCodePrompt prompt = LinkCodePrompt.of("123456", "2026-10-19T12:05:00Z");

        List<Component> lines = prompt.chatLines(NOON);

        assertEquals(3, lines.size());
        assertEquals("Your Discord link code: 123456", TestText.plain(lines.get(0)));
        assertEquals("Enter this code in Discord: open the linking panel and press Confirm Code. "
                + "This code expires in about 5 minutes.", TestText.plain(lines.get(1)));
        assertEquals("Only enter this code yourself, in Discord. Never give it to anyone. "
                + "If you didn't ask for it, ignore it.", TestText.plain(lines.get(2)),
                "always there: the attack is somebody asking the player to read the code out");

        Component code = find(lines.get(0), "123456");
        assertNotNull(code, "the code must be its own component so the click lands on it");
        assertNotNull(code.clickEvent());
        assertEquals(ClickEvent.Action.COPY_TO_CLIPBOARD, code.clickEvent().action());
        assertEquals("123456", code.clickEvent().value());
        assertNotNull(code.hoverEvent(), "the hover is how a player learns the code is clickable");
    }

    @Test
    @DisplayName("who asked, when the bot says, goes before the warning")
    void requestedByLine() {
        LinkCodePrompt prompt = LinkCodePrompt.of("123456", null, "Wild (@lerndmina)");

        List<Component> lines = prompt.chatLines(NOON);

        assertEquals(4, lines.size());
        assertEquals("Requested by Wild (@lerndmina) in Discord.", TestText.plain(lines.get(2)));
        assertEquals(LinkCodePrompt.WARNING, TestText.plain(lines.get(3)));
        assertEquals("", LinkCodePrompt.of("123456", null, "   ").requestedByText(),
                "a blank requester is no requester");
    }

    @Test
    @DisplayName("a display name cannot restyle, disguise or flood the requested-by line")
    void requesterIsCleaned() {
        // The sign goes and the character after it stays, as for the code: harmless once the sign
        // that would have made it a colour is gone.
        assertEquals("Wildc (@lerndmina)",
                LinkCodePrompt.of("1", null, " Wild\u00A7c \n\t (@lerndmina)\u202E\u200B ").requestedBy());
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            huge.append('x');
        }
        assertEquals(LinkCodePrompt.MAX_REQUESTED_BY,
                LinkCodePrompt.of("1", null, huge.toString()).requestedBy().length());
    }

    @Test
    @DisplayName("with no known expiry the second line is the instruction alone")
    void chatWithoutExpiry() {
        List<Component> lines = LinkCodePrompt.of("123456", null).chatLines(NOON);
        assertEquals(LinkCodePrompt.INSTRUCTION, TestText.plain(lines.get(1)));
    }

    /** The component in a tree whose own content is exactly {@code text}. */
    private static Component find(Component root, String text) {
        List<Component> queue = new ArrayList<Component>();
        queue.add(root);
        while (!queue.isEmpty()) {
            Component next = queue.remove(0);
            if (next instanceof net.kyori.adventure.text.TextComponent
                    && text.equals(((net.kyori.adventure.text.TextComponent) next).content())) {
                return next;
            }
            queue.addAll(next.children());
        }
        return null;
    }
}
