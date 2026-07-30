package com.heimdall.platform.bungee;

import com.heimdall.core.http.BedrockIdentityProvider;
import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.pipeline.LoginAttempt;
import com.heimdall.core.pipeline.LoginPipeline;
import com.heimdall.core.pipeline.Verdict;
import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import net.md_5.bungee.api.connection.PendingConnection;
import net.md_5.bungee.api.event.LoginEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.event.EventPriority;

/**
 * The proxy's login gate, and the one class on this platform that can hang a player's connection.
 *
 * <h2>The intent contract, which has no safety net</h2>
 *
 * <p>{@code LoginEvent} fires on the connection's <strong>netty event loop</strong>, and the
 * whitelist decision is a bounded network call to the bot. Blocking here would stall every
 * connection sharing that event loop, which is the canonical BungeeCord plugin bug.
 *
 * <p>So the work is deferred with BungeeCord's own mechanism: {@code registerIntent(plugin)} on the
 * event thread, the decision on {@code heimdall-io}, {@code completeIntent(plugin)} when it is
 * settled. Reading {@code AsyncEvent} is worth doing once, because two properties of it decide the
 * shape of everything below:
 *
 * <ul>
 *   <li><strong>{@code registerIntent} must happen before the handler returns.</strong> It
 *       {@code checkState}s that the event has not fired, and the event fires as soon as the last
 *       handler returns with no intents outstanding. Registering from the worker would be a race
 *       that loses on an idle proxy.
 *   <li><strong>Nothing times out an intent.</strong> {@code AsyncEvent} holds a latch and a
 *       callback and no clock whatsoever. An intent that is never completed does not fail the login,
 *       or delay it, or log: that player's connection sits in the login state until they give up.
 *       There is no supervisor anywhere in BungeeCord that will notice.
 * </ul>
 *
 * <p>Which is why every path below completes exactly once, through an {@link AtomicBoolean} rather
 * than through care: the deny path, the allow path, a pipeline that threw, and an executor that
 * refused the task because the pools are shutting down. Departure D75.
 *
 * <p>Both halves of that are load-bearing, and neither is theoretical:
 *
 * <ul>
 *   <li><strong>At least once.</strong> The worker's {@code finally} makes the release independent of
 *       what {@link #decide} does. {@code decide} contains everything today; the {@code finally} is
 *       what stops a later edit to it turning into a connection nobody can join through.
 *   <li><strong>At most once.</strong> {@code completeIntent} {@code checkState}s that an intent is
 *       outstanding, so a second call throws — on the netty event loop, from inside BungeeCord's own
 *       event dispatch, for a connection that has already been let through. An {@link Executor} that
 *       runs the task and <em>then</em> reports it rejected is exactly that shape, and it is not a
 *       shape a caller can rule out about somebody else's pool.
 * </ul>
 *
 * <h2>{@code EventPriority.LOW}, matching the Bukkit binding</h2>
 *
 * <p>BungeeCord runs handlers in ascending priority, so {@code LOWEST} is first and {@code LOW} is
 * second. Heimdall's is an identity decision — may this person be on this network at all — and it
 * should be settled before anti-VPN, geo and reputation plugins spend work on a connection that is
 * about to be refused. Running at {@code NORMAL} would also mean the outcome depended on load order
 * whenever another plugin disagreed. {@code LOWEST} is left free for the plugins that genuinely have
 * to precede everything, exactly as on the Bukkit side.
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
 * and having nothing listening was the cheapest guarantee that nothing could cancel.
 * {@link BungeeChatListener} arrived in phase 3 for the Discord relay: it reads chat and touches
 * nothing. The prohibition is unchanged, and that class is where it is now written down —
 * cancelling is still forbidden; observing never was. Departure D81.
 */
final class BungeeLoginListener implements Listener {

    private final Plugin plugin;
    private final HeimdallLogger logger;
    private final LoginPipeline pipeline;
    private final BedrockIdentityProvider floodgate;
    private final BungeeText text;
    private final Executor io;

