package com.heimdall.core.platform;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * A chat plugin's channels, as platform-free code is allowed to see them.
 *
 * <p>Today the only implementation that answers anything is ChatControl's, on the Bukkit family,
 * reached reflectively by {@code :platform-bukkit}. Everywhere else this is {@link #NONE}, which is
 * also what a Bukkit server without ChatControl gets. Departure D85.
 *
 * <h2>Why a relay needs to know</h2>
 *
 * <p>On a server running channels, the line a player types into {@code staff} is addressed to staff.
 * A Discord bridge that relayed every line it observed would publish it to whoever reads the mapped
 * Discord channel, and the inbound direction has the mirror problem: a Discord message meant for one
 * in-game channel must not be shown to everybody online. Both halves need the same three facts, and
 * this interface is those three facts and nothing else: whether channels are in play at all, what
 * they are called, and who is in one.
 *
 * <h2>Three states, because "broken" is not "none"</h2>
 *
 * <p>{@link State#BROKEN} exists so a ChatControl release that moved a class cannot silently turn a
 * channelled server back into one that relays everything. "We could not find out" fails closed, and
 * it is reported to the bot as its own state so the dashboard can say why the bridge went quiet.
 *
 * <p>Every method is safe to call from any thread, must not block, and must not throw: an
 * implementation that hits a reflective failure answers {@link State#BROKEN} instead.
 */
public interface ChatChannels {

    /** Whether channels are in play on this server, and whether we can see them. */
    enum State {

        /**
         * No channel plugin, or one that is installed with channels switched off. Chat is one public
         * room, exactly as if nothing were installed.
         */
        NONE("none"),

        /** A channel plugin is installed, its channels are on, and the hook into it is live. */
        ACTIVE("active"),

        /**
         * A channel plugin is installed but something this integration needs from it could not be
         * found or registered, so which channel a line belongs to is unknowable. Callers fail
         * closed: nothing is relayed in either direction that depends on the answer.
         */
        BROKEN("broken");

        private final String wireName;

        State(String wireName) {
            this.wireName = wireName;
        }

        /** The lower-case spelling the {@code bridge.channels} frame carries. A wire contract. */
        public String wireName() {
            return wireName;
        }
    }

    /** The current state. Cheap enough to call per message. */
    State state();

    /**
     * The channel names the chat plugin knows, in its own spelling.
     *
     * @return an unmodifiable list; empty unless {@link #state()} is {@link State#ACTIVE}
     */
    List<String> channelNames();

    /**
     * Everyone currently online who is in {@code channel}, in any mode the chat plugin has (reading
     * or writing).
     *
     * <p>A snapshot, in the same sense as {@link PlayerDirectory#onlinePlayers()}: a handle may name a
     * player who has left by the time it is used, and every handle tolerates that.
     *
     * @param channel the channel name; matched case-insensitively, since the name usually arrives
     *     from a dashboard selection rather than from the chat plugin itself
     * @return the members, possibly none; {@link Optional#empty()} when the channel is unknown or
     *     {@link #state()} is not {@link State#ACTIVE}, so "nobody is in it" and "there is no such
     *     channel" stay two different answers
     */
    Optional<Collection<PlayerHandle>> members(String channel);

    /** No channel plugin: one public room. What every platform without an integration returns. */
    ChatChannels NONE = new ChatChannels() {
        @Override
        public State state() {
            return State.NONE;
        }

        @Override
        public List<String> channelNames() {
            return Collections.emptyList();
        }

        @Override
        public Optional<Collection<PlayerHandle>> members(String channel) {
            return Optional.empty();
        }

        @Override
        public String toString() {
            return "ChatChannels.NONE";
        }
    };
}
