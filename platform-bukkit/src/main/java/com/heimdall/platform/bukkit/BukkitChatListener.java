package com.heimdall.platform.bukkit;

import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.pipeline.ChatMessage;
import com.heimdall.core.pipeline.ChatPipeline;
import com.heimdall.core.pipeline.Verdict;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntSupplier;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.plugin.EventExecutor;

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
 * knows which audience the line is for, or whether ChatControl will let it through at all.
 *
 * <ul>
 *   <li>A player who <strong>is</strong> using ChatControl channels is never relayed from here.
 *       ChatControl's own {@code ChannelPostChatEvent} relays the line with its channel, from
 *       {@link ChatControlChannels}.
 *   <li>A player who is not (or a server with channels switched off) is relayed untagged, but only
 *       once ChatControl has finished with the line. Where that is depends on the server.
 *   <li>If the ChatControl integration is broken, nothing is relayed (fail closed), and that is
 *       logged once rather than per line.
 * </ul>
 *
 * <h2>Where ChatControl finishes: Spigot and Paper differ</h2>
 *
 * <p>On Spigot ChatControl works in this same legacy event, so the untagged relay runs at this
 * event's MONITOR. On Paper it does <em>everything</em> (mutes, rules, the no-write checks, private
 * message auto-mode, which turns chat into a {@code /tell} and cancels the chat event) in Paper's
 * {@code AsyncChatEvent}, which Paper fires <strong>after</strong> this one, on the same thread
 * ({@code ChatProcessor.process}: the legacy async event is posted, then the sync legacy event if it
 * has listeners, then {@code processModern} posts {@code AsyncChatEvent} with the legacy cancelled
 * flag carried over, still on the calling thread). Relaying at this event's MONITOR there would
 * publish private messages, ChatControl-muted players and rule-blocked lines with ChatControl's
 * default config.
 *
 * <p>So where {@code AsyncChatEvent} exists, this event's MONITOR only <em>parks</em> the line in a
 * {@link ThreadLocal}, and a MONITOR executor on {@code AsyncChatEvent} (registered by name, reading
 * nothing but Bukkit's own {@link Event}, {@link Cancellable} and {@link PlayerEvent} types, so the
 * shading problem above never arises) takes it back and relays it only if that event was not
 * cancelled. The ThreadLocal is also reset at LOWEST, so nothing parked by an event that never
 * reached the second half can be picked up by a later one.
 *
 * <p>On both, a line whose audience ChatControl shrank to the sender alone while other players are
 * online is not relayed: that is how ChatControl shadow-blocks a line without cancelling it (only the
 * sender sees it). The check counts {@link Player}s in the recipients (Spigot) or the viewers
 * (Paper), and nothing else about them. A genuinely private line nobody else could see is dropped by
 * the same rule, which is the accepted direction.
 *
 * <h2>One decision per line</h2>
 *
 * <p>Whether a line takes the "relay at NORMAL" path or the "decide later" path is decided once, at
 * LOWEST, and carried in the same per-thread state. Deciding separately at NORMAL and MONITOR would
 * let a line relay twice if ChatControl became visible between the two.
 *
 * <p>Whether ChatControl is <em>installed</em> is the switch, not whether it is healthy: a broken
 * hook still moves relay out of the NORMAL handler, or a channelled server would leak exactly when
 * the code that understands channels stopped working. Departure D85.
 */
final class BukkitChatListener implements Listener {

    /** Whether Paper's {@code AsyncChatEvent} is in play, and whether the hook on it is live. */
    enum ModernState {
        /** No such event on this server (Spigot, older Paper): relay at the legacy MONITOR. */
        ABSENT,
        /** The event exists and the hook is not registered yet: relay nothing (fail closed). */
        PENDING,
        /** The event exists and the hook is live: the legacy MONITOR parks, the hook relays. */
        HOOKED,
        /** The event exists and cannot be hooked: relay nothing (fail closed), said once. */
        FAILED
    }

    /** Registers the {@code AsyncChatEvent} executor. A seam, so tests need no plugin manager. */
    interface ModernRegistrar {
        void register(Class<? extends Event> type, EventExecutor executor) throws Exception;
    }

    private final HeimdallLogger logger;
    private final ChatPipeline pipeline;
    private final BukkitMessenger messenger;
    private final ChatControlChannels chatControl;
    private final ModernChat modern;
    private final IntSupplier onlineCount;

    private volatile ModernState modernState;

    /**
     * This thread's line in flight: the one decision about which path it takes, and, on Paper, the
     * untagged copy parked between the two events. Per listener rather than static, so two plugin
     * instances across a reload never share one.
     */
    private final ThreadLocal<ChatState> current = new ThreadLocal<ChatState>();

    /** Whether the "relay paused, integration broken" error has been logged. Once per boot. */
    private final AtomicBoolean reportedBroken = new AtomicBoolean();

