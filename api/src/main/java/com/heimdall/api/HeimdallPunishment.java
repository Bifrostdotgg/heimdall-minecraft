package com.heimdall.api;

import java.util.UUID;

/**
 * One active punishment, as {@link HeimdallPunishments#activePunishment} found it.
 *
 * <p>An immutable snapshot. Heimdall implements it; other plugins only read it. Methods are added
 * over time and none is ever removed or re-signatured.
 */
public interface HeimdallPunishment {

    /**
     * The punishment's id: the bot's id once it is synced, or a {@code local-} id for one issued on
     * this server that has not reached the bot yet. Never {@code null}; empty when unknown.
     */
    String id();

    /** {@link HeimdallPunishments#TYPE_BAN}, {@link HeimdallPunishments#TYPE_MUTE} or {@link HeimdallPunishments#TYPE_FREEZE}. */
    String type();

    /** The punished player. */
    UUID player();

    /** The punished player's name when it was recorded, or {@code null}. */
    String playerName();

    /** The reason staff gave. Never {@code null}; empty when none was given. */
    String reason();

    /** When it was issued, in epoch milliseconds, or {@code 0} when unknown. */
    long issuedAtMillis();

    /** When it ends, in epoch milliseconds, or {@code 0} for a permanent punishment. */
    long expiresAtMillis();

    /** Whether it never expires. The same as {@code expiresAtMillis() == 0}. */
    boolean isPermanent();

    /** The issuing staff member's name, or {@code null} (the console, or not recorded). */
    String issuerName();

    /** The issuing staff member's UUID, or {@code null} (the console, or not recorded). */
    UUID issuerUuid();

    /**
     * Whether it is hidden: enforced as normal, but kept out of in-game staff lookups unless the
     * reader holds {@code heimdall.punishments.hidden}.
     */
    boolean hidden();

    /** Whether it was issued silently, without a public announcement. */
    boolean silent();
}
