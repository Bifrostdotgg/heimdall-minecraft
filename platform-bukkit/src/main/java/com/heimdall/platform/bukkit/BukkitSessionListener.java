package com.heimdall.platform.bukkit;

import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.session.PlayerSessionEvents;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Turns Bukkit's join and quit into core's session notifications.
 *
 * <h2>Why {@code MONITOR}</h2>
 *
 * <p>Nothing here changes the event, and both of these are read-only observations of something that
 * has already happened. {@code MONITOR} is Bukkit's own name for exactly that, and running last
 * means the timestamp Heimdall stamps is after whatever else the server was going to do with the
 * join rather than in the middle of it.
 *
 * <p>The timestamp is taken <em>here</em>, on the event thread, and carried across. The dispatch is
 * asynchronous, so by the time a listener runs "now" is some unknown distance later — and the one
 * consumer that exists slides a cache window from it.
 *
 * <h2>Nothing here blocks the tick loop</h2>
 *
 * <p>{@code PlayerJoinEvent} is on the main server thread. {@link PlayerSessionEvents} hands off to
 * {@code heimdall-io} and returns, so what actually runs on the tick loop is building a handle and
 * queueing a task — which is the whole reason the dispatcher exists rather than modules registering
 * Bukkit listeners of their own.
 *
 * <p>Both handlers are total: an exception escaping a Bukkit listener is logged by the server as a
 * plugin fault, and neither of these has anything worth failing a join over.
 */
final class BukkitSessionListener implements Listener {

    private final HeimdallLogger logger;
    private final PlayerSessionEvents sessions;
    private final BukkitPlayerDirectory players;

    BukkitSessionListener(
            HeimdallLogger logger, PlayerSessionEvents sessions, BukkitPlayerDirectory players) {
        this.logger = logger;
        this.sessions = sessions;
        this.players = players;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        long observedAt = System.currentTimeMillis();
        try {
            sessions.join(players.wrap(event.getPlayer()), observedAt);
        } catch (Throwable broken) {
            logger.error("could not report the join of " + event.getPlayer().getName(), broken);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        long observedAt = System.currentTimeMillis();
        try {
            // Wrapped even though they are on their way out: PlayerHandle tolerates a player who has
            // gone, and a listener that wanted to say goodbye should be able to try.
            sessions.quit(players.wrap(event.getPlayer()), observedAt);
        } catch (Throwable broken) {
            logger.error("could not report the quit of " + event.getPlayer().getName(), broken);
        }
    }
}
