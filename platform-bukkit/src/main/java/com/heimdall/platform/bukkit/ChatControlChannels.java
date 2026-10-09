package com.heimdall.platform.bukkit;

import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.pipeline.ChatMessage;
import com.heimdall.core.pipeline.ChatPipeline;
import com.heimdall.core.pipeline.Verdict;
import com.heimdall.core.platform.ChatChannels;
import com.heimdall.core.platform.PlayerHandle;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;

/**
 * ChatControl's channels, reached reflectively, and the hook that relays a channel line with its
 * channel attached. Departure D85.
 *
 * <h2>Why reflection</h2>
 *
 * <p>ChatControl is paid and not published to any Maven repository, so it cannot be a compile
 * dependency, and it is not the kind of plugin a server is guaranteed to have. Every type of its is
 * therefore reached through ChatControl's <em>own</em> class loader by name: Heimdall's loader cannot
 * see another plugin's classes. The names below were taken from ChatControl 12.2.18 with
 * {@code javap}; if a release moves one, resolution fails, the state becomes
 * {@link ChatChannels.State#BROKEN}, and everything downstream fails closed rather than quietly
 * relaying a staff channel as public chat.
 *
 * <h2>What the hook listens to, and what it does not</h2>
 *
 * <p>{@code ChannelPostChatEvent}, at {@link EventPriority#MONITOR} with {@code ignoreCancelled}.
 * ChatControl fires it from {@code Channel.sendMessage} for every line delivered into a channel,
 * including the ones sent by command ({@code /ch send}) that never fire a Bukkit chat event at all,
 * and a listener that cancels it stops the delivery. This hook never cancels it: by MONITOR the
 * channel has been decided, and the only question left is whether Heimdall relays it.
 *
 * <ul>
 *   <li>{@code isCancelledSilently()} lines are never relayed. ChatControl's rules shadow-blocked
 *       them: only the sender sees the line, so relaying it would show Discord what the server
 *       hid from everyone else.
 *   <li>Only a {@link Player} sender is chat. The console and command blocks can post into a
 *       channel too, and those are not a player talking.
 *   <li>{@code ChatChannelProxyEvent} (a line forwarded from another backend) is deliberately not
 *       hooked: the backend it was typed on relays its own chat, so hooking it would relay every
 *       network line once per server.
 * </ul>
 *
 * <p>The line goes through {@link ChatPipeline#dispatch} first, read-only: a muted or frozen player
 * whose line ChatControl delivered anyway is still not relayed. The verdict is never applied to the
 * ChatControl event. {@code ChatControlAPI.sendMessage} is not used for anything, in either
 * direction: it re-runs ChatControl's pipeline as the sender and fires this event again, which
 * would loop every Discord line straight back to Discord.
 *
 * <h2>Resolution</h2>
 *
 * <h2>Two kinds of failure</h2>
 *
 * <p>A <em>resolution</em> failure (a class, method or field that is not there, a type that is not
 * what it was, a linkage error) means the API moved, and it is permanent: the state becomes
 * {@code BROKEN} for the rest of the boot. ChatControl's <em>own code throwing</em> while Heimdall
 * calls it (an {@link InvocationTargetException}, say {@code getOnlinePlayers()} racing a player who
 * just quit) is one call's problem: that one lookup or line fails closed, a warning is logged at most
 * once a minute, and the next call tries again.
 *
 * <h2>Reloads</h2>
 *
 * <p>A plugin manager (PlugMan, ServerUtils) can unload ChatControl and load a fresh copy in a new
 * class loader. The hook and every handle here are bound to the old one, so once anything has been
 * resolved, ChatControl disappearing or coming back as a different instance is {@code BROKEN} with a
 * reason that says to restart, never a quiet {@code NONE} while the new copy routes channels
 * unobserved.
 *
 * <h2>Resolution</h2>
 *
 * <p>Lazy, and retried while unresolved, for the same load-order reason as LuckPerms (#796 / MC-10):
 * {@code softdepend} makes ChatControl enable first on an ordinary boot, but nothing guarantees it.
 * A missing plugin is re-looked-up at most once per {@link #RETRY_NANOS}, because
 * {@code getPlugin} is synchronized on the plugin manager and the chat listener asks per message.
 * A <em>found</em> plugin is cached, and only re-checked while it is disabled (see Reloads). A
 * resolution failure is cached for good, as {@code BROKEN}: a class that was not there a second ago
 * will not be there now, and retrying it per message would turn one error into a log flood.
 *
 * <p>Every public method is safe from any thread, does not block beyond ChatControl's own reads,
 * and never throws: anything reflective is caught as {@link Throwable}, because the failure this
 * class exists to contain (a moved class, a changed signature) arrives as an {@link Error}.
 */
