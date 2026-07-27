package com.heimdall.platform.velocity;

import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.session.PlayerSessionEvents;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;

/**
 * Turns the proxy's login and disconnect into core's session notifications.
 *
 * <h2>Which two events, and why not the obvious pair</h2>
 *
 * <p>{@code PostLoginEvent} rather than {@code LoginEvent}: the latter is the proxy's own gate and
 * can still refuse the connection, so a join reported from it would sometimes be a join that never
 * happened — and the one consumer slides a whitelist cache window forward on it. {@code PostLogin}
 * fires once the player is genuinely on the network.
 *
 * <p>{@code DisconnectEvent} rather than {@code ServerDisconnectEvent}: a player moving between two
 * backend servers is not leaving the network, and treating a server switch as a quit would extend a
 * cache window on every {@code /server} command.
 *
 * <p>The timestamp is taken here, on the event thread, and carried across — the dispatch is
 * asynchronous, so by the time a listener runs "now" is some unknown distance later.
 *
 * <p>Both handlers are total. Velocity logs a plugin whose listener throws and carries on, which
 * would leave the whitelist mirror silently missing extensions with the cause a long way from the
 * symptom.
 */
public final class VelocitySessionListener {

    private final HeimdallLogger logger;
    private final PlayerSessionEvents sessions;
    private final VelocityText text;

    VelocitySessionListener(HeimdallLogger logger, PlayerSessionEvents sessions, VelocityText text) {
        this.logger = logger;
        this.sessions = sessions;
        this.text = text;
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        long observedAt = System.currentTimeMillis();
        try {
            sessions.join(new VelocityPlayerHandle(event.getPlayer(), text), observedAt);
        } catch (Throwable broken) {
            logger.error("could not report the join of " + event.getPlayer().getUsername(), broken);
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        long observedAt = System.currentTimeMillis();
        try {
            sessions.quit(new VelocityPlayerHandle(event.getPlayer(), text), observedAt);
        } catch (Throwable broken) {
            logger.error("could not report the quit of " + event.getPlayer().getUsername(), broken);
        }
    }
}
