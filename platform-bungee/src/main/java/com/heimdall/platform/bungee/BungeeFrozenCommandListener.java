package com.heimdall.platform.bungee;

import com.heimdall.core.log.HeimdallLogger;
import java.util.UUID;
import java.util.function.Predicate;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.ChatEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.event.EventPriority;

/**
 * Refuses a frozen player's proxy commands, {@code /server} above all, which would otherwise let
 * them walk off to another backend.
 *
 * <p>Only commands BungeeCord itself runs ({@link ChatEvent#isProxyCommand()}). A command bound
 * for a backend is the backend's to refuse, and it does; a proxy command is never forwarded, so
 * cancelling one is the same as the proxy not running it.
 */
public final class BungeeFrozenCommandListener implements Listener {

    private final HeimdallLogger logger;
    private final Predicate<UUID> refuse;

    BungeeFrozenCommandListener(HeimdallLogger logger, Predicate<UUID> refuse) {
        this.logger = logger;
        this.refuse = refuse;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onChat(ChatEvent event) {
        try {
            if (event.isCancelled() || !event.isProxyCommand()) return;
            if (!(event.getSender() instanceof ProxiedPlayer)) return;
            if (refuse.test(((ProxiedPlayer) event.getSender()).getUniqueId())) {
                event.setCancelled(true);
            }
        } catch (Throwable broken) {
            // Fail open, as the chat listener does: a proxy whose chat handling throws for
            // everyone is worse than one frozen player's command getting through.
            logger.error("the frozen-player command check threw", broken);
        }
    }
}