    BungeeLoginListener(
            Plugin plugin,
            HeimdallLogger logger,
            LoginPipeline pipeline,
            BedrockIdentityProvider floodgate,
            BungeeText text,
            Executor io) {
        this.plugin = plugin;
        this.logger = logger;
        this.pipeline = pipeline;
        this.floodgate = floodgate;
        this.text = text;
        this.io = io;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onLogin(final LoginEvent event) {
        final PendingConnection connection = event.getConnection();
        if (connection == null) {
            return;
        }
        if (event.isCancelled()) {
            // Something already refused this connection — a ban plugin, an IP-reputation plugin, an
            // anti-bot. Running anyway would not change WHETHER they are refused; it would overwrite
            // the reason, replacing a ban's expiry and appeal text with "not whitelisted". The staff
            // member who then gets asked about it has no idea LiteBans was ever involved.
            logger.debug(() -> "skipping the whitelist check for " + connection.getName()
                    + ": another plugin has already refused this connection");
            return;
        }

        if (connection.getUniqueId() == null) {
            // Not reachable through a BungeeCord that fires this event where InitialHandler does:
            // the uuid is assigned from the Mojang login result — or generated as the offline-mode
            // one — several statements before the event is constructed. Checked anyway, and checked
            // HERE, before an intent exists: a LoginAttempt cannot be built without a uuid, so the
            // alternative is an exception on a path that has already taken the connection hostage.
            logger.warn("admitting " + connection.getName() + ": this proxy has not resolved a UUID "
                    + "for their connection, so there is nothing to check them against");
            return;
        }

        // On the event thread, before returning — see the class javadoc. From here on, exactly one
        // path must reach completeIntent, and it must reach it however badly the rest goes.
        event.registerIntent(plugin);
        final AtomicBoolean completed = new AtomicBoolean();

        try {
            io.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        decide(event, connection);
                    } finally {
                        complete(event, completed);
                    }
                }
            });
        } catch (Throwable notSubmitted) {
            // RejectedExecutionException, in practice: the pools are stopping because the plugin is
            // being disabled while a player is mid-login. The intent is already registered, so the
            // ONLY correct thing to do is complete it — the alternative is a connection that hangs
            // until the player closes their client.
            logger.warn("could not run the whitelist check for " + connection.getName()
                    + " (" + notSubmitted + "); admitting them rather than leaving their connection "
                    + "waiting on a decision that will never come");
            complete(event, completed);
        }
    }

    /**
     * The decision itself, on {@code heimdall-io}.
     *
     * <p>Total: everything is contained and resolves to admitting the player. A bug in this glue must
     * not become a network nobody can join, and the pipeline's own default decision for login is
     * admit.
     */
    private void decide(LoginEvent event, PendingConnection connection) {
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
            // between proxy versions — and an Error sails past a RuntimeException catch.
            logger.error("the login pipeline threw for " + connection.getName()
                    + "; admitting them (the pipeline's default decision) rather than locking the "
                    + "network", broken);
        }
    }

    /**
     * Attaches the deny reason, and refuses the login either way.
     *
     * <p>Separate from {@link #decide} so that a text conversion which somehow throws cannot undo the
     * cancellation that has already been applied: the player then sees BungeeCord's own default kick
     * message instead of Heimdall's, which is a worse experience and not a worse <em>decision</em>.
     */
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

    /**
     * Completes the intent, at most once.
     *
     * <p>Once is not a nicety: {@code completeIntent} {@code checkState}s that the plugin still has an
     * intent outstanding, so a second call throws — on the netty event loop, from inside BungeeCord's
     * own event dispatch, for a connection that has already been let through.
     */
    private void complete(LoginEvent event, AtomicBoolean completed) {
        if (!completed.compareAndSet(false, true)) {
            return;
        }
        try {
            event.completeIntent(plugin);
        } catch (Throwable alreadyGone) {
            // The event object outliving its own dispatch, or a BungeeCord that changed the rules.
            // Nothing further can be done for this connection and there is nothing left to unwind;
            // saying so is the only useful action.
            logger.error("could not release the login gate for a connection; if this recurs, "
                    + "players will be left waiting at the login screen", alreadyGone);
        }
    }

    /**
     * What the pipeline is asked about.
     *
     * <p>{@code PendingConnection} rather than a player, because on BungeeCord there is no player
     * object yet — {@code LoginEvent} fires before {@code UserConnection} is constructed, which is
     * precisely why it is the right place for an identity decision. The UUID is nonetheless settled
     * by then: {@code InitialHandler} assigns it from the Mojang login result, or generates the
     * offline-mode one, before it fires this event.
     */
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