    /** Whether the "Paper chat hook unavailable" error has been logged. Once per boot. */
    private final AtomicBoolean reportedModernUnavailable = new AtomicBoolean();

    /**
     * @param modern Paper's {@code AsyncChatEvent} as {@link ModernChat#detect} found it, or
     *     {@code null} where it does not exist
     * @param onlineCount how many players are online, for the shadow-block check
     */
    BukkitChatListener(
            HeimdallLogger logger,
            ChatPipeline pipeline,
            BukkitMessenger messenger,
            ChatControlChannels chatControl,
            ModernChat modern,
            IntSupplier onlineCount) {
        this.logger = logger;
        this.pipeline = pipeline;
        this.messenger = messenger;
        this.chatControl = chatControl;
        this.modern = modern;
        this.onlineCount = onlineCount;
        this.modernState = modern == null
                ? ModernState.ABSENT
                : (modern.usable() ? ModernState.PENDING : ModernState.FAILED);
    }

    /**
     * Registers the {@code AsyncChatEvent} MONITOR executor, where that event exists.
     *
     * <p>Registered whether or not ChatControl is installed: it costs one ThreadLocal read per line
     * when nothing is parked, and a ChatControl that appears later then needs nothing more. With the
     * event present but unhookable, chat relay on a ChatControl server stays off rather than falling
     * back to the legacy MONITOR, which is exactly the leak the hook exists to close.
     */
    void installModern(ModernRegistrar registrar) {
        if (modernState != ModernState.PENDING) {
            return;
        }
        try {
            registrar.register(modern.type, new EventExecutor() {
                @Override
                public void execute(Listener listener, Event event) {
                    onModernChat(event);
                }
            });
            modernState = ModernState.HOOKED;
        } catch (Throwable failed) {
            modernState = ModernState.FAILED;
            logger.error("could not listen to Paper's AsyncChatEvent; with ChatControl installed, "
                    + "in-game chat will not be relayed to Discord on this server", failed);
        }
    }

    /** Where the Paper hook stands. For the bootstrap's log line and the tests. */
    ModernState modernState() {
        return modernState;
    }

