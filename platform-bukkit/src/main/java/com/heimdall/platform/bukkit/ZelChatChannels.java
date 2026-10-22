package com.heimdall.platform.bukkit;

import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.pipeline.ChatMessage;
import com.heimdall.core.platform.PlayerHandle;
import com.heimdall.platform.common.ServerAdventure;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * ZelChat's two chat rooms, public and staff, as channels, and the hook that relays a line with its
 * room attached. Departure D92.
 *
 * <p>Finding the plugin, resolving it, registering the hook once, reloads and failure handling are
 * {@link PluginChannelHook}'s, shared with ChatControl. The names below were taken from
 * {@code it.pino.zelchat:zelchat-api:2.0.0-pre-31}, and this module's tests resolve every one of them
 * against that jar, so a renamed method fails the build rather than a server.
 *
 * <h2>Two fixed channels</h2>
 *
 * <p>ZelChat has no named channels. It has public chat, a staff chat ({@code /staffchat}), and two
 * audiences it never shows the public: Towny town chat and private messages. Its
 * {@code AsyncPostMessageDispatchEvent} says which with a {@code ChannelType}:
 *
 * <ul>
 *   <li>{@code EVERYONE} is relayed as {@value #GLOBAL};
 *   <li>{@code STAFF} is relayed as {@value #STAFF};
 *   <li>{@code CUSTOM} (a town chat, or anything an addon adds) and {@code PRIVATE} are never
 *       relayed. There is no dashboard mapping that could be meant for them.
 * </ul>
 *
 * <p>So this always reports both names, whatever is configured, and the dashboard maps them like any
 * other channel. Inbound, {@value #GLOBAL} reaches everyone online and {@value #STAFF} reaches the
 * players holding {@value #STAFF_PERMISSION}, the permission ZelChat's own staff chat checks: its API
 * has no "who is in staff chat" query, and no way to post into staff chat that would not fire this
 * event again and loop the line back to Discord.
 *
 * <h2>Why every ZelChat line comes from this hook</h2>
 *
 * <p>While the hook is live, the legacy chat listener relays nothing ({@link UntaggedRelay#SKIP}),
 * public chat included. ZelChat's API does not say whether it cancels the Bukkit chat event or which
 * audience a line in it is for, so relaying from there would mean guessing; the post-dispatch event
 * is the one place that names the room after ZelChat's filters have run.
 *
 * <h2>Proxies</h2>
 *
 * <p>A line is relayed only when its sender is online on this server. On a network every backend
 * relays its own chat, so a line another server delivered here must not be relayed again.
 *
 * <h2>Why reflection</h2>
 *
 * <p>Besides the bytecode version ({@link PluginChannelHook}), the event's message is the server's
 * own Adventure {@code Component}, while Heimdall relocates its Adventure into
 * {@code com.heimdall.libs.kyori}. A compiled call would look for the relocated type and fail with
 * {@code NoSuchMethodError} (see {@link BukkitChatListener}), so the component is turned into text
 * through the server's plain-text serializer ({@link ServerAdventure#plainText}).
 */
final class ZelChatChannels extends PluginChannelHook<ZelChatChannels.Api> {

    /** The name ZelChat registers under. */
    static final String PLUGIN_NAME = "ZelChat";

    /** What the {@code bridge.channels} frame calls this plugin. A wire contract. */
    static final String PROVIDER = "zelchat";

    /** Public chat. A wire contract: dashboard selections are stored by this name. */
    static final String GLOBAL = "global";

    /** Staff chat. A wire contract, like {@link #GLOBAL}. */
    static final String STAFF = "staff";

    /** The permission ZelChat's staff chat is gated on, and so who reads it. */
    static final String STAFF_PERMISSION = "zelchat.command.staffchat";

    private static final List<String> NAMES =
            Collections.unmodifiableList(Arrays.asList(GLOBAL, STAFF));

    static final String API_CLASS = "it.pino.zelchat.api.ZelChatAPI";
    static final String REGISTRY_CLASS = "it.pino.zelchat.api.event.EventRegistry";
    static final String LISTENER_CLASS = "it.pino.zelchat.api.event.EventListener";
    static final String POST_EVENT_CLASS =
            "it.pino.zelchat.api.event.builtin.AsyncPostMessageDispatchEvent";
    static final String CHANNEL_TYPE_CLASS = "it.pino.zelchat.api.message.channel.ChannelType";
    static final String CHAT_PLAYER_CLASS = "it.pino.zelchat.api.player.ChatPlayer";

    /** What this class needs from the server, as a seam for the tests. */
    interface Environment {

        /** The ZelChat plugin if it is loaded (enabled or not), else {@code null}. */
        Plugin findZelChat();

        /** The class loader ZelChat's own classes live in. */
        ClassLoader loaderOf(Plugin zelChat);

        /** Everyone online on this server. */
        Collection<? extends Player> onlinePlayers();
    }

    private final Environment environment;
    private final Function<Player, PlayerHandle> handles;

    ZelChatChannels(
            HeimdallLogger logger,
            Environment environment,
            Function<Player, PlayerHandle> handles,
            long retryNanos) {
        super(logger, PLUGIN_NAME, retryNanos);
        this.environment = environment;
        this.handles = handles;
    }

    /** The production wiring. */
    static ZelChatChannels create(HeimdallLogger logger, Function<Player, PlayerHandle> handles) {
        return new ZelChatChannels(logger, new BukkitEnvironment(), handles, RETRY_NANOS);
    }

    @Override
    public String provider() {
        return installed() ? PROVIDER : null;
    }

    // ── PluginChannelHook ────────────────────────────────────────────────────

    @Override
    Plugin findPlugin() {
        return environment.findZelChat();
    }

    @Override
    ClassLoader loaderOf(Plugin found) {
        return environment.loaderOf(found);
    }

    @Override
    Api load(ClassLoader loader) throws ReflectiveOperationException {
        return Api.load(loader);
    }

    /**
     * ZelChat publishes its API from its own {@code onEnable}, which Bukkit runs after marking the
     * plugin enabled. {@code ZelChatAPI.get()} throws until then, and that is "not yet", not "moved".
     */
    @Override
    boolean ready(Api resolved) throws ReflectiveOperationException {
        return resolved.get.invoke(null) != null;
    }

    @Override
    Object register(final Api resolved) throws ReflectiveOperationException {
        Object zelChat = resolved.get.invoke(null);
        Object registry = resolved.eventRegistry.invoke(zelChat);
        Object listener = Proxy.newProxyInstance(resolved.loader,
                new Class<?>[] {resolved.listenerType}, new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        if (method.getDeclaringClass() == Object.class) {
                            return objectMethod(proxy, method, args);
                        }
                        if (args != null && args.length == 1) {
                            onPost(resolved, args[0]);
                        }
                        return null;
                    }
                });
        resolved.register.invoke(registry, resolved.postEvent, listener);
        return new Registration(registry, listener);
    }

    @Override
    void unregister(Api resolved, Object handle) throws ReflectiveOperationException {
        Registration registration = (Registration) handle;
        resolved.unregister.invoke(registration.registry, resolved.postEvent, registration.listener);
    }

    /** ZelChat has no "channels off" switch: once hooked, both rooms exist. */
    @Override
    State hookedState(Api resolved) {
        return State.ACTIVE;
    }

    // ── ChatChannels ─────────────────────────────────────────────────────────

    @Override
    public List<String> channelNames() {
        return currentState() == State.ACTIVE ? NAMES : Collections.<String>emptyList();
    }

    @Override
    public Optional<Collection<PlayerHandle>> members(String channel) {
        if (channel == null || currentState() != State.ACTIVE) {
            return Optional.empty();
        }
        // Matched case-insensitively: the name usually arrives from a dashboard selection.
        boolean staff;
        if (GLOBAL.equalsIgnoreCase(channel)) {
            staff = false;
        } else if (STAFF.equalsIgnoreCase(channel)) {
            staff = true;
        } else {
            return Optional.empty();
        }
        Collection<? extends Player> online;
        try {
            online = environment.onlinePlayers();
        } catch (Throwable raced) {
            return Optional.empty();
        }
        List<PlayerHandle> members = new ArrayList<PlayerHandle>();
        for (Player player : online) {
            try {
                if (!staff || player.hasPermission(STAFF_PERMISSION)) {
                    members.add(handles.apply(player));
                }
            } catch (RuntimeException gone) {
                // A player who left mid-iteration. Staff chat stays closed to them either way.
            }
        }
        return Optional.<Collection<PlayerHandle>>of(Collections.unmodifiableList(members));
    }

    // ── ChannelHook ──────────────────────────────────────────────────────────

    @Override
    public UntaggedRelay untaggedRelay(Player player) {
        State state = currentState();
        if (state == null) {
            return UntaggedRelay.TRANSIENT;
        }
        switch (state) {
            case ACTIVE:
                // Every ZelChat line, public included, is relayed from the hook.
                return UntaggedRelay.SKIP;
            case NONE:
                // Switched off, ZelChat routes nothing and chat is one room. Running but not hooked
                // yet (its API is published from its own onEnable), it may already be turning a
                // toggled player's line into staff chat, so that line is dropped, not guessed at.
                return running() ? UntaggedRelay.TRANSIENT : UntaggedRelay.RELAY;
            default:
                return UntaggedRelay.BROKEN;
        }
    }

    // ── The hook ─────────────────────────────────────────────────────────────

    /**
     * One {@code AsyncPostMessageDispatchEvent}: read it, then relay with the room attached.
     *
     * <p>Contained as {@link Throwable}: this runs inside ZelChat's dispatch, and an escaped error
     * would land in its stack, not ours. A failure reading the event is a reflective one, so it marks
     * the integration broken (relay stops) rather than being retried per message.
     */
    private void onPost(Api bound, Object event) {
        if (!bound.postEvent.isInstance(event) || !hasPipeline()) {
            return;
        }
        ChatMessage message;
        try {
            String channel = channelFor(bound.eventChannelType.invoke(event));
            if (channel == null) {
                return;
            }
            Object sender = bound.eventSender.invoke(event);
            Object online = sender == null ? null : bound.senderPlayer.invoke(sender);
            if (!(online instanceof Player)) {
                return;
            }
            Player player = (Player) online;
            if (!player.isOnline()) {
                // Delivered here, typed somewhere else: that server relays its own chat.
                return;
            }
            String text = ServerAdventure.plainText(bound.eventMessage.invoke(event));
            message = ChatMessage.inChannel(player.getUniqueId(), player.getName(), text, channel);
        } catch (Throwable failed) {
            fail("reading a ZelChat message", failed);
            return;
        }
        relay(message);
    }

    /** {@code EVERYONE} and {@code STAFF} by name; everything else is a room nobody mapped. */
    static String channelFor(Object channelType) {
        if (!(channelType instanceof Enum)) {
            return null;
        }
        String name = ((Enum<?>) channelType).name();
        if ("EVERYONE".equals(name)) {
            return GLOBAL;
        }
        if ("STAFF".equals(name)) {
            return STAFF;
        }
        return null;
    }

    private static Object objectMethod(Object proxy, Method method, Object[] args) {
        String name = method.getName();
        if ("equals".equals(name)) {
            return args != null && args.length == 1 && proxy == args[0];
        }
        if ("hashCode".equals(name)) {
            return System.identityHashCode(proxy);
        }
        if ("toString".equals(name)) {
            return "Heimdall ZelChat relay";
        }
        return null;
    }

    // ── Reflective handles ───────────────────────────────────────────────────

    /** The registry and listener the hook was registered with. */
    private static final class Registration {
        final Object registry;
        final Object listener;

        Registration(Object registry, Object listener) {
            this.registry = registry;
            this.listener = listener;
        }
    }

    /** Every class and method this integration touches, resolved once. */
    static final class Api {

        final ClassLoader loader;
        final Method get;
        final Method eventRegistry;
        final Method register;
        final Method unregister;
        final Class<?> listenerType;
        final Class<?> postEvent;
        final Method eventMessage;
        final Method eventChannelType;
        final Method eventSender;
        final Method senderPlayer;

        private Api(ClassLoader loader) throws ReflectiveOperationException {
            this.loader = loader;
            Class<?> api = Class.forName(API_CLASS, true, loader);
            this.get = api.getMethod("get");
            this.eventRegistry = api.getMethod("getEventRegistry");

            Class<?> registry = Class.forName(REGISTRY_CLASS, true, loader);
            this.listenerType = Class.forName(LISTENER_CLASS, true, loader);
            if (!listenerType.isInterface()) {
                throw new ClassCastException(LISTENER_CLASS + " is not an interface");
            }
            this.register = registry.getMethod("register", Class.class, listenerType);
            this.unregister = registry.getMethod("unregister", Class.class, listenerType);

            this.postEvent = Class.forName(POST_EVENT_CLASS, true, loader);
            this.eventMessage = postEvent.getMethod("getMessage");
            this.eventChannelType = postEvent.getMethod("getChannelType");
            this.eventSender = postEvent.getMethod("getSender");

            Class<?> channelType = Class.forName(CHANNEL_TYPE_CLASS, true, loader);
            requireConstants(channelType, "EVERYONE", "STAFF");

            Class<?> chatPlayer = Class.forName(CHAT_PLAYER_CLASS, true, loader);
            this.senderPlayer = chatPlayer.getMethod("getBukkitPlayer");
        }

        /** The rooms this maps by name must still exist, or every line would silently drop. */
        private static void requireConstants(Class<?> type, String... names)
                throws NoSuchFieldException {
            Object[] constants = type.getEnumConstants();
            if (constants == null) {
                throw new NoSuchFieldException(type.getName() + " is not an enum");
            }
            for (String name : names) {
                boolean found = false;
                for (Object constant : constants) {
                    if (name.equals(((Enum<?>) constant).name())) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    throw new NoSuchFieldException(type.getName() + "." + name);
                }
            }
        }

        static Api load(ClassLoader loader) throws ReflectiveOperationException {
            if (loader == null) {
                throw new ClassNotFoundException("ZelChat has no class loader");
            }
            return new Api(loader);
        }
    }

    /** The real server. */
    private static final class BukkitEnvironment implements Environment {

        @Override
        public Plugin findZelChat() {
            return Bukkit.getPluginManager().getPlugin(PLUGIN_NAME);
        }

        @Override
        public ClassLoader loaderOf(Plugin zelChat) {
            return zelChat.getClass().getClassLoader();
        }

        @Override
        public Collection<? extends Player> onlinePlayers() {
            return Bukkit.getOnlinePlayers();
        }
    }
}
