package com.heimdall.stubbot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The bot's capability rules, transcribed.
 *
 * <p>This is a port of {@code bot/plugins/minecraft/lib/moduleConfig.ts} on the bot's
 * {@code feat/minecraft-v3-protocol} branch, and it is a port rather than an approximation on
 * purpose. The stub's whole value is that a plugin tested against it behaves the same way against
 * production; a "close enough" acceptance rule is exactly the kind of difference that produces a
 * client which passes every test and receives configuration for nothing.
 *
 * <p>Every behaviour below is one the bot's own unit tests pin:
 *
 * <ul>
 *   <li><strong>Exact major match.</strong> {@code whitelist@2} against a bot that supports major 1
 *       is <em>dropped</em>, not downgraded.
 *   <li><strong>A bare id is major 1.</strong> {@code whitelist} with no {@code @N} is accepted, and
 *       echoed back <em>verbatim</em> — not normalised to {@code whitelist@1}.
 *   <li><strong>A non-numeric major is also 1.</strong> {@code whitelist@beta} parses to "no major",
 *       which falls back to 1 and is accepted. Surprising, and it is what the bot does.
 *   <li><strong>Unknown modules are dropped silently.</strong> No error frame, no reason — the
 *       capability is simply absent from {@code accepted}, which is the only signal a client gets.
 *   <li><strong>Order and duplicates.</strong> Declaration order is preserved and exact-string
 *       duplicates collapse to their first occurrence.
 * </ul>
 */
final class CapabilityNegotiation {

    /** The protocol version the bot declares in {@code identify_ack}. */
    static final int BOT_PROTOCOL_VERSION = 3;

    /**
     * The modules the bot has configuration for, and the single major it speaks for each.
     *
     * <p>{@code modules} and {@code config} are here too — they are negotiated and acknowledged like
     * any other capability. What makes them different is downstream: they never appear as keys in a
     * config document, so nothing is ever pushed for them.
     */
    private static final Map<String, Integer> SUPPORTED_MAJORS;

    /** The module ids a config document may contain. */
    static final List<String> MANAGED_MODULE_IDS =
            Collections.unmodifiableList(java.util.Arrays.asList(
                    "whitelist", "rolesync", "console", "health", "bridge"));

    /** Negotiated and acked, but never a key in a config document. */
    static final List<String> META_CAPABILITY_IDS =
            Collections.unmodifiableList(java.util.Arrays.asList("modules", "config"));

    static {
        Map<String, Integer> majors = new java.util.LinkedHashMap<String, Integer>();
        majors.put("whitelist", 1);
        majors.put("rolesync", 1);
        majors.put("console", 1);
        majors.put("health", 1);
        majors.put("bridge", 1);
        majors.put("modules", 1);
        majors.put("config", 1);
        SUPPORTED_MAJORS = Collections.unmodifiableMap(majors);
    }

    private CapabilityNegotiation() {
    }

    /**
     * What the bot will honour, out of what the client declared.
     *
     * @return the accepted capabilities in the client's own spelling and order; never {@code null}
     */
    static List<String> accepted(List<String> declared) {
        List<String> out = new ArrayList<String>();
        Set<String> seen = new LinkedHashSet<String>();
        if (declared == null) {
            return out;
        }
        for (String raw : declared) {
            if (raw == null) {
                continue;
            }
            String capability = raw.trim();
            if (capability.isEmpty() || !seen.add(capability)) {
                continue;
            }
            Integer supported = SUPPORTED_MAJORS.get(moduleId(capability));
            if (supported == null) {
                continue;
            }
            // A capability with no parseable major reads as major 1 — which is how a bare
            // "whitelist" is accepted, and also how "whitelist@beta" is.
            Integer major = major(capability);
            if (major != null && !major.equals(supported)) {
                continue;
            }
            out.add(capability);
        }
        return out;
    }

    /** {@code whitelist@1} → {@code whitelist}. Trimmed and lower-cased, like the bot's. */
    static String moduleId(String capability) {
        int at = capability.indexOf('@');
        String id = at < 0 ? capability : capability.substring(0, at);
        return id.trim().toLowerCase(Locale.ROOT);
    }

    /** The major, or {@code null} when there is no {@code @} or the suffix is not all digits. */
    static Integer major(String capability) {
        int at = capability.indexOf('@');
        if (at < 0) {
            return null;
        }
        String raw = capability.substring(at + 1).trim();
        if (raw.isEmpty()) {
            return null;
        }
        for (int i = 0; i < raw.length(); i++) {
            if (!Character.isDigit(raw.charAt(i))) {
                return null;
            }
        }
        try {
            return Integer.valueOf(raw);
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    /**
     * The module ids a connection may be sent configuration for.
     *
     * <p>Derived from the <em>accepted</em> capabilities by base name, so {@code whitelist@1} allows
     * the {@code whitelist} key through. {@code modules} and {@code config} contribute nothing here
     * simply because no config document has keys by those names.
     */
    static Set<String> allowedModuleIds(List<String> acceptedCapabilities) {
        Set<String> allowed = new LinkedHashSet<String>();
        for (String capability : acceptedCapabilities) {
            allowed.add(moduleId(capability));
        }
        return allowed;
    }
}
