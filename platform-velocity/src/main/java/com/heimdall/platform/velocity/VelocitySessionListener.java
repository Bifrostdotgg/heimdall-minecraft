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
            if (!reachedTheNetwork(event.getLoginStatus())) {
                // A player the proxy refused also produces a DisconnectEvent, and reporting it as a
                // quit is not merely noise: the whitelist module's quit window slides that player's
                // mirror expiry FORWARD by the leave window. So the effect of being denied would be
                // to extend the cached decision that admits them, which is backwards, and a player
                // bouncing off a login gate does it repeatedly.
                logger.debug(() -> "not reporting a quit for " + event.getPlayer().getUsername()
                        + ": they never joined (" + event.getLoginStatus() + ")");
                return;
            }
            sessions.quit(new VelocityPlayerHandle(event.getPlayer(), text), observedAt);
        } catch (Throwable broken) {
            logger.error("could not report the quit of " + event.getPlayer().getUsername(), broken);
        }
    }

    /**
     * Whether this disconnect follows a session that actually existed.
     *
     * <p>An allow-list rather than a deny-list, and the enum is read by name rather than by constant
     * so a Velocity release that adds a status cannot silently start counting as a join.
     *
     * <ul>
     *   <li>{@code SUCCESSFUL_LOGIN} — they were on the network. The only unambiguous yes.
     *   <li>{@code PRE_SERVER_JOIN} — connected to the proxy but never reached a backend. Counted:
     *       {@code PostLoginEvent} has already fired by then, so this is the matching quit for a
     *       join that was reported, and skipping it would leak a session that never closes.
     *   <li>everything else — {@code CANCELLED_BY_PROXY} (the login gate refusing them),
     *       {@code CONFLICTING_LOGIN}, and the two user-cancelled states — is somebody who never
     *       joined.
     * </ul>
     */
    private static boolean reachedTheNetwork(DisconnectEvent.LoginStatus status) {
        if (status == null) {
            // Unreachable through velocity-api 3.4.0, whose DisconnectEvent constructor rejects a
            // null status outright — kept against an API that stops doing so, and untested for
            // exactly that reason (the event is final, so the case cannot be constructed).
            //
            // Reporting is the safer of the two wrongs: a missed extension costs one
            // re-verification against the bot, whereas a missed quit for a real session leaves a
            // mirror entry that nothing ever slides or closes.
            return true;
        }
        String name = status.name();
        return "SUCCESSFUL_LOGIN".equals(name) || "PRE_SERVER_JOIN".equals(name);
    }
}
