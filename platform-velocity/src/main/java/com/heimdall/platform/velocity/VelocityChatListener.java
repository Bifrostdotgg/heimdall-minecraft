package com.heimdall.platform.velocity;

import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.pipeline.ChatMessage;
import com.heimdall.core.pipeline.ChatPipeline;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.proxy.Player;

/**
 * Chat on the proxy, <strong>observed and never touched</strong>.
 *
 * <h2>This class must never cancel or modify a message, and that is a client-compatibility rule
 * rather than a style preference</h2>
 *
 * <p>Since 1.19.1 a client <em>signs</em> its chat messages and the backend validates the signature.
 * A proxy that dropped or edited one produces a client-side {@code "chat validation failure"}
 * disconnect rather than a moderated message — the player is kicked, and the plugin that kicked them
 * is invisible in the log. That is why {@code :platform-velocity} shipped with no chat listener at
 * all through phase 1 (see {@link VelocityLoginListener}) and why chat <em>interception</em> still
 * belongs exclusively to the backend servers.
 *
 * <p>What changed in phase 3 is the question being asked. Cancelling signed chat is still forbidden;
 * <em>reading</em> it never was. The Discord bridge is a relay, so a proxy is a legitimate place to
 * tap chat for a whole network — and that tap needs precisely the right this class has: to see the
 * message and to do nothing to it. So the listener exists, it calls no mutator, and
 * {@code VelocityChatListenerTest} asserts the event's result is the same object afterwards.
 * Departure D81.
 *
 * <p>Concretely, the two things this class may never do:
 *
 * <ul>
 *   <li>call {@code event.setResult(...)} — every {@code ChatResult} other than {@code allowed()},
 *       including a "denied" one, is the cancellation described above. (Velocity has deprecated the
 *       setter for the same underlying reason.)
 *   <li>route a deny verdict from the pipeline into the event. It deliberately does not read one:
 *       see below.
 * </ul>
 *
 * <h2>Why it still goes through {@code dispatchWithObservers}</h2>
 *
 * <p>The alternative — a proxy-only entry point that talks straight to the observers — would be a
 * second way into the same pipeline, and the property that makes chat relay-safe ("observers run
 * only for messages that were allowed") would then hold on one platform and not the other.
 *
 * <p>So this uses the same call the Bukkit listener does, and simply <strong>discards the
 * verdict</strong>. On a proxy that costs nothing today, because chat interceptors are registered by
 * backend-role modules and none exists here; and if one ever did, the effect would be that a message
 * an interceptor blocked is not relayed, which is the conservative direction. The one thing that
 * would be wrong — acting on the verdict by cancelling — is exactly what this class refuses to do.
 *
 * <h2>Order and the already-denied case</h2>
 *
 * <p>{@link PostOrder#LAST}, so anything else on the proxy with an opinion has already had it, and a
 * message another plugin denied is skipped — relaying something the network just refused to send
 * would put it in front of a wider audience than it started with. That is the same reasoning as
 * {@code ignoreCancelled = true} on the Bukkit side.
 *
 * <h2>Inert unless the network asks for it</h2>
 *
 * <p>Registering this listener does not start relaying anything. The bridge module's
 * {@code relayChat} setting defaults to <em>false</em> on {@code GATEKEEPER}, so on a stock proxy
 * the pipeline has no observer attached and this handler's dispatch reaches nobody. Proxy-origin
 * relay is an explicit per-network choice made in the dashboard.
 */
public final class VelocityChatListener {

    private final HeimdallLogger logger;
    private final ChatPipeline pipeline;

    VelocityChatListener(HeimdallLogger logger, ChatPipeline pipeline) {
        this.logger = logger;
        this.pipeline = pipeline;
    }

    @Subscribe(order = PostOrder.LAST)
    public void onChat(PlayerChatEvent event) {
        // Captured as the handler goes, so the catch below can name the player when one is known
        // and stay quiet about it when one is not. Reading it again down there would re-enter the
        // very API that just failed — and on this platform the likeliest thing to have failed IS
        // an event accessor.
        String senderName = null;
        try {
            // Every event accessor is INSIDE the guard, not before it. The catch exists for a
            // NoSuchMethodError from an API that moved between Velocity releases, and
            // `getResult()` is one of the named candidates — it answers a nested type whose setter
            // Velocity has already deprecated. A call sitting outside the try is a call the guard
            // does not cover, which is the opposite of what it was written for.
            Player sender = event.getPlayer();
            if (sender == null) {
                return;
            }
            if (!event.getResult().isAllowed()) {
                // Somebody else already refused it. See the class javadoc.
                return;
            }
            senderName = sender.getUsername();
            // The verdict is deliberately discarded — this listener has no right to act on one.
            pipeline.dispatchWithObservers(
                    ChatMessage.of(sender.getUniqueId(), senderName, event.getMessage()));
        } catch (Throwable broken) {
            // Throwable, not RuntimeException: a NoSuchMethodError from an API that moved between
            // Velocity releases would otherwise reach the proxy's event machinery. Fail open — and
            // "open" here literally means "the message is delivered untouched", because this class
            // never touches it in the first place, so there is no half-applied state to report.
            //
            // The message body is NOT logged. Chat content reaching a log file is exactly the
            // storage the bridge promises not to do; the sender's name, where it is known, is
            // enough to find the cause.
            logger.error("the chat pipeline threw"
                    + (senderName == null ? "" : " for " + senderName)
                    + "; the message itself was not affected", broken);
        }
    }
}
