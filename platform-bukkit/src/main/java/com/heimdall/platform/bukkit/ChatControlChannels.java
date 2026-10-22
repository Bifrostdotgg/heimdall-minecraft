package com.heimdall.platform.bukkit;

import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.pipeline.ChatMessage;
import com.heimdall.core.platform.PlayerHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;

/**
 * ChatControl's channels, reached reflectively, and the hook that relays a channel line with its
 * channel attached. Departure D85.
 *
 * <p>Finding the plugin, resolving it, registering the hook once, reloads and failure handling are
 * {@link PluginChannelHook}'s, shared with ZelChat. The names below were taken from ChatControl
 * 12.2.18 with {@code javap}.
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
 * <p>The line goes through the chat pipeline first, read-only ({@link PluginChannelHook#relay}).
 * {@code ChatControlAPI.sendMessage} is not used for anything, in either direction: it re-runs
 * ChatControl's pipeline as the sender and fires this event again, which would loop every Discord
 * line straight back to Discord.
 */
final class ChatControlChannels extends PluginChannelHook<ChatControlChannels.Api> {

    /** The name ChatControl registers under. */
    static final String PLUGIN_NAME = "ChatControl";

    /** What the {@code bridge.channels} frame calls this plugin. A wire contract. */
    static final String PROVIDER = "chatcontrol";

    static final String CHANNEL_CLASS = "org.mineacademy.chatcontrol.model.Channel";
    static final String POST_EVENT_CLASS = "org.mineacademy.chatcontrol.api.ChannelPostChatEvent";
    static final String SETTINGS_CLASS = "org.mineacademy.chatcontrol.settings.Settings$Channels";

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

        /**
         * Removes everything registered for {@code listener}. A default no-op so a test environment
         * that never registers anything need not implement it.
         */
        default void unregister(Listener listener) {
        }
    }

    private final Environment environment;
    private final Function<Player, PlayerHandle> handles;

    /** The channel names last read successfully; see {@link #channelNames()}. */
    private volatile List<String> lastNames;

    ChatControlChannels(
            HeimdallLogger logger,
            Environment environment,
            Function<Player, PlayerHandle> handles,
            long retryNanos) {
        super(logger, PLUGIN_NAME, retryNanos);
        this.environment = environment;
        this.handles = handles;
    }

    /** The production wiring: Bukkit's plugin manager, owned by {@code heimdall}. */
    static ChatControlChannels create(
            HeimdallLogger logger, Plugin heimdall, Function<Player, PlayerHandle> handles) {
        return new ChatControlChannels(logger, new BukkitEnvironment(heimdall), handles, RETRY_NANOS);
    }

    @Override
    public String provider() {
        return installed() ? PROVIDER : null;
    }

    // ── PluginChannelHook ────────────────────────────────────────────────────

    @Override
    Plugin findPlugin() {
        return environment.findChatControl();
    }

    @Override
    ClassLoader loaderOf(Plugin found) {
        return environment.loaderOf(found);
    }

    @Override
    Api load(ClassLoader loader) throws ReflectiveOperationException {
        return Api.load(loader);
    }

    @Override
    Object newHandle(Api resolved) {
        return new Listener() {
        };
    }

    @Override
    void register(final Api resolved, Object handle) {
        environment.register(resolved.postEvent, (Listener) handle, new EventExecutor() {
            @Override
            public void execute(Listener ignored, Event event) {
                onChannelPost(resolved, event);
            }
        });
    }

    @Override
    void unregister(Api resolved, Object handle) {
        environment.unregister((Listener) handle);
    }

    @Override
    State hookedState(Api resolved) {
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

    // ── ChatChannels ─────────────────────────────────────────────────────────

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
        State state = currentState();
        if (state == null) {
            // Registration still in progress: keep the last good inventory, as for a one-call
            // failure below.
            List<String> previous = lastNames;
            return previous == null ? Collections.<String>emptyList() : previous;
        }
        if (state != State.ACTIVE) {
            return Collections.emptyList();
        }
        List<String> names = names(api);
        if (names != null) {
            lastNames = names;
            return names;
        }
        if (brokenReason() != null) {
            return Collections.emptyList();
        }
        List<String> previous = lastNames;
        return previous == null ? Collections.<String>emptyList() : previous;
    }

    @Override
    public Optional<Collection<PlayerHandle>> members(String channel) {
        if (channel == null || channel.isEmpty() || currentState() != State.ACTIVE) {
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

    // ── ChannelHook ──────────────────────────────────────────────────────────

    @Override
    public UntaggedRelay untaggedRelay(Player player) {
        State state = currentState();
        if (state == null) {
            // The hook is still registering after the bounded wait: drop this one line.
            return UntaggedRelay.TRANSIENT;
        }
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

    // ── The hook ─────────────────────────────────────────────────────────────

    /**
     * One {@code ChannelPostChatEvent}: read it, then relay with the channel attached.
     *
     * <p>Contained as {@link Throwable}: this runs inside ChatControl's delivery, and an escaped
     * error would land in its stack, not ours. A failure reading the event is a reflective one, so it
     * marks the integration broken (relay stops) rather than being retried per message.
     */
    private void onChannelPost(Api bound, Event event) {
        if (!bound.postEvent.isInstance(event) || !hasPipeline()) {
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
        relay(message);
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

        @Override
        public void unregister(Listener listener) {
            HandlerList.unregisterAll(listener);
        }
    }
}
