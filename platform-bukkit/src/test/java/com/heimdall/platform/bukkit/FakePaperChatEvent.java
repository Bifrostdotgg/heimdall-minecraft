package com.heimdall.platform.bukkit;

import java.util.LinkedHashSet;
import java.util.Set;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

/**
 * Stand-in for Paper's {@code AsyncChatEvent} with the surface the listener uses: a
 * {@code PlayerEvent} that is {@code Cancellable} and has a no-argument {@code viewers()} returning
 * a set. The real one's set holds Adventure audiences (players and the console); this one holds
 * plain objects, which is all the listener may assume.
 */
public final class FakePaperChatEvent extends PlayerEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Set<Object> viewers = new LinkedHashSet<Object>();
    private boolean cancelled;

    public FakePaperChatEvent(Player who, Object... viewers) {
        super(who);
        for (Object viewer : viewers) {
            this.viewers.add(viewer);
        }
    }

    public Set<Object> viewers() {
        return viewers;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
