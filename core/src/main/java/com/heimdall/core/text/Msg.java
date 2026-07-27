package com.heimdall.core.text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * Turns the text the bot sends into the {@link Component} the platforms render.
 *
 * <p><strong>Everything user-visible in v3 is a {@code Component}, not a {@code String}.</strong>
 * v2 passed §-coded strings all the way down and each platform re-interpreted them: Bukkit's kick
 * screen, Velocity's disconnect reason and the console each wanted a different type, and the
 * conversions were spread across the call sites. A single model type converted once at the edge is
 * what makes a deny reason usable identically on a 1.8.8 kick screen and a Velocity proxy.
 *
 * <h2>What 1b deliberately does not have</h2>
 *
 * <p>MiniMessage. It is the obvious next step and it is <em>not</em> here, because the templates it
 * would parse are dashboard-owned and that work has not landed. Adding the parser now would mean
 * shipping a syntax nothing produces, and the first thing anyone would do with it is hand-write
 * templates in a config file that phase 1d is about to take away.
 *
 * <p>So the input format is the one the bot actually sends today: §-coded legacy text.
 *
 * <h2>Thread safety</h2>
 *
 * <p>Stateless and safe from any thread. {@link LegacyComponentSerializer} instances are immutable
 * and the shared one is reused rather than rebuilt per call — the login path calls this.
 */
public final class Msg {

    /**
     * The section-sign serializer, with hex colours enabled.
     *
     * <p>{@code legacySection()} rather than {@code legacyAmpersand()}: the bot's message templates
     * arrive already resolved to §, which is what a Minecraft client understands. Hex support is
     * harmless on 1.8.8 — the serializer only produces the {@code §x§r§r§g§g§b§b} form when the
     * input contains it, and nothing on the legacy path does.
     */
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character(LegacyComponentSerializer.SECTION_CHAR)
            .hexColors()
            .build();

    private Msg() {
    }

    /**
     * Parses §-coded legacy text.
     *
     * @param text the message; {@code null} renders as empty rather than throwing, because the
     *     alternative is a missing remote-config message taking out a login
     */
    public static Component legacy(String text) {
        return text == null ? Component.empty() : LEGACY.deserialize(text);
    }

    /**
     * Wraps text with no formatting applied at all.
     *
     * <p>For anything that came from a player or another plugin. Running untrusted text through
     * {@link #legacy} lets whoever wrote it inject colour codes into a message Heimdall is
     * attributing to itself.
     *
     * @param text the message; {@code null} renders as empty
     */
    public static Component plain(String text) {
        return text == null ? Component.empty() : Component.text(text);
    }

    /**
     * The §-coded form of a component — the inverse of {@link #legacy}.
     *
     * <p>Needed wherever a component has to cross an interface that predates Adventure: an RCON
     * command string, a 1.8.8 API that only takes a String, a log line.
     */
    public static String toLegacy(Component component) {
        return component == null ? "" : LEGACY.serialize(component);
    }
}
