package com.heimdall.core.platform;

import com.heimdall.core.link.LinkCodePrompt;

/**
 * Showing a link code as a dialog, on a platform and a client that can draw one.
 *
 * <p>A dialog is a screen the client opens over the game (Minecraft 1.21.6 and later), and only
 * Paper 1.21.7 and later has an API to send one. Everything else is told the code in chat, which
 * core renders itself, so this seam is only the part core cannot do: whether this player, on this
 * server, with this client, can be shown one, and showing it.
 *
 * <p>Core decides everything before asking. A Bedrock player is never offered a dialog, a code is
 * never shown twice in a short window, and an expired one is not shown at all; an implementation
 * only has to answer for the server and the client.
 *
 * <p>Implementations are safe to call from any thread, and must not block: they are called from
 * {@code heimdall-io}, and a dialog that has to be built on the player's own thread is handed there
 * rather than waited for.
 */
public interface LinkDialogs {

    /** Never shows a dialog. The default for every platform without the API. */
    LinkDialogs NONE = new LinkDialogs() {
        @Override
        public boolean show(PlayerHandle player, LinkCodePrompt prompt, Runnable fallback) {
            return false;
        }
    };

    /**
     * Shows the prompt as a dialog, if this player can be shown one.
     *
     * <p>Returning {@code true} hands the prompt over: core sends nothing else. That is decided
     * before the dialog is actually built on the player's thread, so a dialog that then fails to
     * build or send must run {@code fallback}, which puts the chat version in front of the player
     * instead. Never both, and never neither.
     *
     * @param fallback the chat version; run at most once, from any thread, only after returning
     *     {@code true}
     * @return {@code true} if a dialog will be shown, {@code false} if the caller should use chat
     */
    boolean show(PlayerHandle player, LinkCodePrompt prompt, Runnable fallback);
}
