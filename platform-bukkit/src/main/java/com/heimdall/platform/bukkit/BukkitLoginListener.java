package com.heimdall.platform.bukkit;

import com.heimdall.core.http.BedrockIdentity;
import com.heimdall.core.http.BedrockIdentityProvider;
import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.pipeline.LoginAttempt;
import com.heimdall.core.pipeline.LoginPipeline;
import com.heimdall.core.pipeline.Verdict;
import com.heimdall.core.text.Msg;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

/**
 * Turns a Bukkit pre-login into a {@link LoginPipeline} run, and the verdict back into a kick.
 *
 * <h2>Why {@code AsyncPlayerPreLoginEvent}, and why {@code LOW}</h2>
 *
 * <p>{@code AsyncPlayerPreLoginEvent} fires on the connection's own thread, before the player
 * exists as an entity and before a world is touched. That is what lets the whitelist check make a
 * network call to the bot without holding the server's tick loop — the alternative,
 * {@code PlayerLoginEvent}, is main-thread and would stall every player on the server for the
 * duration of an HTTP request.
 *
 * <p>{@link EventPriority#LOW} rather than {@code NORMAL} or {@code HIGHEST}: Heimdall's is an
 * <em>identity</em> decision — is this person allowed on this server at all — and it should be
 * settled before anti-cheat, IP reputation and geo plugins spend work on a connection that is about
 * to be refused. Running last would also mean a plugin at {@code NORMAL} could allow somebody
 * Heimdall was about to deny, and the ordering would be down to whoever loaded first.
 *
 * <h2>The reason is delivered as a legacy string</h2>
 *
 * <p>{@code disallow(Result, String)} is the only signature that exists on every supported version.
 * Paper added a Component overload, but calling it would put an Adventure type in the constant pool
 * of a class that has to load on 1.8.8 — and the shaded, relocated Adventure Heimdall carries is
 * not the same type as the one Paper's event expects. Serialising to §-codes at the edge is what
 * {@link Msg#toLegacy} exists for.
 *
 * <h2>Nothing here throws</h2>
 *
 * <p>An exception escaping this listener is a player who cannot join and a stack trace nobody can
 * act on. Every failure is contained and resolves to admitting the player: a Heimdall bug must not
 * become a locked server.
 */
final class BukkitLoginListener implements Listener {

    private final HeimdallLogger logger;
    private final LoginPipeline pipeline;
    private final BedrockIdentityProvider floodgate;

    BukkitLoginListener(
            HeimdallLogger logger, LoginPipeline pipeline, BedrockIdentityProvider floodgate) {
        this.logger = logger;
        this.pipeline = pipeline;
        this.floodgate = floodgate;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        try {
            Verdict verdict = pipeline.dispatch(attemptFrom(event));
            if (verdict.isDeny()) {
                event.disallow(
                        AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST,
                        Msg.toLegacy(verdict.reason()));
            }
        } catch (RuntimeException broken) {
            // Fail open, loudly. The pipeline's own interceptors decide what their failure means
            // (departure D39); this is the last resort for a bug in the glue, and a bug in the glue
            // must not be able to lock everybody out of a server.
            logger.error("the login pipeline threw for " + event.getName()
                    + "; admitting them rather than locking the server", broken);
        }
    }

    private LoginAttempt attemptFrom(AsyncPlayerPreLoginEvent event) {
        BedrockIdentity bedrock = resolveBedrock(event);
        return LoginAttempt.builder(event.getUniqueId())
                .username(event.getName())
                .ipAddress(event.getAddress() == null
                        ? "" : event.getAddress().getHostAddress())
                .bedrock(bedrock != null)
                .build();
    }

    /** Floodgate is optional and reflective; a failure here means "treat them as a Java player". */
    private BedrockIdentity resolveBedrock(AsyncPlayerPreLoginEvent event) {
        try {
            return floodgate.resolve(event.getUniqueId().toString());
        } catch (RuntimeException unusable) {
            return null;
        }
    }
}
