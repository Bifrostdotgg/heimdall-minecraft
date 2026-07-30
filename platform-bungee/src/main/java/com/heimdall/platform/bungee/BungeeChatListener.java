package com.heimdall.platform.bungee;

import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.pipeline.ChatMessage;
import com.heimdall.core.pipeline.ChatPipeline;
import net.md_5.bungee.api.connection.Connection;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.ChatEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.event.EventPriority;

/**
 * Chat on the proxy, <strong>observed and never touched</strong>.
 *
 * <h2>This class must never cancel or modify a message, and that is a client-compatibility rule
 * rather than a style preference</h2>
 *
 * <p>Identical to {@code VelocityChatListener}'s reasoning, one proxy along (this module cannot
 * {@code @link} it — the two bindings never share a classpath). Since 1.19.1 a client signs its
 * chat and the backend validates the signature, so a proxy
 * that dropped or edited one produces a client-side {@code "chat validation failure"} disconnect
 * rather than a moderated message — with nothing in any log pointing at the plugin that did it. That
 * is why this binding shipped with no chat listener at all through phase 1 (see
 * {@link BungeeLoginListener}) and why chat <em>interception</em> remains the backends'.
 *
 * <p>Phase 3 asks a different question. Cancelling signed chat is still forbidden; <em>reading</em>
 * it never was, and a relay only needs to read. So this listener exists, it calls neither
 * {@code setCancelled} nor {@code setMessage}, and {@code BungeeChatListenerTest} asserts the event
 * is unchanged afterwards. Departure D81.
 *
 * <h2>{@code ChatEvent} fires in both directions, and only one of them is chat</h2>
 *
 * <p>This is the trap specific to BungeeCord. {@code ChatEvent} is a {@code TargetedEvent}: it is
 * fired from {@code UpstreamBridge} when a <em>player</em> sends something, and again from
 * {@code DownstreamBridge} when a <em>server</em> sends something to a player. Treating the second
 * as chat would relay every message the network sends to anybody — including the plugin's own
 * Discord→Minecraft deliveries, which is a relay loop rather than a merely noisy bug.
 *
 * <p>So the sender is type-checked: only a {@link ProxiedPlayer} is somebody talking. That is a
 * property of what the event carries rather than a heuristic, and it costs one {@code instanceof}.
 *
 * <h2>Commands are not chat</h2>
 *
 * <p>{@code isCommand()} is true for anything starting with {@code /} and {@code isProxyCommand()}
 * for the subset the proxy itself will handle. Both are excluded: a relay that shipped
 * {@code /login hunter2} to a Discord channel would be publishing passwords, and the two flags are
 * checked rather than the leading slash so that a BungeeCord release changing what counts as a
 * command cannot quietly change what gets published.
 *
 * <h2>Order and the already-cancelled case</h2>
 *
 * <p>{@code EventPriority.HIGHEST} — BungeeCord runs handlers in ascending priority, so this is
 * last, and anything else with an opinion has already had it. There is no {@code MONITOR} on this
 * platform and no {@code ignoreCancelled}, so a cancelled event is skipped by hand: relaying
 * something the network just refused to send would put it in front of a wider audience than it
 * started with.
 *
 * <h2>Inert unless the network asks for it</h2>
 *
 * <p>Registering this listener does not start relaying anything. The bridge module's
 * {@code relayChat} setting defaults to <em>false</em> on {@code GATEKEEPER}, so on a stock proxy
 * the pipeline has no observer attached and this handler's dispatch reaches nobody.
 */
final class BungeeChatListener implements Listener {

    private final HeimdallLogger logger;
    private final ChatPipeline pipeline;

    BungeeChatListener(HeimdallLogger logger, ChatPipeline pipeline) {
        this.logger = logger;
        this.pipeline = pipeline;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChat(ChatEvent event) {
        // Captured as the handler goes, so the catch below can name the player when one is known
        // and stay quiet about it when one is not. Reading it again down there would re-enter the
        // very API that just failed — and on this platform the likeliest thing to have failed IS
        // an event accessor.
        String senderName = null;
        try {
            // Every event accessor is INSIDE the guard, not before it. The catch exists for a
            // NoSuchMethodError from an API that moved between BungeeCord releases, and
            // `isProxyCommand()` is the named candidate: this module compiles against the 1.16-R0.4
            // FLOOR (departure D74), so it is exactly the kind of method a version below that
            // would not have. A call sitting outside the try is a call the guard does not cover,
            // which is the opposite of what it was written for — and a listener that threw an
            // Error out of BungeeCord's dispatcher for every chat message would be a proxy-wide
            // fault caused by a feature nobody had switched on.
            if (event.isCancelled()) {
                return;
            }
            if (event.isCommand() || event.isProxyCommand()) {
                // A relay that published `/login hunter2` would be publishing passwords.
                return;
            }
            Connection sender = event.getSender();
            if (!(sender instanceof ProxiedPlayer)) {
                // The downstream half of this event: a server talking to a player. See the class
                // javadoc — relaying it would include this plugin's own Discord deliveries.
                return;
            }
            ProxiedPlayer player = (ProxiedPlayer) sender;
            senderName = player.getName();
            // The verdict is deliberately discarded — this listener has no right to act on one.
            pipeline.dispatchWithObservers(
                    ChatMessage.of(player.getUniqueId(), senderName, event.getMessage()));
        } catch (Throwable broken) {
            // Throwable, not RuntimeException: a NoSuchMethodError from an API that moved between
            // BungeeCord releases would otherwise reach the proxy's event machinery. Fail open —
            // which here literally means "the message is delivered untouched", because this class
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