final class ChatControlChannels implements ChatChannels {

    /** The name ChatControl registers under. */
    static final String PLUGIN_NAME = "ChatControl";

    static final String CHANNEL_CLASS = "org.mineacademy.chatcontrol.model.Channel";
    static final String POST_EVENT_CLASS = "org.mineacademy.chatcontrol.api.ChannelPostChatEvent";
    static final String SETTINGS_CLASS = "org.mineacademy.chatcontrol.settings.Settings$Channels";

    /** How long a "ChatControl is not installed" answer is trusted before asking Bukkit again. */
    static final long RETRY_NANOS = 5_000_000_000L;

    /** At most one warning per this interval for ChatControl's own code throwing. */
    static final long TRANSIENT_WARN_NANOS = 60_000_000_000L;

    /**
     * What this class needs from the server, as a seam.
     *
     * <p>Exists for the tests: a mocked {@link Plugin}'s class is Mockito's, so its loader cannot
     * stand in for ChatControl's, and registering an event needs a live plugin manager.
     */
    interface Environment {

        /** The ChatControl plugin if it is loaded (enabled or not), else {@code null}. */
        Plugin findChatControl();

        /** The class loader ChatControl's own classes live in. */
        ClassLoader loaderOf(Plugin chatControl);

        /** Registers {@code executor} for {@code type} at MONITOR, ignoring cancelled events. */
        void register(Class<? extends Event> type, Listener listener, EventExecutor executor);
    }

    /** What a legacy chat line should do once Heimdall knows ChatControl is installed. */
    enum UntaggedRelay {
        /** Relay it without a channel: the player is not using channels, or channels are off. */
        RELAY,
        /** Leave it to the channel hook: the player's line is going into a channel. */
        SKIP,
        /** The hook cannot be trusted, so relay nothing (fail closed). */
        BROKEN,
        /** ChatControl threw while answering for this one line; drop it (fail closed), try again next. */
        TRANSIENT
    }

    private final HeimdallLogger logger;
    private final Environment environment;
    private final Function<Player, PlayerHandle> handles;
    private final long retryNanos;

    private final Object lock = new Object();

    /** The ChatControl plugin once found; replaced only before anything was bound to it. */
    private volatile Plugin chatControl;

    /**
     * {@link System#nanoTime()} before which a missing ChatControl is not looked up again. Starts at
     * construction time rather than zero, because {@code nanoTime}'s origin is arbitrary and may be
     * negative.
     */
    private volatile long nextLookupAt;

    /** {@link System#nanoTime()} before which a transient ChatControl failure is not warned again. */
    private volatile long nextTransientWarnAt;

    /** The resolved reflective handles; {@code null} until ChatControl is enabled and resolved. */
    private volatile Api api;

    /** Non-null once something reflective failed. Permanent: see the class javadoc. */
    private volatile String brokenBecause;

    /** Set once at bootstrap; the hook registers only when there is a pipeline to feed. */
    private volatile ChatPipeline pipeline;

    /** Whether the post-event listener is registered. Written once, by the registration claimant. */
    private volatile boolean hooked;

    /** Claimed by the one thread that attempts the registration; never released. */
    private final AtomicBoolean registrationClaimed = new AtomicBoolean();

    /** The channel names last read successfully; see {@link #channelNames()}. */
    private volatile List<String> lastNames;

    ChatControlChannels(
            HeimdallLogger logger,
            Environment environment,
            Function<Player, PlayerHandle> handles,
            long retryNanos) {
        this.logger = logger;
        this.environment = environment;
        this.handles = handles;
        this.retryNanos = retryNanos;
        long now = System.nanoTime();
        this.nextLookupAt = now;
        this.nextTransientWarnAt = now;
    }

    /** The production wiring: Bukkit's plugin manager, owned by {@code heimdall}. */
    static ChatControlChannels create(
            HeimdallLogger logger, Plugin heimdall, Function<Player, PlayerHandle> handles) {
        return new ChatControlChannels(logger, new BukkitEnvironment(heimdall), handles, RETRY_NANOS);
    }

