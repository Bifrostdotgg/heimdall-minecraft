package com.heimdall.core.text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
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
 * <p>Two input formats: §-coded legacy for bot-owned chat lines, and MiniMessage for punishment
 * screens pushed in {@code config.push}. Placeholder substitution for screens lives next to the
 * MiniMessage parser so a player-supplied {@code {reason}} cannot inject tags.
 *
 * <h2>Thread safety</h2>
 *
 * <p>Stateless and safe from any thread. Serializer instances are immutable and the shared ones are
 * reused rather than rebuilt per call — the login path calls this.
 */
public final class Msg {

    /**
     * The section-sign serializer, with hex colours in the form a Minecraft client understands.
     *
     * <p>{@code legacySection()} rather than {@code legacyAmpersand()}: the bot's message templates
     * arrive already resolved to §, which is what a client reads.
     *
     * <h2>Both hex flags, because one without the other is broken</h2>
     *
     * <p>{@code hexColors()} on its own is asymmetric, and it is the shape of bug that survives
     * review precisely because the round trip is never written down anywhere. It makes the parser
     * <em>accept</em> {@code §x§R§R§G§G§B§B} — the repeated-character form vanilla actually speaks —
     * while the writer <em>emits</em> {@code §#RRGGBB}, which no client understands. That renders as
     * the literal characters: {@code §x§f§f§8§8§0§0Denied} goes in and {@code §#ff8800Denied} comes
     * out.
     *
     * <p>Every user-visible string on the Bukkit family passes through {@link #toLegacy} on its way
     * to a kick screen, a pre-login denial or a chat message, so the asymmetry would corrupt all of
     * them the moment a dashboard template used a hex colour.
     * {@code useUnusualXRepeatedCharacterHexFormat()} makes the writer speak the reader's dialect.
     *
     * <p>Harmless on 1.8.8: the {@code §x} form is only produced when the input carried a hex
     * colour, and nothing produces one until a template says so.
     */
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character(LegacyComponentSerializer.SECTION_CHAR)
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();

    private static final MiniMessage MINI = MiniMessage.miniMessage();

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
     * Parses MiniMessage, falling back to the raw text if the template is unusable.
     *
     * <p>A thrown parse must not take out a login or a kick. The dashboard owns the templates;
     * a typo there is a bad screen, not a locked network.
     */
    public static Component mini(String text) {
        if (text == null || text.isEmpty()) {
            return Component.empty();
        }
        try {
            return MINI.deserialize(text);
        } catch (RuntimeException e) {
            return Component.text(text);
        }
    }

    /**
     * MiniMessage template with brace placeholders.
     *
     * <p>Values are tag-escaped before substitution so a reason containing {@code <red>} cannot
     * restyle the rest of the screen. Keys are {@code {name}} in the template.
     *
     * @param replacements even-length name/value pairs; a dangling last name is ignored
     */
    public static Component miniTemplate(String template, String... replacements) {
        if (template == null || template.isEmpty()) {
            return Component.empty();
        }
        String filled = template;
        if (replacements != null) {
            for (int i = 0; i + 1 < replacements.length; i += 2) {
                String key = replacements[i];
                String value = replacements[i + 1];
                if (key == null) {
                    continue;
                }
                filled = filled.replace("{" + key + "}", escapeMini(value));
            }
        }
        return mini(filled);
    }

    /** MiniMessage-escapes untrusted text so it cannot introduce tags. */
    public static String escapeMini(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        try {
            return MINI.escapeTags(text);
        } catch (RuntimeException e) {
            return text.replace("<", "").replace(">", "");
        }
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
