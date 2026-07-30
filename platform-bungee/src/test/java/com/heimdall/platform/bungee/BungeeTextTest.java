package com.heimdall.platform.bungee;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.text.Msg;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The one conversion between Heimdall's text and BungeeCord's, driven end to end.
 *
 * <p>Small, and worth having anyway: this is the path every kick screen, every command reply and
 * every relayed message on the proxy goes down, and its failure mode is silent — a message that
 * arrives as literal {@code §c} characters, or one whose colour is simply gone, looks like a
 * formatting nicety rather than the bug it is.
 *
 * <p>It also pins the choice of serializer. If anybody ever swaps the legacy round trip for a JSON
 * one (departure D76 explains why that was rejected), the assertions below are what has to be
 * re-satisfied rather than quietly re-baselined.
 */
class BungeeTextTest {

    private final BungeeText text = new BungeeText();

    @Test
    @DisplayName("text survives the trip")
    void plainTextSurvives() {
        BaseComponent[] converted = text.toComponents(Msg.legacy("You are not whitelisted."));

        assertEquals("You are not whitelisted.", TextComponent.toPlainText(converted));
    }

    @Test
    @DisplayName("a colour code becomes a real colour, not four literal characters")
    void coloursAreParsed() {
        // The failure this catches: handing BungeeCord the raw §-coded STRING instead of parsing it
        // renders "§cYou are not whitelisted." on the kick screen, section sign and all.
        BaseComponent[] converted = text.toComponents(Msg.legacy("§cYou are not whitelisted."));

        assertEquals("You are not whitelisted.", TextComponent.toPlainText(converted),
                "the colour code must not survive as text");
        assertEquals(ChatColor.RED, converted[0].getColor());
    }

    @Test
    @DisplayName("a hex colour crosses in the §x form BungeeCord actually parses")
    void hexColoursCross() {
        // Msg emits §x§R§R§G§G§B§B — vanilla's own repeated-character form — rather than §#RRGGBB,
        // which no client and no proxy understands. BungeeCord has read the former since 1.16, so
        // this is the assertion that the two ends agree on the dialect rather than merely on the
        // idea of hex.
        BaseComponent[] converted = text.toComponents(
                Msg.legacy("§x§f§f§8§8§0§0Denied"));

        assertEquals("Denied", TextComponent.toPlainText(converted));
        assertEquals(ChatColor.of("#ff8800"), converted[0].getColor());
    }

    @Test
    @DisplayName("an empty message still yields something a kick screen can take")
    void emptyIsStillAComponent() {
        // Every caller hands the result straight to an API that rejects a null or an empty array —
        // disconnect(BaseComponent...) among them — so "nothing to say" must not become "nothing to
        // pass".
        BaseComponent[] converted = text.toComponents(Msg.legacy(""));

        assertTrue(converted.length > 0, "an empty array would make disconnect() throw");
        assertEquals("", TextComponent.toPlainText(converted));
    }
}
