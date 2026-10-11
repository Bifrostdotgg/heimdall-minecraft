package com.heimdall.core.platform;

import com.heimdall.core.util.Registration;

/**
 * Whether a player is hidden by a vanish plugin, as platform-free code is allowed to see it.
 * Departure D88.
 *
 * <h2>Why a relay needs to know</h2>
 *
 * <p>A staff member who joins vanished is, as far as everyone in game can tell, not on the server:
 * the vanish plugin swallows the join message and hides them from the tab list. A Discord bridge
 * that announced "X joined the server" anyway would publish exactly what the server hid. So the
 * bridge asks this before it relays a join, a leave or a death, and it listens for the moment a
 * player vanishes or reappears mid-session.
 *
 * <h2>What answers</h2>
 *
 * <p>PremiumVanish (and SuperVanish, which ships the same API on Bukkit) on all three platforms,
 * reached reflectively by the platform modules, and on Bukkit backends also the {@code vanished}
 * metadata convention that SuperVanish, EssentialsX, CMI and VanishNoPacket all set. Everywhere else,
 * and on any server without one of those, this is {@link #NONE}: nobody is vanished, nothing toggles,
 * and nothing waits.
 *
 * <h2>Contract</h2>
 *
 * <p>Every method is safe to call from any thread and never throws. {@link #isVanished} may call into
 * the vanish plugin, which can be slow on a MySQL-backed PremiumVanish, so it must not be called on a
 * server's main thread; the bridge calls it from {@code heimdall-io} and {@code heimdall-sched} only.
 *
 * <p>Two kinds of "cannot answer", answered differently. The vanish plugin throwing on one call
 * answers {@code true} for that call: one missing join message beats publishing a hidden staff
 * member. An integration whose API could not be resolved at all (the plugin moved a class) answers
 * as if the plugin were absent and says why in the log once, because failing closed there would
 * silence every player's join for good.
 */
public interface Vanish {

    /**
     * How much longer a fresh join should wait before it is decided, in milliseconds.
     *
     * <p>PremiumVanish can mark a player invisible a little after the platform's own join event: its
     * auto-vanish-on-join runs inside a backend's join, but a proxy only learns a player's state once
     * they have reached a backend and that backend (or the shared database) has told it. A relay that
     * decided at the join event would announce exactly the joins it should not. So a caller re-checks
     * {@link #isVanished} and asks this again until it answers {@code 0}, and only then announces.
     *
     * <p>The platform decides what the wait is anchored to, which is why this takes the player and
     * the time already waited rather than being a constant: a backend counts from the join, a proxy
     * from the player's first backend connection, with a cap from the join so a player who never
     * reaches a backend is still decided.
     *
     * <p>{@code 0} means decide now. That is the answer whenever PremiumVanish is absent, so a server
     * without it pays no delay at all.
     *
     * @param player the player who joined
     * @param elapsedMs how long ago the join was reported, by the caller's own monotonic clock
     */
    long joinWaitMillis(PlayerHandle player, long elapsedMs);

    /**
     * Whether {@code player} is currently hidden.
     *
     * <p>Tolerates a player who has already left: a quit is reported after the player is gone, and
     * implementations fall back to whatever offline lookup the vanish plugin offers. Never throws;
     * {@code false} when nothing can say.
     */
    boolean isVanished(PlayerHandle player);

    /**
     * Subscribes to players vanishing and reappearing mid-session.
     *
     * <p>Only PremiumVanish (or SuperVanish) fires these. The metadata convention has no events, so on
     * a server that only has, say, EssentialsX, nothing is ever delivered here and a toggle is seen
     * only by the next {@link #isVanished} call.
     *
     * <p>Listeners run on whichever thread the vanish plugin fired its event on, usually a server's
     * main thread, so they must be quick and must not call {@link #isVanished}.
     *
     * @return a handle that removes the listener; closing it twice is harmless
     */
    Registration onToggle(VanishListener listener);

    /** No vanish plugin: nobody is hidden, nothing toggles, nothing waits. */
    Vanish NONE = new Vanish() {
        @Override
        public long joinWaitMillis(PlayerHandle player, long elapsedMs) {
            return 0L;
        }

        @Override
        public boolean isVanished(PlayerHandle player) {
            return false;
        }

        @Override
        public Registration onToggle(VanishListener listener) {
            return Registration.NONE;
        }

        @Override
        public String toString() {
            return "Vanish.NONE";
        }
    };
}
