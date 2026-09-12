package com.heimdall.core.text;

import com.heimdall.core.log.HeimdallLogger;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
     * Parses MiniMessage, falling back to the template's plain text if it is unusable.
     *
     * <p>A thrown parse must not take out a login or a kick. The dashboard owns the templates;
     * a typo there is a bad screen, not a locked network.
     *
     * <p>See {@link #mini(String, String)} for what the fallback does and why it is not the raw
     * string.
     */
    public static Component mini(String text) {
        return mini(text, text);
    }

    /**
     * Parses MiniMessage, falling back to the template's plain text if it is unusable.
     *
     * <h2>The fallback strips tags rather than printing them</h2>
     *
     * <p>It used to be {@code Component.text(text)}, which shows the reader every tag the guild
     * wrote: {@code <gray>Reason</gray> <dark_gray>»</dark_gray> <yellow>griefing</yellow>} on a
     * disconnect screen, in place of a sentence. A template nobody can parse should cost the
     * screen its colours, not its legibility, and by this point the values are substituted, so the
     * reason, the length and the appeal link all survive the strip.
     *
     * <p><strong>This is a backstop, not a hot path.</strong> MiniMessage 4.13.1 in its default
     * non-strict mode does not throw for anything a guild can type: a malformed tag comes back as
     * literal text, an unclosed one is simply never closed. Probed, at the pinned version, against
     * every mangled tag worth trying. What it protects against is a future version, a strict-mode
     * change, or an input nobody thought of - and the cost of being wrong here is a kick screen
     * nobody can read, so it keeps its fallback and its warning.
     *
     * @param sourceKey what the one warning is deduplicated on. {@link Template#render} passes the
     *     template, so a broken screen is reported once rather than once per punishment
     */
    public static Component mini(String text, String sourceKey) {
        if (text == null || text.isEmpty()) {
            return Component.empty();
        }
        try {
            return MINI.deserialize(text);
        } catch (RuntimeException e) {
            reportBrokenTemplate(text, sourceKey == null ? text : sourceKey, e);
            return Component.text(stripTags(text));
        }
    }

    /**
     * Where a template that will not render as written is reported, or {@code null} when nothing
     * is listening.
     *
     * <p>Static because {@link Msg} is static and every caller that renders a template is several
     * layers below anything holding a logger - a login verdict, a chat gate, a static screen
     * renderer. Threading a logger through all of them for a line that fires once per broken
     * template would be a worse trade than one field set at boot. Left unset in a unit test, where
     * there is nothing to report to and nothing that needs reporting.
     */
    private static volatile HeimdallLogger diagnostics;

    /** Templates already reported, by hash. See {@link #REPORT_LIMIT}. */
    private static final Map<Integer, Boolean> REPORTED =
            new ConcurrentHashMap<Integer, Boolean>();

    /**
     * The most distinct templates that will ever be reported.
     *
     * <p>A guild has nine screen segments and a plugin instance serves one guild, so the real
     * number is single digits. The bound is here because the map is keyed on something a guild
     * controls, and a log-once set that can grow without limit is not a log-once set.
     */
    static final int REPORT_LIMIT = 64;

    /**
     * Wires the warning sink. Called once, from the runtime that owns the logger.
     *
     * <p>Idempotent and safe to call again: the last caller wins, which is what a plugin reload
     * wants.
     *
     * <p><strong>Also forgets what has already been reported.</strong> Re-wiring happens on a
     * reload or a re-enable, which is the point at which a guild that went and fixed its templates
     * deserves a fresh budget rather than a set still full of the ones it edited. Nothing is lost:
     * a template still broken re-reports the first time it renders.
     */
    public static void diagnostics(HeimdallLogger logger) {
        diagnostics = logger;
        REPORTED.clear();
    }

    /**
     * Warns, once per distinct template, about a template that will not render as it reads.
     *
     * <p>Called by {@link Template#fill}, which is the single door every guild-authored template
     * goes through. Keyed on the template rather than on the finished text, so a broken screen is
     * reported once rather than once per punishment.
     *
     * <h2>What it catches, and why it is only a warning</h2>
     *
     * <p>A {@code §} legacy colour code. MiniMessage does not reject one - it carries it through
     * as ordinary content - and the Bukkit family then serialises the finished component back to
     * §-codes on its way to a kick screen, where the client reads it as a real colour. So the
     * guild's code works, and nothing after it can turn it off: a {@code </yellow>} closes a tag
     * the client never saw, and the rest of the screen stays whatever the stray code set. It is
     * the one construct that looks fine in the editor and is wrong on the screen, which is exactly
     * the kind of thing worth one line in the log.
     *
     * <p>Not an error and not a refusal: the screen still renders, the bot refuses {@code §} at
     * save, and a template already in a guild's config predates that refusal. This is the line
     * that tells an operator why their colours are wrong.
     */
    public static void checkTemplate(String template) {
        if (template == null || template.indexOf(LegacyComponentSerializer.SECTION_CHAR) < 0) {
            return;
        }
        warnOnce(template, "it contains a legacy section colour code. MiniMessage does not"
                + " recognise one, so it reaches the client as a raw colour that no later tag can"
                + " close - write the colour as a tag such as <red> instead");
    }

    private static void reportBrokenTemplate(String text, String sourceKey, RuntimeException cause) {
        warnOnce(sourceKey, "MiniMessage refused it (" + cause + "), so it was shown with its"
                + " formatting removed");
    }

    /** One warning per distinct template, to a bounded set of templates. */
    private static void warnOnce(String sourceKey, String problem) {
        HeimdallLogger logger = diagnostics;
        if (logger == null) {
            return;
        }
        Integer key = Integer.valueOf(sourceKey.hashCode());
        // Whether this template is new is asked BEFORE the budget, not after. A template that has
        // already been reported has to cost nothing at all - it renders again on every punishment
        // - and checking the bound first made a full set turn every repeat into a size() call and
        // a branch that could never log anyway.
        if (REPORTED.putIfAbsent(key, Boolean.TRUE) != null) {
            return;
        }
        if (REPORTED.size() > REPORT_LIMIT) {
            // This one took the set past its bound. Take it back out rather than leaving it: the
            // entry would otherwise count against a limit it is not allowed to benefit from, and
            // enough distinct templates would grow the map past the bound it exists to enforce.
            REPORTED.remove(key);
            return;
        }
        logger.warn("a message template will not render as written: " + problem
                + ". Edit it on the dashboard's Minecraft page.");
    }

    /**
     * The text of a MiniMessage string with every tag taken out.
     *
     * <p>Hand-written rather than a regex because a backslash is MiniMessage's escape character:
     * an escaped {@code \<red\>} in a substituted value is text the reader should keep, and a
     * pattern for {@code <...>} would eat it. Walking the string once handles both in the order
     * MiniMessage itself would.
     */
    static String stripTags(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()) {
                out.append(text.charAt(i + 1));
                i += 2;
                continue;
            }
            if (c == '<') {
                int close = text.indexOf('>', i + 1);
                if (close > i) {
                    i = close + 1;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
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
        // Reported against the template rather than the filled text, for the reason in
        // #mini(String, String): the values differ per message and the template does not.
        return mini(filled, template);
    }

    /**
     * MiniMessage-escapes untrusted text so it cannot introduce tags.
     *
     * <h2>The backslash goes first, because {@code escapeTags} does not touch it</h2>
     *
     * <p>A backslash is MiniMessage's own escape character, and {@code escapeTags} only prefixes
     * tags with one - it never doubles the ones already in the text. So a value that ends in a
     * backslash escapes whatever the template wrote next: {@code /hd ban Steve 1d cheating\}
     * filled into {@code <yellow>{reason}</yellow>} produced {@code <yellow>cheating\</yellow>},
     * which prints {@code </yellow>} as literal text and leaves yellow open for the rest of the
     * screen. Doubling first makes the value's own backslash a backslash again, and the tag after
     * it a tag.
     */
    public static String escapeMini(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String escaped = text.replace("\\", "\\\\");
        try {
            return MINI.escapeTags(escaped);
        } catch (RuntimeException e) {
            return escaped.replace("<", "").replace(">", "");
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
