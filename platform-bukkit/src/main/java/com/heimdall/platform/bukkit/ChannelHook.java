package com.heimdall.platform.bukkit;

import com.heimdall.core.pipeline.ChatPipeline;
import com.heimdall.core.platform.ChatChannels;
import org.bukkit.entity.Player;

/**
 * A chat plugin's channels, plus the two questions the legacy chat listener asks of it.
 *
 * <p>{@link ChatChannels} is what platform-free code may see. This adds what only the Bukkit chat
 * listener needs: whether the plugin is installed at all (which moves relay out of the NORMAL
 * handler, see {@link BukkitChatListener}), and what to do with a line the legacy chat event saw.
 * ChatControl ({@link ChatControlChannels}) and ZelChat ({@link ZelChatChannels}) implement it, and
 * {@link ChatChannelHooks} picks between them. Departures D85 and D92.
 */
interface ChannelHook extends ChatChannels, AutoCloseable {

    /** What a legacy chat line should do once Heimdall knows a channel plugin is installed. */
    enum UntaggedRelay {
        /** Relay it without a channel: the player is not using channels, or channels are off. */
        RELAY,
        /** Leave it to the channel hook: the player's line is going into a channel. */
        SKIP,
        /** The hook cannot be trusted, so relay nothing (fail closed). */
        BROKEN,
        /** The plugin threw while answering for this one line; drop it (fail closed), try again next. */
        TRANSIENT
    }

    /**
     * Hands over the chat pipeline and tries to hook the plugin now. Called once from the bootstrap,
     * before the chat listener is registered.
     */
    void attach(ChatPipeline chatPipeline);

    /**
     * Whether the plugin is installed at all, enabled or not, working or not. Presence, not health:
     * a broken hook must still keep the legacy listener from relaying at NORMAL.
     */
    boolean installed();

    /** For the legacy chat listener's MONITOR handler: what to do with a line {@code player} typed. */
    UntaggedRelay untaggedRelay(Player player);

    /** Why the integration is broken, or {@code null}. For a log line, never shown to players. */
    String brokenReason();

    /** The plugin's name as an operator knows it, for log lines. */
    String pluginName();

    /** Takes any hook off the server. Never throws. */
    @Override
    void close();
}
