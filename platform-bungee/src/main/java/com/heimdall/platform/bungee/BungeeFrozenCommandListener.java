package com.heimdall.platform.bungee;

import com.heimdall.core.log.HeimdallLogger;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.ChatEvent;
import net.md_5.bungee.api.event.ServerKickEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.event.EventPriority;

/**
 * Refuses a frozen player's proxy commands, {@code /server} above all, which would otherwise let
 * them walk off to another backend, and notes backend kicks so a kick is not read as a logout.
 *
 * <p>Only commands BungeeCord itself runs ({@link ChatEvent#isProxyCommand()}). A command bound
 * for a backend is the backend's to refuse, and it does; a proxy command is never forwarded, so
 * cancelling one is the same as the proxy not running it.
 */
public final class BungeeFrozenCommandListener implements Listener {

    private final HeimdallLogger logger;
    private final Predicate<UUID> refuse;
    private final Consumer<UUID> kicked;

    BungeeFrozenCommandListener(
            HeimdallLogger logger, Predicate<UUID> refuse, Consumer<UUID> kicked) {
        this.logger = logger;
        this.refuse = refuse;
        this.kicked = kicked;
    }

    /**
     * A backend kicking a frozen player, so the disconnect that may follow is not read as the
     * player logging out to escape a check. Last: whether another plugin moves them elsewhere
     * instead does not matter, since the note only counts if they then disconnect.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onKick(ServerKickEvent event) {
        try {
            if (event.getPlayer() != null) kicked.accept(event.getPlayer().getUniqueId());
        } catch (Throwable broken) {
            logger.error("noting a kick for a frozen player threw", broken);
        }
    }

    /**
     * HIGHEST, which BungeeCord runs last: {@link ChatEvent#isProxyCommand()} reads the message as
     * it stands, so this sees a command after any other listener has rewritten it.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
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