    /**
     * Hands over the chat pipeline and tries to hook ChatControl now.
     *
     * <p>Called once from the bootstrap, while Heimdall is enabling: on an ordinary boot ChatControl
     * is already enabled by then ({@code softdepend}), so the hook registers here, on the main
     * thread, and the first {@link #state()} has nothing left to do.
     */
    void attach(ChatPipeline chatPipeline) {
        this.pipeline = chatPipeline;
        state();
    }

    /**
     * Whether ChatControl is installed at all, enabled or not, working or not.
     *
     * <p>This is what switches the legacy chat listener from "relay at NORMAL, as always" to
     * "check at NORMAL, decide at MONITOR". Presence, not health: a broken hook must still keep the
     * listener from relaying at NORMAL, or a channelled server would leak exactly when the
     * integration that knows about channels stopped working.
     */
    boolean installed() {
        return chatControl() != null;
    }

    @Override
    public State state() {
        // Looked up first: the lookup is where a reload is noticed, and it marks the integration
        // broken, so checking brokenBecause before it would answer NONE for that one call.
        Plugin plugin = chatControl();
        if (brokenBecause != null) {
            return State.BROKEN;
        }
        if (plugin == null || !isEnabled(plugin)) {
            // Not installed, or installed and not running: either way ChatControl is not routing
            // chat, so chat is one room.
            return State.NONE;
        }
        Api resolved = resolve(plugin);
        if (resolved == null) {
            return brokenBecause != null ? State.BROKEN : State.NONE;
        }
        if (!hooked) {
            // No pipeline yet (bootstrap has not attached), so nothing could be relayed with a
            // channel. Not ACTIVE: that state promises the hook is live.
            return brokenBecause != null ? State.BROKEN : State.NONE;
        }
        Object enabled;
        try {
            enabled = resolved.channelsEnabled.get(null);
        } catch (Throwable failed) {
            markBroken("reading Settings.Channels.ENABLED failed", failed);
            return State.BROKEN;
        }
        // Boolean.TRUE only. null is "ChatControl has not loaded its settings yet", which is not a
        // channelled server yet, and isUsingChannels would throw on it.
        return Boolean.TRUE.equals(enabled) ? State.ACTIVE : State.NONE;
    }

    /**
     * {@inheritDoc}
     *
     * <p>When ChatControl's own code throws while listing (a one-call failure, not a broken API),
     * this answers the names it last read successfully rather than an empty list. An empty list next
     * to an {@code active} state would tell the bot every channel had vanished; the last good answer
     * makes an inventory poll compare equal and send nothing, which is "skip this poll". Before any
     * successful read there is nothing to fall back on, and the answer is empty.
     */
    @Override
    public List<String> channelNames() {
        if (state() != State.ACTIVE) {
            return Collections.emptyList();
        }
        List<String> names = names(api);
        if (names != null) {
            lastNames = names;
            return names;
        }
        if (brokenBecause != null) {
            return Collections.emptyList();
        }
        List<String> previous = lastNames;
        return previous == null ? Collections.<String>emptyList() : previous;
    }

    @Override
    public Optional<Collection<PlayerHandle>> members(String channel) {
        if (channel == null || channel.isEmpty() || state() != State.ACTIVE) {
            return Optional.empty();
        }
        Api resolved = api;
        List<String> names = names(resolved);
        if (names == null) {
            return Optional.empty();
        }
        // ChatControl's own lookup is not documented as case-insensitive, and the name usually
        // arrives from a dashboard selection. Resolving the canonical spelling first makes the match
        // independent of how ChatControl compares.
        String canonical = null;
        for (String name : names) {
            if (name != null && name.equalsIgnoreCase(channel)) {
                canonical = name;
                break;
            }
        }
        if (canonical == null) {
            return Optional.empty();
        }
        try {
            Object found = resolved.findChannel.invoke(null, canonical);
            if (found == null) {
                return Optional.empty();
            }
            Object online = resolved.channelOnlinePlayers.invoke(found);
            List<PlayerHandle> members = new ArrayList<PlayerHandle>();
            if (online instanceof Map) {
                for (Object member : ((Map<?, ?>) online).keySet()) {
                    if (member instanceof Player) {
                        members.add(handles.apply((Player) member));
                    }
                }
            }
            return Optional.<Collection<PlayerHandle>>of(Collections.unmodifiableList(members));
        } catch (Throwable failed) {
            fail("looking up a ChatControl channel's members", failed);
            return Optional.empty();
        }
    }

