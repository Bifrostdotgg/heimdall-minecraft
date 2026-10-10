package de.myzelyam.api.vanish;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

/** Stand-in for PremiumVanish's pre-change event of this name, the fallback for older releases. */
public class PlayerHideEvent extends PlayerEvent implements Cancellable {

    private static final HandlerList handlers = new HandlerList();
    private final boolean silent;
    private boolean cancelled;

    public PlayerHideEvent(Player p, boolean silent) {
        super(p);
        this.silent = silent;
    }

    public static HandlerList getHandlerList() {
        return handlers;
    }

    public boolean isSilent() {
        return silent;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        cancelled = cancel;
    }

    @Override
    public HandlerList getHandlers() {
        return handlers;
    }
}
