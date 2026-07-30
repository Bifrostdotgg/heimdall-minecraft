package com.heimdall.platform.bungee;

import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.session.PlayerSessionEvents;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.event.PostLoginEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;

/**
 * Turns the proxy's login and disconnect into core's session notifications.
 *
 * <h2>Which two events, and why this platform needs no filter</h2>
 *
 * <p>{@code PostLoginEvent} rather than {@code LoginEvent}: the latter is the proxy's own gate and
 * can still refuse the connection, so a join reported from it would sometimes be a join that never
 * happened — and the one consumer slides a whitelist cache window forward on it.
 *
 * <p>{@code PlayerDisconnectEvent} pairs with it cleanly, which is a genuine simplification over the
 * Velocity binding. There, {@code DisconnectEvent} also fires for a connection the proxy refused, so
 * that listener has to read a {@code LoginStatus} and filter — otherwise being denied would slide the
 * cached decision that admits them <em>forward</em>, and a player bouncing off a login gate does it
 * repeatedly. BungeeCord cannot produce that event at all: it is fired from
 * {@code UpstreamBridge.disconnected}, and an {@code UpstreamBridge} is only installed for a
 * {@code UserConnection} — which is constructed after the login gate has already let the player
 * through, one statement before {@code PostLoginEvent} is fired. So a refusal produces no
 * {@code PlayerDisconnectEvent}, and there is nothing to filter.
 *
 * <p>The single-statement gap between those two is real and is left unhandled deliberately: a
 * connection dropping inside it yields a quit for a join that was never reported. Reporting is the
 * safer of the two wrongs, exactly as on Velocity — a spurious quit costs one re-verification, while
 * a missed quit for a real session leaves a mirror entry that nothing ever slides or closes.
 *
 * <p>The timestamp is taken here, on the event thread, and carried across — the dispatch is
 * asynchronous, so by the time a listener runs "now" is some unknown distance later.
 *
 * <p>Both handlers are total. BungeeCord logs a plugin whose listener throws and carries on, which
 * would leave the whitelist mirror silently missing extensions with the cause a long way from the
 * symptom.
 */
final class BungeeSessionListener implements Listener {

    private final HeimdallLogger logger;
    private final PlayerSessionEvents sessions;
    private final BungeeText text;

    BungeeSessionListener(
            HeimdallLogger logger, PlayerSessionEvents sessions, BungeeText text) {
        this.logger = logger;
        this.sessions = sessions;
        this.text = text;
    }

    @EventHandler
    public void onPostLogin(PostLoginEvent event) {
        long observedAt = System.currentTimeMillis();
        ProxiedPlayer player = event.getPlayer();
        if (player == null) {
            return;
        }
        try {
            sessions.join(new BungeePlayerHandle(player, text), observedAt);
        } catch (Throwable broken) {
            logger.error("could not report the join of " + player.getName(), broken);
        }
    }

    @EventHandler
    public void onDisconnect(PlayerDisconnectEvent event) {
        long observedAt = System.currentTimeMillis();
        ProxiedPlayer player = event.getPlayer();
        if (player == null) {
            return;
        }
        try {
            sessions.quit(new BungeePlayerHandle(player, text), observedAt);
        } catch (Throwable broken) {
            logger.error("could not report the quit of " + player.getName(), broken);
        }
    }
}