    /**
     * For the legacy chat listener's MONITOR handler: what to do with a line {@code player} typed,
     * given that ChatControl is installed.
     */
    UntaggedRelay untaggedRelay(Player player) {
        State state = state();
        if (state == State.BROKEN) {
            return UntaggedRelay.BROKEN;
        }
        if (state == State.NONE) {
            return UntaggedRelay.RELAY;
        }
        try {
            Object using = api.isUsingChannels.invoke(null, player);
            return Boolean.TRUE.equals(using) ? UntaggedRelay.SKIP : UntaggedRelay.RELAY;
        } catch (Throwable failed) {
            return fail("checking whether a player uses ChatControl channels", failed)
                    ? UntaggedRelay.BROKEN
                    : UntaggedRelay.TRANSIENT;
        }
    }

    /** Why the integration is broken, or {@code null}. For a log line, never shown to players. */
    String brokenReason() {
        return brokenBecause;
    }

    // ── Resolution ───────────────────────────────────────────────────────────

    /**
     * The ChatControl plugin, looked up again at most once per retry interval while missing, and
     * re-checked on every call while the cached instance is disabled, which is what a reload looks
     * like from here.
     */
    private Plugin chatControl() {
        Plugin found = chatControl;
        if (found != null) {
            if (brokenBecause == null && !isEnabled(found)) {
                checkReloaded(found);
            }
            return chatControl;
        }
        long now = System.nanoTime();
        if (now - nextLookupAt < 0) {
            return null;
        }
        try {
            found = environment.findChatControl();
        } catch (Throwable failed) {
            found = null;
        }
        if (found == null) {
            nextLookupAt = now + retryNanos;
            return null;
        }
        chatControl = found;
        return found;
    }

    /**
     * The cached ChatControl is disabled: find out whether it is the same plugin switched off, or
     * gone, or replaced by a fresh copy.
     *
     * <p>Switched off in place is {@code NONE}: it is not routing chat, and if it is switched back on
     * it is the same classes, so the hook is still valid. Gone or replaced, once anything was bound to
     * the old class loader, is {@code BROKEN}: the hook listens on the old copy's event class and
     * would never hear the new one route a staff line. Before anything was bound there is nothing
     * stale, so the new answer is simply adopted.
     */
    private void checkReloaded(Plugin cached) {
        // Not throttled, unlike the missing-plugin lookup. A throttle here would leave a window of
        // up to RETRY_NANOS in which a reloaded copy routes staff lines while this answers NONE, and
        // a disabled cached instance is rare (a reload, or ChatControl switched off by hand), so a
        // synchronized getPlugin per call for that stretch is the cheaper risk.
        Plugin current;
        try {
            current = environment.findChatControl();
        } catch (Throwable failed) {
            // Could not ask; keep the current answer and ask again next interval.
            return;
        }
        if (current == cached) {
            return;
        }
        synchronized (lock) {
            if (api == null && !hooked) {
                chatControl = current;
                return;
            }
        }
        markBroken(current == null
                ? "ChatControl was unloaded after Heimdall hooked it; restart the server to re-hook"
                : "ChatControl was reloaded; restart the server to re-hook", null);
    }

    private static boolean isEnabled(Plugin plugin) {
        try {
            return plugin.isEnabled();
        } catch (Throwable failed) {
            return false;
        }
    }

