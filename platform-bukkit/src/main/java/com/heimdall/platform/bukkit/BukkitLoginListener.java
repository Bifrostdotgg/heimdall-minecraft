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

    // No ignoreCancelled: AsyncPlayerPreLoginEvent is not a Cancellable, so the flag is inert on it
    // — Bukkit reads it off the annotation and it never applies. Leaving it there would read as a
    // deliberate "skip logins another plugin already refused", which is not what it does.
    @EventHandler(priority = EventPriority.LOW)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            // Somebody else has already refused this connection — a ban plugin, an IP-reputation
            // plugin, an anti-bot. v2 bailed here and v3 must too, and `ignoreCancelled` cannot do
            // it: AsyncPlayerPreLoginEvent is not Cancellable, so Bukkit never reads that flag for
            // this event (see the annotation below).
            //
            // Running anyway does not change WHETHER they are refused, which is why this is easy to
            // miss. What it changes is the message: disallow() overwrites the reason unconditionally,
            // so a banned player would be told they are not whitelisted, with the ban's expiry and
            // appeal text replaced by ours. The staff member who then gets asked about it has no
            // idea LiteBans was ever involved.
            logger.debug(() -> "skipping the whitelist check for " + event.getName()
                    + ": already refused by something else (" + event.getLoginResult() + ")");
            return;
        }
        try {
            Verdict verdict = pipeline.dispatch(attemptFrom(event));
            if (verdict.isDeny()) {
                event.disallow(
                        AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST,
                        Msg.toLegacy(verdict.reason()));
            }
        } catch (Throwable broken) {
            // Throwable, not RuntimeException, and the difference is not theoretical: the failure
            // class this binding exists to be careful about — a NoSuchMethodError or
            // NoClassDefFoundError from an API that moved between server versions, which is exactly
            // what departures D43 and D44 are about — is an Error. Pipeline.dispatch catches
            // RuntimeException per interceptor and applies its declared failureVerdict (D39), so
            // anything arriving here already escaped that: it is a bug in the glue, not in a check.
            //
            // The outcome is the pipeline's own default decision, which for login is admit. A bug
            // in the glue must not be able to lock everybody out of a server, and a Heimdall that
            // silently starts refusing every login is far worse than one that says it is broken.
            // error() is already SEVERE with the cause attached; a separate severe() line would
            // only put the same failure in the log twice.
            logger.error("the login pipeline threw for " + event.getName()
                    + "; admitting them (the pipeline's default decision) rather than locking the "
                    + "server", broken);
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
