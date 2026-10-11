package de.myzelyam.api.vanish;

import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Event;

/**
 * A compile-time stand-in for PremiumVanish's own class of this name. Never shipped; see
 * {@link BungeePlayerHideEvent} for why it exists and how it is kept out of the jar.
 */
public class BungeePlayerShowEvent extends Event {

    private final ProxiedPlayer player;

    public BungeePlayerShowEvent(ProxiedPlayer player) {
        this.player = player;
    }

    /** The player who is reappearing. */
    public ProxiedPlayer getPlayer() {
        return player;
    }
}
