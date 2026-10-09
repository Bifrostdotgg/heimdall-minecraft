package org.mineacademy.chatcontrol.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.bukkit.entity.Player;
import org.mineacademy.chatcontrol.settings.Settings;

/**
 * Stand-in for ChatControl's {@code Channel}, with the statics and instance methods Heimdall calls,
 * plus test steering ({@link #reset}, {@link #create}, {@link #member}, {@link #using}).
 *
 * <p>{@link #findChannel} is deliberately exact-match, so a test proves Heimdall resolves the
 * canonical spelling itself rather than relying on how ChatControl compares names.
 */
public final class Channel {

    /** Test steering: when set, {@link #getOnlinePlayers()} throws it, as ChatControl's can. */
    public static volatile RuntimeException failOnlinePlayers;

    /** Test steering: when set, {@link #isUsingChannels} throws it. */
    public static volatile RuntimeException failUsing;

    /** Test steering: when set, {@link #getChannelNames} throws it. */
    public static volatile RuntimeException failChannelNames;

    private static final List<Channel> LOADED = new CopyOnWriteArrayList<Channel>();
    private static final Set<UUID> USING = ConcurrentHashMap.newKeySet();

    private final String name;
    private final Map<Player, ChannelMode> members =
            Collections.synchronizedMap(new LinkedHashMap<Player, ChannelMode>());

    private Channel(String name) {
        this.name = name;
    }

    /** Test steering: forgets every channel and every channel user. */
    public static void reset() {
        LOADED.clear();
        USING.clear();
        failOnlinePlayers = null;
        failUsing = null;
        failChannelNames = null;
    }

    /** Test steering: loads a channel. */
    public static Channel create(String name) {
        Channel channel = new Channel(name);
        LOADED.add(channel);
        return channel;
    }

    /** Test steering: puts a player in this channel. */
    public Channel member(Player player, ChannelMode mode) {
        members.put(player, mode);
        return this;
    }

    /** Test steering: whether a player is "using channels". */
    public static void using(Player player, boolean value) {
        if (value) {
            USING.add(player.getUniqueId());
        } else {
            USING.remove(player.getUniqueId());
        }
    }

    public static List<String> getChannelNames() {
        RuntimeException failure = failChannelNames;
        if (failure != null) {
            throw failure;
        }
        List<String> names = new ArrayList<String>();
        for (Channel channel : LOADED) {
            names.add(channel.name);
        }
        return names;
    }

    public static Channel findChannel(String name) {
        for (Channel channel : LOADED) {
            if (channel.name.equals(name)) {
                return channel;
            }
        }
        return null;
    }

    /** Like the real one: dereferences ENABLED unguarded, so a null setting throws. */
    public static boolean isUsingChannels(Player player) {
        RuntimeException failure = failUsing;
        if (failure != null) {
            throw failure;
        }
        return Settings.Channels.ENABLED.booleanValue() && USING.contains(player.getUniqueId());
    }

    public String getName() {
        return name;
    }

    public Map<Player, ChannelMode> getOnlinePlayers() {
        RuntimeException failure = failOnlinePlayers;
        if (failure != null) {
            throw failure;
        }
        synchronized (members) {
            return new LinkedHashMap<Player, ChannelMode>(members);
        }
    }
}
