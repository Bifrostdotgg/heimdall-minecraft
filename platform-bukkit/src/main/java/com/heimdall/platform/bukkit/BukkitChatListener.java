package com.heimdall.platform.bukkit;

import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.pipeline.ChatMessage;
import com.heimdall.core.pipeline.ChatPipeline;
import com.heimdall.core.pipeline.Verdict;
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
 */
final class BukkitChatListener implements Listener {

    private final HeimdallLogger logger;
    private final ChatPipeline pipeline;
    private final BukkitMessenger messenger;

    BukkitChatListener(HeimdallLogger logger, ChatPipeline pipeline, BukkitMessenger messenger) {
        this.logger = logger;
        this.pipeline = pipeline;
        this.messenger = messenger;
    }

    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        Player sender = event.getPlayer();
        if (sender == null) {
            return;
        }
        try {
            Verdict verdict = pipeline.dispatchWithObservers(
                    ChatMessage.of(sender.getUniqueId(), sender.getName(), event.getMessage()));
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
}
