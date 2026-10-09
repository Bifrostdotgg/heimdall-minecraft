package org.mineacademy.chatcontrol.api;

import org.bukkit.command.CommandSender;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.mineacademy.chatcontrol.model.Channel;

/** Stand-in for ChatControl's post-delivery channel event. */
public final class ChannelPostChatEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    /** Test steering: when set, {@link #getMessage()} throws it, as ChatControl's own code can. */
    public static volatile RuntimeException failGetMessage;

    private final Channel channel;
    private final CommandSender sender;
    private final String message;
    private final boolean cancelledSilently;
    private boolean cancelled;

    public ChannelPostChatEvent(
            Channel channel, CommandSender sender, String message, boolean cancelledSilently) {
        this.channel = channel;
        this.sender = sender;
        this.message = message;
        this.cancelledSilently = cancelledSilently;
    }

    public Channel getChannel() {
        return channel;
    }

    public CommandSender getSender() {
        return sender;
    }

    public String getMessage() {
        RuntimeException failure = failGetMessage;
        if (failure != null) {
            throw failure;
        }
        return message;
    }

    public boolean isCancelledSilently() {
        return cancelledSilently;
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
