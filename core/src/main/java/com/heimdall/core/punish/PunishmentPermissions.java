package com.heimdall.core.punish;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Which node runs each punishment verb, under either spelling.
 *
 * <p>One table, because two places ask: the punishments module registers each root alias
 * ({@code /ban}) with its node, and the admin tree lets a holder of the same node run
 * {@code /hd ban}. They were two copies of the same list, and a verb moved to a different node in
 * one of them would have let a moderator run a command under one spelling and not the other.
 *
 * <p>{@code import} is absent on purpose: it writes a guild's whole history in bulk, so only
 * {@code heimdall.admin} runs it.
 */
public final class PunishmentPermissions {

    private static final Map<String, String> BY_VERB;

    static {
        Map<String, String> nodes = new LinkedHashMap<String, String>();
        nodes.put("ban", "heimdall.punishments.ban");
        nodes.put("tempban", "heimdall.punishments.ban");
        nodes.put("ipban", "heimdall.punishments.ipban");
        nodes.put("mute", "heimdall.punishments.mute");
        nodes.put("tempmute", "heimdall.punishments.mute");
        nodes.put("unmute", "heimdall.punishments.unban");
        nodes.put("kick", "heimdall.punishments.kick");
        nodes.put("warn", "heimdall.punishments.warn");
        nodes.put("unwarn", "heimdall.punishments.unban");
        nodes.put("unban", "heimdall.punishments.unban");
        nodes.put("rollback", "heimdall.punishments.unban");
        nodes.put("history", "heimdall.punishments.history");
        nodes.put("staffhistory", "heimdall.punishments.history");
        nodes.put("banlist", "heimdall.punishments.history");
        nodes.put("dupeip", "heimdall.punishments.dupeip");
        nodes.put("iphistory", "heimdall.punishments.dupeip");
        BY_VERB = Collections.unmodifiableMap(nodes);
    }

    private PunishmentPermissions() {
    }

    /**
     * The node that runs {@code verb}.
     *
     * @throws IllegalArgumentException for a verb with no node, so a new verb cannot be wired up
     *     without deciding who may run it
     */
    public static String nodeFor(String verb) {
        String node = verb == null ? null : BY_VERB.get(verb.toLowerCase(Locale.ROOT));
        if (node == null) {
            throw new IllegalArgumentException("no punishment node for verb " + verb);
        }
        return node;
    }

    /** Every verb and its node, in the order the admin tree lists them. */
    public static Map<String, String> byVerb() {
        return BY_VERB;
    }
}
