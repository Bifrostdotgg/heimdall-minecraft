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

    private ChatMessage(UUID senderUuid, String senderName, String message) {
        if (senderUuid == null) {
            throw new IllegalArgumentException("senderUuid is required");
        }
        this.senderUuid = senderUuid;
        this.senderName = Strings.trimToEmpty(senderName);
        this.message = message == null ? "" : message;
    }

    public static ChatMessage of(UUID senderUuid, String senderName, String message) {
        return new ChatMessage(senderUuid, senderName, message);
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
     * Renders the sender only.
     *
     * <p>The message body is deliberately absent. {@code toString()} ends up in debug logs and
     * exception messages, and chat content reaching a log file is exactly the storage this feature
     * promises not to do.
     */
    @Override
    public String toString() {
        return "ChatMessage{sender='" + senderName + "', length=" + message.length() + "}";
    }
}