    /**
     * Resolves the reflective handles and, once there is a pipeline, registers the hook. Returns the
     * handles, or {@code null} if resolution failed (and {@link #brokenBecause} says why).
     */
    private Api resolve(Plugin plugin) {
        Api resolved = api;
        if (resolved != null && (hooked || pipeline == null)) {
            return resolved;
        }
        if (brokenBecause != null) {
            return null;
        }
        // Neither the class loading nor the registration below happens under `lock`. Loading runs
        // ChatControl's static initialisers and takes class-loader locks, and registerEvent belongs
        // to the server's plugin manager, whose monitor the server holds while dispatching sync
        // events and disabling plugins. `lock` is taken from inside such dispatches (markBroken,
        // from the channel hook), so holding it across either call could close a loop with them.
        if (resolved == null) {
            Api loaded;
            try {
                loaded = Api.load(environment.loaderOf(plugin));
            } catch (Throwable failed) {
                markBroken("ChatControl's API could not be found (a ChatControl update may have "
                        + "moved it)", failed);
                return null;
            }
            synchronized (lock) {
                if (chatControl != plugin) {
                    // Replaced while loading (a reload during the very first resolution). These
                    // handles belong to the old copy; the next call starts over with the new one.
                    return null;
                }
                if (api == null) {
                    api = loaded;
                }
                resolved = api;
            }
        }
        if (!hooked && pipeline != null && registrationClaimed.compareAndSet(false, true)) {
            // One attempt, ever, by whichever thread claims it. Another thread arriving meanwhile
            // sees hooked=false and answers NONE for that call, which is the truth until this lands.
            final Api bound = resolved;
            try {
                environment.register(bound.postEvent, new Listener() {
                }, new EventExecutor() {
                    @Override
                    public void execute(Listener listener, Event event) {
                        onChannelPost(bound, event);
                    }
                });
                hooked = true;
                logger.info("ChatControl detected: Discord relay follows its chat channels");
            } catch (Throwable failed) {
                markBroken("registering the ChatControl channel listener failed", failed);
                return null;
            }
        }
        return brokenBecause != null ? null : resolved;
    }

    /** Channel names, or {@code null} after marking the integration broken. */
    private List<String> names(Api resolved) {
        if (resolved == null) {
            return null;
        }
        try {
            Object names = resolved.channelNames.invoke(null);
            List<String> out = new ArrayList<String>();
            if (names instanceof Collection) {
                for (Object name : (Collection<?>) names) {
                    if (name != null) {
                        out.add(name.toString());
                    }
                }
            }
            return Collections.unmodifiableList(out);
        } catch (Throwable failed) {
            fail("listing ChatControl channels", failed);
            return null;
        }
    }

    /**
     * Classifies a failure from a reflective call.
     *
     * <p>An {@link InvocationTargetException} is ChatControl's own code throwing for this one call: a
     * rate-limited warning, and the caller fails closed for that call only. Anything else (a missing
     * member, an access or linkage error, a type that is not what it was, an argument the method no
     * longer takes) means the API moved, and the integration is broken for good.
     *
     * <p>Package-private so a test can pin the classification itself: the stand-in classes can
     * produce an {@link InvocationTargetException} naturally, but not every shape of a moved API.
     *
     * @return {@code true} if the integration is now broken
     */
    boolean fail(String what, Throwable failure) {
        if (failure instanceof InvocationTargetException) {
            warnTransient(what, ((InvocationTargetException) failure).getCause());
            return false;
        }
        markBroken(what + " failed", failure);
        return true;
    }

    /**
     * At most one warning a minute. Names the exception's class only, never its message: this runs
     * while a chat line or a Discord message is in flight, and a message is the one place a third
     * party's code could put that text.
     */
    private void warnTransient(String what, Throwable thrown) {
        long now = System.nanoTime();
        if (now - nextTransientWarnAt < 0) {
            return;
        }
        nextTransientWarnAt = now + TRANSIENT_WARN_NANOS;
        logger.warn("ChatControl threw " + (thrown == null ? "an exception" : thrown.getClass().getName())
                + " while Heimdall was " + what + "; that one relay was skipped, nothing else is "
                + "affected. Repeats are reported at most once a minute.");
    }

    /**
     * Records the integration as broken, once, with an error naming why. No chat text can reach this
     * line: the causes are resolution failures and reloads, whose messages name classes and methods.
     */
    private void markBroken(String why, Throwable cause) {
        synchronized (lock) {
            if (brokenBecause != null) {
                return;
            }
            brokenBecause = cause == null ? why : why + ": " + cause;
        }
        logger.error("ChatControl channel integration disabled: " + why + ". Chat relay to and "
                + "from Discord is paused on this server until this is fixed, so a staff channel "
                + "cannot leak as public chat.", cause);
    }

    // ── The hook ─────────────────────────────────────────────────────────────

