package de.myzelyam.api.vanish;

import com.velocitypowered.api.proxy.Player;

/** Stand-in for PremiumVanish's event of this name. */
public class VelocityPlayerHideEvent {

    private final Player player;

    public VelocityPlayerHideEvent(Player player) {
        this.player = player;
    }

    public Player getPlayer() {
        return player;
    }
}
