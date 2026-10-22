package com.heimdall.platform.bukkit;

import com.heimdall.core.pipeline.ChatPipeline;
import com.heimdall.core.platform.PlayerHandle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import org.bukkit.entity.Player;

/**
 * The chat plugin this server runs, whichever it is. Departure D92.
 *
 * <p>One of ChatControl and ZelChat is normally installed, or neither. This answers for the one that
 * is, and {@link State#NONE} when neither is (the first hook's answer, which is NONE without its
 * plugin).
 *
 * <h2>Both installed</h2>
 *
 * <p>Two chat plugins both routing chat is a server where nobody can say which audience a line is
 * for: ZelChat could deliver a line ChatControl put in {@code staff} as public chat, and the relay
 * would publish it. So it is {@link State#BROKEN} with a reason saying so, every legacy line is
 * dropped, and both hooks are vetoed from relaying anything. Fail closed, as everywhere else here.
 */
final class ChatChannelHooks implements ChannelHook {

    private final List<PluginChannelHook<?>> hooks;

    ChatChannelHooks(PluginChannelHook<?>... hooks) {
        if (hooks.length == 0) {
            throw new IllegalArgumentException("at least one hook");
        }
        this.hooks = Collections.unmodifiableList(new ArrayList<PluginChannelHook<?>>(
                Arrays.asList(hooks)));
        BooleanSupplier conflict = new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
                return conflicted();
            }
        };
        for (PluginChannelHook<?> hook : this.hooks) {
            hook.vetoRelayWhen(conflict);
        }
    }

    /**
     * The installed hook, the first one when none is, or {@code null} when more than one is
     * installed.
     */
    private ChannelHook selected() {
        ChannelHook found = null;
        for (ChannelHook hook : hooks) {
            if (hook.installed()) {
                if (found != null) {
                    return null;
                }
                found = hook;
            }
        }
        return found != null ? found : hooks.get(0);
    }

    /** Whether more than one chat plugin is installed. */
    boolean conflicted() {
        return selected() == null;
    }

    @Override
    public void attach(ChatPipeline chatPipeline) {
        for (ChannelHook hook : hooks) {
            hook.attach(chatPipeline);
        }
    }

    @Override
    public boolean installed() {
        for (ChannelHook hook : hooks) {
            if (hook.installed()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public State state() {
        ChannelHook hook = selected();
        return hook == null ? State.BROKEN : hook.state();
    }

    @Override
    public List<String> channelNames() {
        ChannelHook hook = selected();
        return hook == null ? Collections.<String>emptyList() : hook.channelNames();
    }

    @Override
    public Optional<Collection<PlayerHandle>> members(String channel) {
        ChannelHook hook = selected();
        return hook == null ? Optional.<Collection<PlayerHandle>>empty() : hook.members(channel);
    }

    @Override
    public String provider() {
        ChannelHook hook = selected();
        return hook == null ? null : hook.provider();
    }

    @Override
    public UntaggedRelay untaggedRelay(Player player) {
        ChannelHook hook = selected();
        return hook == null ? UntaggedRelay.BROKEN : hook.untaggedRelay(player);
    }

    @Override
    public String brokenReason() {
        ChannelHook hook = selected();
        if (hook == null) {
            return "more than one chat plugin is installed (" + names() + "), so which audience a "
                    + "line is for cannot be told; remove all but one";
        }
        return hook.brokenReason();
    }

    @Override
    public String pluginName() {
        ChannelHook hook = selected();
        return hook == null ? names() : hook.pluginName();
    }

    @Override
    public void close() {
        for (ChannelHook hook : hooks) {
            hook.close();
        }
    }

    private String names() {
        StringBuilder out = new StringBuilder();
        for (ChannelHook hook : hooks) {
            if (out.length() > 0) {
                out.append(" and ");
            }
            out.append(hook.pluginName());
        }
        return out.toString();
    }
}
