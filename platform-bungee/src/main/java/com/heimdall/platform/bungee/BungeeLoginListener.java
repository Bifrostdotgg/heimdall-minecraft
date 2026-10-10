package com.heimdall.platform.bungee;

import com.heimdall.core.http.BedrockIdentityProvider;
import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.pipeline.LoginAttempt;
import com.heimdall.core.pipeline.LoginPipeline;
import com.heimdall.core.pipeline.Verdict;
import com.heimdall.shell.contract.LoginGate;
import java.net.InetSocketAddress;
import java.util.UUID;
import net.md_5.bungee.api.connection.PendingConnection;
import net.md_5.bungee.api.event.LoginEvent;

/**
 * The proxy's login decision.
 *
 * <h2>The intent contract moved to the shell</h2>
 *
 * <p>{@code LoginEvent} fires on the connection's netty event loop, and the whitelist decision is a
 * bounded network call to the bot, so it has to be deferred with BungeeCord's own mechanism:
 * {@code registerIntent(plugin)} during dispatch, the decision elsewhere, {@code completeIntent}
 * once it is settled. Nothing in BungeeCord ever times an intent out, so one that is never
 * completed hangs that player's connection for good (departure D75).
 *
 * <p>Until the hot-swap split this class did all of that itself, on {@code heimdall-io}. It cannot
 * any more: an intent may only be registered during dispatch, a login can arrive while no core is
 * running at all, and a swap shuts {@code heimdall-io} down, which would drop a decision queued
 * behind the drain with its intent never completed. So the shell's permanent listener
 * ({@code com.heimdall.shell.bungee.BungeeLoginGate}) registers the intent, runs the decision on its
 * own threads, and completes the intent exactly once in a {@code finally} however the decision ends.
 * This class is the decision: {@link #decide} is synchronous, may block, and must not touch the
 * intent (departure D87).
 *
 * <h2>{@code EventPriority.LOW}, matching the Bukkit binding</h2>
 *
 * <p>The shell's listener runs at {@code LOW}: second, after {@code LOWEST}. Heimdall's is an
 * identity decision (may this person be on this network at all) and it should be settled before
 * anti-VPN, geo and reputation plugins spend work on a connection that is about to be refused.
 *
 * <h2>Chat is observed here, and still never intercepted</h2>
 *
 * <p>A proxy cannot cancel signed chat: since 1.19 the client signs its messages and the backend
 * validates them, so a proxy that dropped one produces a client-side kick for "chat validation
 * failure" rather than a moderated message. Chat <strong>interception</strong> therefore belongs to
 * the backend servers, which is exactly why the role system exists: the gatekeeper owns login, the
 * enforcers own everything that happens after it. {@link BungeeChatListener} reads chat and touches
 * nothing; cancelling is still forbidden, observing never was. Departure D81.
 */
final class BungeeLoginListener implements LoginGate {

    private final HeimdallLogger logger;
    private final LoginPipeline pipeline;
    private final BedrockIdentityProvider floodgate;
    private final BungeeText text;

    BungeeLoginListener(
            HeimdallLogger logger,
            LoginPipeline pipeline,
            BedrockIdentityProvider floodgate,
            BungeeText text) {
        this.logger = logger;
        this.pipeline = pipeline;
        this.floodgate = floodgate;
        this.text = text;
    }

    @Override
    public void decide(Object raw) {
        LoginEvent event = (LoginEvent) raw;
        final PendingConnection connection = event.getConnection();
        if (connection == null) {
            return;
        }
        if (event.isCancelled()) {
            // Something already refused this connection: a ban plugin, an IP-reputation plugin, an
            // anti-bot. Running anyway would not change WHETHER they are refused; it would overwrite
            // the reason, replacing a ban's expiry and appeal text with "not whitelisted".
            logger.debug(() -> "skipping the whitelist check for " + connection.getName()
                    + ": another plugin has already refused this connection");
            return;
        }
        if (connection.getUniqueId() == null) {
            // Not reachable through a BungeeCord that fires this event where InitialHandler does:
            // the uuid is assigned from the Mojang login result, or generated as the offline-mode
            // one, several statements before the event is constructed. Checked anyway: a
            // LoginAttempt cannot be built without a uuid.
            logger.warn("admitting " + connection.getName() + ": this proxy has not resolved a UUID "
                    + "for their connection, so there is nothing to check them against");
            return;
        }
        try {
            Verdict verdict = pipeline.dispatch(attemptFrom(connection));
            if (!verdict.isDeny()) {
                return;
            }
            // Cancelled AND given a reason, in that order: BungeeCord reads isCancelled() first and
            // only then asks for the reason, so a reason set without the flag is silently discarded.
            event.setCancelled(true);
            setReason(event, verdict);
        } catch (Throwable broken) {
            // Throwable, not RuntimeException. Pipeline.dispatch already catches RuntimeException per
            // interceptor and applies its declared failureVerdict (D39), so anything arriving here
            // escaped that: it is a bug in the glue, or a NoSuchMethodError from an API that moved
            // between proxy versions, and an Error sails past a RuntimeException catch.
            logger.error("the login pipeline threw for " + connection.getName()
                    + "; admitting them (the pipeline's default decision) rather than locking the "
                    + "network", broken);
        }
    }

    @SuppressWarnings("deprecation")
    private void setReason(LoginEvent event, Verdict verdict) {
        try {
            // setCancelReason(BaseComponent...) is deprecated on modern BungeeCord in favour of the
            // single-component setReason, which does not exist below the 1.20 line. It is not going
            // anywhere either: current BungeeCord still implements it, as
            // setReason(TextComponent.fromArray(cancelReason)). Departure D74.
            event.setCancelReason(text.toComponents(verdict.reason()));
        } catch (Throwable unrenderable) {
            logger.warn("could not render the refusal reason; the player will see the proxy's "
                    + "default message instead: " + unrenderable);
        }
    }

    private LoginAttempt attemptFrom(PendingConnection connection) {
        UUID uuid = connection.getUniqueId();
        InetSocketAddress address = connection.getAddress();
        return LoginAttempt.builder(uuid)
                .username(connection.getName())
                .ipAddress(address == null || address.getAddress() == null
                        ? "" : address.getAddress().getHostAddress())
                .bedrock(isBedrock(uuid))
                .build();
    }

    private boolean isBedrock(UUID uuid) {
        if (uuid == null) {
            return false;
        }
        try {
            return floodgate.resolve(uuid.toString()) != null;
        } catch (RuntimeException unusable) {
            return false;
        }
    }
}
