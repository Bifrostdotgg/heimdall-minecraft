package com.heimdall.api;

import java.util.UUID;

/**
 * Read-only questions about Heimdall's active punishments, for other server plugins.
 *
 * <p>The typical caller is a plugin with its own way for a player to talk, such as a speech-bubble
 * command, a mail system or a sign editor, that has to refuse a muted player the same way Heimdall's
 * own chat gate does.
 *
 * <h2>Getting hold of one</h2>
 *
 * <p>On the Bukkit family it is registered with the {@code ServicesManager}:
 *
 * <pre>{@code
 * HeimdallPunishments punishments =
 *         Bukkit.getServicesManager().load(HeimdallPunishments.class);
 * }</pre>
 *
 * <p>{@link HeimdallPunishmentsProvider#get()} is the portable way to ask on any platform, Velocity
 * and BungeeCord included. A plugin that only soft-depends on Heimdall should reach either one
 * reflectively, or behind a class it loads only when Heimdall is present, so it keeps loading on a
 * server without Heimdall.
 *
 * <p>The object is permanent for the life of the plugin. It survives a hot swap of Heimdall's core,
 * so it may be cached.
 *
 * <h2>Where the answers come from</h2>
 *
 * <p>Every method reads Heimdall's in-memory mirror of active punishments, the same one its own
 * login, chat and command gates read. No method makes a network or database call, takes a lock a
 * server thread holds, or blocks, so each is safe and cheap from any thread: the main thread, Paper's
 * async chat threads, a proxy's event threads.
 *
 * <p>The mirror is network-wide and re-synced from the bot every few minutes and on every
 * {@code punish.apply} or {@code punish.revoke} the bot pushes, so an answer can trail the dashboard
 * by however long that push takes to arrive.
 *
 * <h2>When nothing can answer</h2>
 *
 * <p>While no core is running (between two cores during a hot swap, or after a core failed to
 * start), and while the punishments module is switched off, every query answers {@code false} or
 * {@code null}, and {@link #isAvailable()} says why. Nothing here throws for that reason. A caller
 * that must fail closed should check {@link #isAvailable()} itself.
 *
 * <h2>Hidden punishments</h2>
 *
 * <p>A punishment marked hidden is still enforced; it is only kept out of in-game staff lookups
 * unless the reader holds {@code heimdall.punishments.hidden}. A plugin that tells anyone other than
 * the punished player about a punishment should check {@link #isMuteHidden(UUID)} or
 * {@link HeimdallPunishment#hidden()} and keep a hidden one to holders of that permission.
 *
 * <p>Methods are added to this interface over time. None is ever removed or re-signatured.
 */
public interface HeimdallPunishments {

    /** {@link HeimdallPunishment#type()} of a ban. */
    String TYPE_BAN = "ban";

    /** {@link HeimdallPunishment#type()} of a mute. */
    String TYPE_MUTE = "mute";

    /** {@link HeimdallPunishment#type()} of a freeze. */
    String TYPE_FREEZE = "freeze";

    /**
     * Whether a core is running with the punishments module enabled, so the other answers mean
     * something. While this is {@code false} every query answers "not punished".
     */
    boolean isAvailable();

    /**
     * Whether {@code player} has an active, unexpired mute.
     *
     * @param player the player's UUID; {@code null} answers {@code false}
     */
    boolean isMuted(UUID player);

    /**
     * Whether {@code player}'s active mute is a hidden one. {@code false} when there is no active
     * mute.
     *
     * @param player the player's UUID; {@code null} answers {@code false}
     */
    boolean isMuteHidden(UUID player);

    /**
     * Whether {@code player} has an active, unexpired ban on their account. IP, subnet and country
     * bans are matched against a connection, not a UUID, and are not reported here.
     *
     * @param player the player's UUID; {@code null} answers {@code false}
     */
    boolean isBanned(UUID player);

    /**
     * Whether {@code player} has an active, unexpired freeze.
     *
     * @param player the player's UUID; {@code null} answers {@code false}
     */
    boolean isFrozen(UUID player);

    /**
     * The active punishment of {@code type} on {@code player}, or {@code null} when there is none.
     *
     * <p>A new snapshot on each call. It does not change when the punishment is later revoked or
     * edited; ask again.
     *
     * @param player the player's UUID; {@code null} answers {@code null}
     * @param type {@link #TYPE_BAN}, {@link #TYPE_MUTE} or {@link #TYPE_FREEZE}; anything else
     *     answers {@code null}
     */
    HeimdallPunishment activePunishment(UUID player, String type);
}
