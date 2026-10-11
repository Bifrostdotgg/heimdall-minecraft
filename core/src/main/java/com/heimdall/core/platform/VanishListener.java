package com.heimdall.core.platform;

/**
 * Told when a player vanishes or reappears mid-session. See {@link Vanish#onToggle}.
 *
 * <p>Runs on the vanish plugin's own thread, usually a server's main thread, so an implementation
 * must be quick, must not block, and must not throw: the caller contains a throw, but the event it
 * was delivering is lost to every listener after it.
 */
public interface VanishListener {

    /**
     * @param player the player who changed state; may name somebody already gone
     * @param vanished {@code true} when they vanished, {@code false} when they reappeared
     * @param silent {@code true} when the change must not be announced: the vanish plugin was told
     *     not to announce it in game (PremiumVanish's {@code -s}), it happened as part of a join, or
     *     the platform cannot tell whether it was announced (both proxies, which also hear every
     *     backend's toggle a moment after that backend does). {@code false} only when the platform
     *     knows it was announced in game
     * @param timestampMs when the change was observed, taken on the event thread
     */
    void onVanishChanged(PlayerHandle player, boolean vanished, boolean silent, long timestampMs);
}
