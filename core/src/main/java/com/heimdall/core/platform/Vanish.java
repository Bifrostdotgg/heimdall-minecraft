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
 * An implementation that cannot answer (the plugin's API moved, the plugin threw) answers
 * {@code false} and says why in the log: a broken integration degrades to the behaviour of a server
 * without one, which is how every other optional integration here behaves.
 */
public interface Vanish {

    /**
     * How long a fresh join may take to be marked vanished, in milliseconds.
     *
     * <p>PremiumVanish can mark a player invisible a little after the platform's own join event: its
     * auto-vanish-on-join runs inside the join, but a proxy only learns a player's state when a
     * backend or the shared database tells it. A relay that decided at the join event would announce
     * exactly the joins it should not. So a caller that sees a positive value waits up to that long,
     * re-checking, before it announces a join.
     *
     * <p>{@code 0} means every source this platform has answers immediately, and a join can be decided
     * at once. That is the answer whenever PremiumVanish is absent, so a server without it pays no
     * delay at all.
     */
    long joinSettleMillis();

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
        public long joinSettleMillis() {
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
