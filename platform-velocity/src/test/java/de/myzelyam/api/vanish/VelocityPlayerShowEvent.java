package de.myzelyam.api.vanish;

import com.velocitypowered.api.proxy.Player;

/** Stand-in for PremiumVanish's event of this name. */
public class VelocityPlayerShowEvent {

    private final Player player;

    public VelocityPlayerShowEvent(Player player) {
        this.player = player;
    }

    public Player getPlayer() {
        return player;
    }
}
