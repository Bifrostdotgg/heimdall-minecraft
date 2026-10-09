package com.heimdall.platform.bukkit;

import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.pipeline.ChatMessage;
import com.heimdall.core.pipeline.ChatPipeline;
import com.heimdall.core.pipeline.Verdict;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

/**
 * Chat, into the pipeline and — if it survives — out to the observers.
 *
 * <h2>Why the deprecated event, on every version</h2>
 *
 * <p>Paper's {@code AsyncChatEvent} is the modern replacement and v3 deliberately does not use it.
 * The reason is shading, not preference: that event's {@code message()} returns a
 * {@code net.kyori.adventure.text.Component} supplied by the <em>server</em>, while Heimdall shades
 * and relocates its own Adventure into {@code com.heimdall.libs.kyori}. Shadow rewrites every
 * {@code net.kyori} reference in every class it merges, including the method descriptor at that
 * call site — so the compiled call would look for {@code com.heimdall.libs.kyori.text.Component} on
 * an event that returns the server's own, and fail at runtime with a
 * {@code NoSuchMethodError} on precisely the modern Paper servers it was added for.
 *
 * <p>Un-relocating Adventure is not an option (it would collide with whatever else the server has
 * loaded), and relocation cannot be excluded for one consumer. {@code AsyncPlayerChatEvent} is
 * plain {@link String}, fires on every server from 1.8.8 to current, and has none of that problem.
 * Deprecated is not the same as absent — see departure D43.
 *
 * <h2>Priority and cancellation</h2>
 *
 * <p>{@link EventPriority#NORMAL}, and {@code ignoreCancelled} so a message another plugin has
 * already blocked is not relayed to Discord a second time. Cancelling is how a chat interceptor
 * blocks; observers run only for messages that survived, because relaying something the server just
 * censored would put it in front of a wider audience than it started with.
 *
 * <h2>With ChatControl installed, checking and relaying happen at different times</h2>
 *
 * <p>Without ChatControl, all of the above happens in one handler, exactly as it always has. With
 * it, the NORMAL handler runs the checks only ({@link ChatPipeline#dispatch}): a muted player is
 * still blocked before anything is delivered, but nothing is relayed yet, because at NORMAL nobody
 * knows which audience the line is for. ChatControl decides that later (on Paper, in the Adventure
 * chat event, which fires after this one), and a line going into its {@code staff} channel must not
 * reach Discord as public chat.
 *
 * <p>So relay moves to a second, MONITOR handler, and splits:
 *
 * <ul>
 *   <li>a player who is <strong>not</strong> using ChatControl channels (or a server with channels
 *       switched off) is relayed here, untagged, as before;
 *   <li>a player who <strong>is</strong> using channels is skipped here; ChatControl's own
 *       {@code ChannelPostChatEvent} relays the line with its channel, from
 *       {@link ChatControlChannels};
 *   <li>if the ChatControl integration is broken, nothing is relayed (fail closed), and that is
 *       logged once rather than per line.
 * </ul>
 *
 * <p>Whether ChatControl is <em>installed</em> is the switch, not whether it is healthy: a broken
 * hook still moves relay out of the NORMAL handler, or a channelled server would leak exactly when
 * the code that understands channels stopped working. Departure D85.
 */
final class BukkitChatListener implements Listener {

    private final HeimdallLogger logger;
    private final ChatPipeline pipeline;
    private final BukkitMessenger messenger;
    private final ChatControlChannels chatControl;

    /** Whether the "relay paused, integration broken" error has been logged. Once per boot. */
    private final AtomicBoolean reportedBroken = new AtomicBoolean();

