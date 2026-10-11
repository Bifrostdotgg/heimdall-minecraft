package de.myzelyam.api.vanish;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

/** Stand-in for PremiumVanish's auto-vanish-on-join event. */
public class PlayerAutoVanishOnJoinEvent extends PlayerEvent {

    private static final HandlerList handlers = new HandlerList();

    public PlayerAutoVanishOnJoinEvent(Player p) {
        super(p);
    }

    public static HandlerList getHandlerList() {
        return handlers;
    }

    @Override
    public HandlerList getHandlers() {
        return handlers;
    }
}
