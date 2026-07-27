package com.heimdall.platform.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.heimdall.core.text.Msg;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The §-coded string is the wire between Heimdall's Adventure and the server's.
 *
 * <p>{@link VelocityText} cannot pass a {@link Component} across that boundary — ours is shaded and
 * relocated, Velocity's is not — so it serialises to legacy text and asks the <em>server's</em>
 * {@code LegacyComponentSerializer.legacySection()} to read it back. That makes the exact dialect a
 * contract between two libraries that never see each other's types, and nothing at the boundary
 * would fail loudly if they disagreed: a kick reason would simply arrive with visible junk in it.
 *
 * <p>These tests stand in for the reflective call by doing what it does — {@code legacySection()},
 * by name, on the same input {@link Msg#toLegacy} produces. In this source set there is only one
 * Adventure on the classpath, so this pins the <em>format</em> rather than the relocation; the
 * format is the half that can silently drift.
 *
 * <p>The hex case is the one that mattered: {@code Msg} used to emit {@code §#RRGGBB}, which
 * {@code legacySection()} does not read as a colour at all.
 */
class VelocityTextWireTest {

    /** The repeated-character form vanilla speaks, written out rather than generated. */
    private static final String HEX_ORANGE =
            "§x§f§f§8§8§0§0Denied";

    /** Exactly what {@link VelocityText} resolves reflectively at runtime. */
    private static final LegacyComponentSerializer SERVER_SIDE =
            LegacyComponentSerializer.legacySection();

    @Test
    @DisplayName("the server's legacySection() reads the hex form Msg now writes")
    void serverReadsOurHex() {
        String onTheWire = Msg.toLegacy(
                Component.text("Denied").color(TextColor.color(0xff, 0x88, 0x00)));
        assertEquals(HEX_ORANGE, onTheWire, "guards the input to the assertion below");

        Component readBack = SERVER_SIDE.deserialize(onTheWire);

        assertEquals(TextColor.color(0xff, 0x88, 0x00), colourOf(readBack),
                "Velocity's own serializer must see a colour here, not literal characters. If Msg "
                        + "reverts to the compact §#RRGGBB form this reads as text and the "
                        + "player sees the code on their disconnect screen");
        assertEquals("Denied", contentOf(readBack));
    }

    @Test
    @DisplayName("named colours cross the same wire unharmed")
    void serverReadsNamedColours() {
        Component readBack = SERVER_SIDE.deserialize(Msg.toLegacy(Msg.legacy("§cRefused")));

        assertEquals(NamedTextColor.RED, colourOf(readBack));
        assertEquals("Refused", contentOf(readBack));
    }

    @Test
    @DisplayName("an empty reason is a component, not a null")
    void emptyIsStillAComponent() {
        assertNotNull(SERVER_SIDE.deserialize(Msg.toLegacy(Component.empty())));
    }

    private static TextColor colourOf(Component component) {
        if (component.color() != null) {
            return component.color();
        }
        for (Component child : component.children()) {
            TextColor colour = colourOf(child);
            if (colour != null) {
                return colour;
            }
        }
        return null;
    }

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
