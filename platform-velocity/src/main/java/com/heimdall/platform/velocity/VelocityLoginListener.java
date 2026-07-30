package com.heimdall.platform.velocity;

import com.heimdall.core.http.BedrockIdentityProvider;
import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.pipeline.LoginAttempt;
import com.heimdall.core.pipeline.LoginPipeline;
import com.heimdall.core.pipeline.Verdict;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.proxy.Player;
import java.net.InetSocketAddress;

/**
 * The proxy's login gate.
 *
 * <h2>{@code PostOrder.FIRST}, matching v2</h2>
 *
 * <p>Heimdall's is an identity decision — may this person be on this network at all — and it should
 * be settled before anti-VPN, geo and reputation plugins spend work on a connection that is about
 * to be refused. Running later would also mean a plugin at {@code NORMAL} could allow somebody
 * Heimdall was about to deny, with the outcome decided by load order.
 *
 * <h2>Where the deny reason goes</h2>
 *
 * <p>Through {@link VelocityText}, reflectively, because Velocity's {@code Component} is the
 * server's and Heimdall's is shaded and relocated — see that class. If the bridge could not
 * resolve, the login is <strong>still refused</strong>; the player sees the proxy's default
 * disconnect message instead of Heimdall's. Refusing without an explanation is bad; admitting
 * somebody because a text library did not load would be a security hole.
 *
 * <h2>Chat is observed here, and still never intercepted</h2>
 *
 * <p>A proxy cannot cancel signed chat — since 1.19 the client signs its messages and the backend
 * validates them, so a proxy that dropped one produces a client-side kick for "chat validation
 * failure" rather than a moderated message. Chat <strong>interception</strong> therefore belongs to
 * the backend servers, which is exactly why the role system exists: the gatekeeper owns login, the
 * enforcers own everything that happens after it.
 *
 * <p>Through phase 1 this section said "there is no chat listener here", because nothing needed one
 * and the cheapest way to guarantee no cancellation was to have nothing listening.
 * {@link VelocityChatListener} arrived in phase 3 for the Discord relay: it reads chat and touches
 * nothing, which lets a network relay from the proxy instead of from every backend. The prohibition
 * is unchanged, and that class is where it is now written down — cancelling is still forbidden;
 * observing never was. Departure D81.
 */
final class VelocityLoginListener {

    private final HeimdallLogger logger;
    private final LoginPipeline pipeline;
    private final BedrockIdentityProvider floodgate;
    private final VelocityText text;

    VelocityLoginListener(
            HeimdallLogger logger,
            LoginPipeline pipeline,
            BedrockIdentityProvider floodgate,
            VelocityText text) {
        this.logger = logger;
        this.pipeline = pipeline;
        this.floodgate = floodgate;
        this.text = text;
    }

    @Subscribe(order = PostOrder.FIRST)
    public void onLogin(LoginEvent event) {
        Player player = event.getPlayer();
        if (player == null) {
            return;
        }
        if (!event.getResult().isAllowed()) {
            // The proxy-side twin of the Bukkit guard: something already refused this connection, and
            // overwriting its result would replace a ban's reason with ours. PostOrder.FIRST means
            // this usually runs before anything else, so the case is rarer here than on a backend —
            // but "rarer" is exactly the kind of path that is never noticed when it is wrong.
            logger.debug(() -> "skipping the whitelist check for " + player.getUsername()
                    + ": another plugin has already refused this connection");
            return;
        }
        try {
            Verdict verdict = pipeline.dispatch(attemptFrom(player));
            if (!verdict.isDeny()) {
                return;
            }
            Object denial = text.deniedResult(verdict.reason());
            if (!(denial instanceof ResultedEvent.ComponentResult)) {
                // Second chance, depending on strictly less: a denial with no reason at all, so the
                // player sees Velocity's default text. A worse experience, not a worse decision.
                denial = text.deniedWithoutReason();
            }
            if (denial instanceof ResultedEvent.ComponentResult) {
                event.setResult((ResultedEvent.ComponentResult) denial);
                return;
            }
            // Unreachable on a working proxy: every denial route Velocity offers takes a Component,
            // and velocity-api guarantees Adventure is present — a proxy without it could not load
            // any plugin at all. Said loudly rather than swallowed, because if it ever does happen
            // the symptom is a whitelist that silently admits everybody.
            logger.severe("refusing " + player.getUsername() + " is impossible on this proxy: no "
                    + "usable Adventure to express a denial with. Admitting them.");
        } catch (Throwable broken) {
            // Throwable, not RuntimeException, and on this platform that is the likeliest failure
            // there is: VelocityText resolves Adventure reflectively (departure D44), so a proxy
            // whose API shape differs produces a NoSuchMethodError — an Error, which sails past a
            // RuntimeException catch and out into Velocity's event machinery.
            //
            // Pipeline.dispatch already catches RuntimeException per interceptor and applies its
            // declared failureVerdict (D39), so anything arriving here escaped that: it is a bug in
            // the glue, not in a check. The outcome is the pipeline's own default decision, which
            // for login is admit — a bug in the glue must not lock everybody out of a network.
            logger.error("the login pipeline threw for " + player.getUsername()
                    + "; admitting them (the pipeline's default decision) rather than locking the "
                    + "network", broken);
        }
    }

    private LoginAttempt attemptFrom(Player player) {
        InetSocketAddress address = player.getRemoteAddress();
        return LoginAttempt.builder(player.getUniqueId())
                .username(player.getUsername())
                .ipAddress(address == null || address.getAddress() == null
                        ? "" : address.getAddress().getHostAddress())
                .bedrock(isBedrock(player))
                .build();
    }

    private boolean isBedrock(Player player) {
        try {
            return floodgate.resolve(player.getUniqueId().toString()) != null;
        } catch (RuntimeException unusable) {
            return false;
        }
    }
}
