package com.heimdall.core.pipeline;

import com.heimdall.core.util.Strings;
import java.util.UUID;

/**
 * One chat message, in flight.
 *
 * <p><strong>This object exists for the duration of one dispatch and is never stored.</strong> That
 * is a product decision, not an implementation detail — see {@link ChatPipeline} — and it is why
 * there is nothing here that looks like an id, a timestamp or an index. A value type with no handle
 * is a value type nothing can accumulate.
 *
 * <p>Immutable.
 */
public final class ChatMessage {

    private final UUID senderUuid;
    private final String senderName;
    private final String message;
    private final String channel;

    private ChatMessage(UUID senderUuid, String senderName, String message, String channel) {
        if (senderUuid == null) {
            throw new IllegalArgumentException("senderUuid is required");
        }
        this.senderUuid = senderUuid;
        this.senderName = Strings.trimToEmpty(senderName);
        this.message = message == null ? "" : message;
        // Blank is normalised to null so "no channel" has exactly one spelling. The bridge omits the
        // wire key for null, and a "" that slipped through would be a channel nobody can map.
        this.channel = channel == null || channel.trim().isEmpty() ? null : channel;
    }

    /** A message said in ordinary chat, outside any chat-plugin channel. */
    public static ChatMessage of(UUID senderUuid, String senderName, String message) {
        return new ChatMessage(senderUuid, senderName, message, null);
    }

    /**
     * A message said into a named channel of a chat plugin (ChatControl, today).
     *
     * <p>The channel is the plugin's own name for it, verbatim. A {@code null} or blank channel
     * produces the same value {@link #of} would, so a caller that could not resolve the name does
     * not invent one.
     */
    public static ChatMessage inChannel(
            UUID senderUuid, String senderName, String message, String channel) {
        return new ChatMessage(senderUuid, senderName, message, channel);
    }

    /** Who said it. */
    public UUID senderUuid() {
        return senderUuid;
    }

    /** Their username, as the platform reported it. */
    public String senderName() {
        return senderName;
    }

    /**
     * What they said, verbatim.
     *
     * <p>Not trimmed and not normalised: a relay that silently edits what a player typed is worse
     * than one that does not relay at all.
     */
    public String message() {
        return message;
    }

    /**
     * The chat-plugin channel this was said in, or {@code null} for ordinary chat.
     *
     * <p>Not a property core interprets. It exists so a relay can tell a staff channel from public
     * chat: on a server running channels, the line a player typed into {@code staff} was addressed
     * to staff, and relaying it as if it were public chat would put it in front of everyone reading
     * the Discord side. See departure D85.
     */
    public String channel() {
        return channel;
    }

    /**
     * Renders the sender, the length and the channel name, never the body.
     *
     * <p>The message body is deliberately absent. {@code toString()} ends up in debug logs and
     * exception messages, and chat content reaching a log file is exactly the storage this feature
     * promises not to do.
     */
    @Override
    public String toString() {
        return "ChatMessage{sender='" + senderName + "', length=" + message.length()
                + (channel == null ? "" : ", channel='" + channel + "'") + "}";
    }
}