    /**
     * One {@code ChannelPostChatEvent}: dispatch read-only, then relay with the channel attached.
     *
     * <p>Contained as {@link Throwable}: this runs inside ChatControl's delivery, and an escaped
     * error would land in its stack, not ours. A failure here is a reflective one, so it marks the
     * integration broken (relay stops) rather than being retried per message.
     */
    private void onChannelPost(Api bound, Event event) {
        if (!bound.postEvent.isInstance(event)) {
            return;
        }
        ChatPipeline chat = pipeline;
        if (chat == null) {
            return;
        }
        ChatMessage message;
        try {
            if (Boolean.TRUE.equals(bound.eventCancelledSilently.invoke(event))) {
                return;
            }
            Object sender = bound.eventSender.invoke(event);
            if (!(sender instanceof Player)) {
                return;
            }
            Object channel = bound.eventChannel.invoke(event);
            Object name = channel == null ? null : bound.channelName.invoke(channel);
            if (name == null || name.toString().trim().isEmpty()) {
                // A channel line with no channel name cannot be routed, and relaying it untagged
                // would be the leak this class exists to prevent.
                return;
            }
            Object text = bound.eventMessage.invoke(event);
            Player player = (Player) sender;
            message = ChatMessage.inChannel(
                    player.getUniqueId(), player.getName(), text == null ? "" : text.toString(),
                    name.toString());
        } catch (Throwable failed) {
            // Only the reflective reads above can say anything about ChatControl's health. A throw
            // from its own getters drops this one line; anything else means the API moved.
            fail("reading a ChatControl channel message", failed);
            return;
        }

        // Heimdall's own pipeline, outside the catch above on purpose: an Error from a check or an
        // observer is a Heimdall bug, and recording it as "ChatControl is broken" would pause relay
        // for the rest of the boot and blame the wrong plugin.
        try {
            Verdict verdict = chat.dispatch(message);
            if (verdict.isDeny()) {
                // Read-only: ChatControl already decided to deliver it, and Heimdall's verdict is
                // not applied to its event. Not relaying is the whole of the consequence here.
                return;
            }
            chat.notifyObservers(message);
        } catch (Throwable failed) {
            logger.error("the chat pipeline threw on a ChatControl channel line; it was delivered "
                    + "in game but not relayed", failed);
        }
    }

    // ── Reflective handles ───────────────────────────────────────────────────

    /** Every class, method and field this integration touches, resolved once. */
    static final class Api {

        final Class<? extends Event> postEvent;
        final Method eventChannel;
        final Method eventSender;
        final Method eventMessage;
        final Method eventCancelledSilently;
        final Method channelNames;
        final Method findChannel;
        final Method isUsingChannels;
        final Method channelName;
        final Method channelOnlinePlayers;
        final Field channelsEnabled;

        private Api(ClassLoader loader) throws ReflectiveOperationException {
            Class<?> event = Class.forName(POST_EVENT_CLASS, true, loader);
            if (!Event.class.isAssignableFrom(event)) {
                throw new ClassCastException(POST_EVENT_CLASS + " is not a Bukkit event");
            }
            this.postEvent = event.asSubclass(Event.class);
            this.eventChannel = event.getMethod("getChannel");
            this.eventSender = event.getMethod("getSender");
            this.eventMessage = event.getMethod("getMessage");
            this.eventCancelledSilently = event.getMethod("isCancelledSilently");

            Class<?> channel = Class.forName(CHANNEL_CLASS, true, loader);
            this.channelNames = channel.getMethod("getChannelNames");
            this.findChannel = channel.getMethod("findChannel", String.class);
            this.isUsingChannels = channel.getMethod("isUsingChannels", Player.class);
            this.channelName = channel.getMethod("getName");
            this.channelOnlinePlayers = channel.getMethod("getOnlinePlayers");

            Class<?> settings = Class.forName(SETTINGS_CLASS, true, loader);
            this.channelsEnabled = settings.getField("ENABLED");
        }

        static Api load(ClassLoader loader) throws ReflectiveOperationException {
            if (loader == null) {
                throw new ClassNotFoundException("ChatControl has no class loader");
            }
            return new Api(loader);
        }
    }

    /** The real server. */
    private static final class BukkitEnvironment implements Environment {

        private final Plugin heimdall;

        BukkitEnvironment(Plugin heimdall) {
            this.heimdall = heimdall;
        }

        @Override
        public Plugin findChatControl() {
            return Bukkit.getPluginManager().getPlugin(PLUGIN_NAME);
        }

        @Override
        public ClassLoader loaderOf(Plugin chatControl) {
            return chatControl.getClass().getClassLoader();
        }

        @Override
        public void register(Class<? extends Event> type, Listener listener, EventExecutor executor) {
            Bukkit.getPluginManager()
                    .registerEvent(type, listener, EventPriority.MONITOR, executor, heimdall, true);
        }
    }
}