    /**
     * Starts this line's per-thread state, replacing whatever an earlier line left behind, and makes
     * the one decision about which path it takes.
     *
     * <p>LOWEST, and not {@code ignoreCancelled}: it must run for every line, so nothing parked by a
     * previous event on this thread can survive into this one.
     */
    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChatStart(AsyncPlayerChatEvent event) {
        try {
            current.set(new ChatState(event, deferRelay()));
        } catch (Throwable broken) {
            current.remove();
            logger.error("starting chat relay state failed", broken);
        }
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
            Verdict verdict = stateFor(event).deferred
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
     * The legacy relay half, when ChatControl is installed. A no-op without ChatControl, because
     * {@link #onChat} already relayed.
     *
     * <p>MONITOR, and not {@code ignoreCancelled}, so it always runs and can release this thread's
     * state on a server where nothing else will; a cancelled line is still never relayed. Its text is
     * read here because it is final for the legacy event: what is relayed is what the server showed,
     * not what was typed before another plugin filtered it.
     */
    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChatRelay(AsyncPlayerChatEvent event) {
        ChatState state = stateFor(event);
        ModernState hook = modernState;
        if (hook != ModernState.HOOKED) {
            // Nothing will come back for this line on this thread, so nothing may stay behind.
            current.remove();
        }
        Player sender = event.getPlayer();
        if (sender == null || event.isCancelled() || !state.deferred) {
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
            if (decision != ChatControlChannels.UntaggedRelay.RELAY) {
                // SKIP: ChatControl routes this player's line into a channel, and its post event
                // relays it tagged. TRANSIENT: ChatControl threw answering for this line; drop it.
                return;
            }
            ChatMessage message =
                    ChatMessage.of(sender.getUniqueId(), sender.getName(), event.getMessage());
            switch (hook) {
                case ABSENT:
                    // Spigot: ChatControl has finished with this event by MONITOR.
                    if (shrunkToSender(event.getRecipients(), sender.getUniqueId())) {
                        return;
                    }
                    pipeline.notifyObservers(message);
                    return;
                case HOOKED:
                    // Paper: ChatControl has not even started. Park it for onModernChat.
                    state.pending = message;
                    return;
                default:
                    if (reportedModernUnavailable.compareAndSet(false, true)) {
                        logger.severe("Paper's AsyncChatEvent is present but not hooked, so "
                                + "Heimdall cannot see what ChatControl does with a line; in-game "
                                + "chat is not being relayed to Discord on this server");
                    }
                    return;
            }
        } catch (Throwable broken) {
            // Same containment as onChat, and the same reason: a version-specific Error must not
            // escape into Bukkit's event machinery. The line was already delivered in game; only
            // the relay is lost.
            logger.error("relaying a chat line failed; it was delivered in game but not relayed",
                    broken);
        }
    }

    /**
     * The Paper relay half: Paper's {@code AsyncChatEvent} at MONITOR, after ChatControl.
     *
     * <p>Takes whatever {@link #onChatRelay} parked on this thread, always clearing it, and relays it
     * only if the event was not cancelled (ChatControl's mutes, rules and private-message auto-mode
     * all cancel), the event is this sender's, and ChatControl did not shrink the audience to the
     * sender alone. It reads the event through Bukkit types and one reflective {@code viewers()}
     * call treated as a plain collection; the message component is never touched.
     */
    void onModernChat(Event event) {
        ChatState state = current.get();
        current.remove();
        ChatMessage pending = state == null ? null : state.pending;
        if (pending == null) {
            return;
        }
        state.pending = null;
        try {
            if (event instanceof Cancellable && ((Cancellable) event).isCancelled()) {
                return;
            }
            if (!(event instanceof PlayerEvent)) {
                return;
            }
            Player player = ((PlayerEvent) event).getPlayer();
            if (player == null || !pending.senderUuid().equals(player.getUniqueId())) {
                // Not the line that was parked: some other plugin fired this event on its own.
                // Dropping is the fail-closed answer; the parked line is gone either way.
                return;
            }
            Object viewers = modern.viewers.invoke(event);
            if (!(viewers instanceof Collection)
                    || shrunkToSender((Collection<?>) viewers, pending.senderUuid())) {
                return;
            }
            pipeline.notifyObservers(pending);
        } catch (Throwable broken) {
            logger.error("relaying a chat line from Paper's chat event failed; it was delivered in "
                    + "game but not relayed", broken);
        }
    }

    /**
     * Whether an audience says ChatControl shadow-blocked the line: no players at all, or the sender
     * alone while somebody else is online. Players are counted; nothing else about the audience is
     * read (Paper's viewers include the console, which is not a player).
     */
    private boolean shrunkToSender(Collection<?> audience, UUID sender) {
        if (audience == null) {
            return true;
        }
        int players = 0;
        boolean senderIncluded = false;
        for (Object member : audience) {
            if (member instanceof Player) {
                players++;
                if (sender.equals(((Player) member).getUniqueId())) {
                    senderIncluded = true;
                }
            }
        }
        if (players == 0) {
            return true;
        }
        return players == 1 && senderIncluded && onlineCount.getAsInt() > 1;
    }

    /** This thread's state for {@code event}, made now if LOWEST did not make it. */
    private ChatState stateFor(AsyncPlayerChatEvent event) {
        ChatState state = current.get();
        if (state != null && state.event.get() == event) {
            return state;
        }
        ChatState fresh = new ChatState(event, deferRelay());
        current.set(fresh);
        return fresh;
    }

    /** Whether relay waits for a later handler: true exactly when ChatControl is installed. */
    private boolean deferRelay() {
        return chatControl != null && chatControl.installed();
    }

    /** One line in flight on one thread. */
    private static final class ChatState {

        /** Weak, so a line nothing came back for never keeps its event alive. */
        final WeakReference<AsyncPlayerChatEvent> event;
        final boolean deferred;

        /** The untagged copy parked for {@link #onModernChat}; cleared when taken. */
        ChatMessage pending;

        ChatState(AsyncPlayerChatEvent event, boolean deferred) {
            this.event = new WeakReference<AsyncPlayerChatEvent>(event);
            this.deferred = deferred;
        }
    }

    /** Paper's {@code AsyncChatEvent}, reached by name, and the one method read from it. */
    static final class ModernChat {

        static final String CLASS_NAME = "io.papermc.paper.event.player.AsyncChatEvent";

        final Class<? extends Event> type;
        final Method viewers;

        private ModernChat(Class<? extends Event> type, Method viewers) {
            this.type = type;
            this.viewers = viewers;
        }

        /**
         * Looks the event up through {@code loader}.
         *
         * @return {@code null} when the class does not exist; an unusable instance when it exists but
         *     is not what it should be, so the listener fails closed rather than treating the server
         *     as Spigot
         */
        static ModernChat detect(ClassLoader loader) {
            Class<?> found;
            try {
                found = Class.forName(CLASS_NAME, false, loader);
            } catch (ClassNotFoundException absent) {
                return null;
            } catch (Throwable broken) {
                return new ModernChat(null, null);
            }
            return of(found);
        }

        /** Describes a given class as the modern chat event. Package-private for the tests. */
        static ModernChat of(Class<?> type) {
            if (type == null || !Event.class.isAssignableFrom(type)) {
                return new ModernChat(null, null);
            }
            Method viewers;
            try {
                viewers = type.getMethod("viewers");
            } catch (Throwable missing) {
                viewers = null;
            }
            return new ModernChat(type.asSubclass(Event.class), viewers);
        }

        boolean usable() {
            return type != null && viewers != null;
        }
    }
}