    BukkitChatListener(
            HeimdallLogger logger,
            ChatPipeline pipeline,
            BukkitMessenger messenger,
            ChatControlChannels chatControl) {
        this.logger = logger;
        this.pipeline = pipeline;
        this.messenger = messenger;
        this.chatControl = chatControl;
    }

    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        Player sender = event.getPlayer();
        if (sender == null) {
            return;
        }
        try {
            ChatMessage message =
                    ChatMessage.of(sender.getUniqueId(), sender.getName(), event.getMessage());
            // With ChatControl installed, relay waits for MONITOR (onChatRelay) to learn which
            // audience the line is for; here it is checks only. Without it, unchanged.
            Verdict verdict = deferRelay()
                    ? pipeline.dispatch(message)
                    : pipeline.dispatchWithObservers(message);
            if (verdict.isDeny()) {
                event.setCancelled(true);
                // Through the messenger, like every other player-facing send. Serialising here
                // instead would make this the one place that bypasses Adventure — and it would
                // inherit whatever the legacy serializer's dialect happens to be rather than the
                // one Msg is configured for.
                //
                // The reason goes to the sender only. Chat moderation that announced itself would
                // repeat the blocked message to everyone who had not seen it.
                messenger.send(sender, verdict.reason());
            }
        } catch (Throwable broken) {
            // Throwable, not RuntimeException. The failure class this whole binding is careful about
            // — a NoSuchMethodError from an API that moved between server versions — is an Error,
            // and it would sail straight past a RuntimeException catch into Bukkit's event
            // machinery. Fail open: a bug in the relay must not silence a server's chat.
            //
            // What is reported depends on how far it got, because the two outcomes are opposite and
            // the log is the only place anyone will see the difference. A throw from dispatch means
            // the message really did go through. A throw from the send AFTER setCancelled(true) —
            // the messenger's Adventure path is exactly where a version-specific Error would come
            // from — means the message was blocked and only the explanation was lost, and saying
            // "letting the message through" there sends whoever reads it looking for a message that
            // was never delivered.
            if (event.isCancelled()) {
                logger.error("chat from " + sender.getName() + " was blocked, but telling them why "
                        + "failed; they were cut off with no explanation", broken);
            } else {
                logger.error("the chat pipeline threw; letting the message through", broken);
            }
        }
    }

    /**
     * The relay half, when ChatControl is installed: an untagged copy of the line for a player who is
     * not talking in a channel. A no-op without ChatControl, because {@link #onChat} already relayed.
     *
     * <p>MONITOR and {@code ignoreCancelled}: the line has survived everything that could block it,
     * including {@link #onChat}'s own deny, and its text is final, so what is relayed is what the
     * server actually showed rather than what was typed before another plugin filtered it.
     */
    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChatRelay(AsyncPlayerChatEvent event) {
        Player sender = event.getPlayer();
        if (sender == null || !deferRelay()) {
            return;
        }
        try {
            ChatControlChannels.UntaggedRelay decision = chatControl.untaggedRelay(sender);
            if (decision == ChatControlChannels.UntaggedRelay.BROKEN) {
                if (reportedBroken.compareAndSet(false, true)) {
                    // Once per boot. The integration logged the cause when it broke; this line says
                    // what that means for chat, without repeating it for every line anyone types.
                    logger.severe("ChatControl is installed but its channel integration is not "
                            + "working (" + chatControl.brokenReason() + "); in-game chat is not "
                            + "being relayed to Discord on this server");
                }
                return;
            }
            if (decision == ChatControlChannels.UntaggedRelay.SKIP) {
                // ChatControl routes this player's line into a channel; its post event relays it,
                // tagged. Relaying it here too would publish it as public chat.
                return;
            }
            pipeline.notifyObservers(
                    ChatMessage.of(sender.getUniqueId(), sender.getName(), event.getMessage()));
        } catch (Throwable broken) {
            // Same containment as onChat, and the same reason: a version-specific Error must not
            // escape into Bukkit's event machinery. The line was already delivered in game; only
            // the relay is lost.
            logger.error("relaying a chat line failed; it was delivered in game but not relayed",
                    broken);
        }
    }

    /** Whether relay waits for MONITOR: true exactly when ChatControl is installed. */
    private boolean deferRelay() {
        return chatControl != null && chatControl.installed();
    }
}
