package com.heimdall.platform.bukkit.adapter;

import com.heimdall.core.link.LinkCodePrompt;
import org.bukkit.entity.Player;

/**
 * Draws a link code as a dialog, on a server whose API can send one.
 *
 * <p>The seam between {@code :platform-bukkit}, which decides when and on which thread, and
 * {@code :platform-bukkit-paper}, which knows Paper's dialog API. It exists for the same reason
 * {@link TickSource} does: this module loads the Paper implementation by name and must never
 * depend on it, so the two meet on an interface declared here.
 *
 * <p>Both methods are called on the player's own thread (the entity scheduler on Folia), never from
 * {@code heimdall-io}.
 */
public interface LinkDialogPresenter {

    /**
     * Whether this player's client can draw a dialog.
     *
     * <p>The server having the API says nothing about the client: a 1.21.5 client joined through
     * ViaBackwards is on a 1.21.7 server and has no dialog screen at all.
     */
    boolean canShow(Player player);

    /**
     * Builds the dialog and sends it.
     *
     * @return whether it was sent; {@code false} on any failure, never an exception, so the caller
     *     can put the chat version in front of the player instead
     */
    boolean show(Player player, LinkCodePrompt prompt, long nowMs);

    /** For the debug log: which API this is and how the client's version is read. */
    String describe();
}
