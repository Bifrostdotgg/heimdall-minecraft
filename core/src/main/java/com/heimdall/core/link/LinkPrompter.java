package com.heimdall.core.link;

import com.heimdall.core.http.BedrockIdentity;
import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.platform.LinkDialogs;
import com.heimdall.core.platform.PlatformFacade;
import com.heimdall.core.platform.PlayerHandle;
import com.heimdall.core.util.Strings;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;
import net.kyori.adventure.text.Component;

/**
 * Puts a pending link code in front of a player who is online here: a dialog where one can be
 * drawn, chat everywhere else, and never the same code twice in quick succession.
 *
 * <h2>Why a player needs this at all</h2>
 *
 * <p>Linking starts in Discord and ends with a six-digit code that is only ever shown in Minecraft.
 * On a whitelisted server the kick screen shows it. On a link-only server nothing kicks anybody, so
 * the code used to be visible only to a player who thought to type {@code /linkdiscord}. This is
 * the other way the code arrives: when the player joins (asked by
 * {@link com.heimdall.core.wiring.LinkPromptWiring}), and when the bot pushes it to a server the
 * player is already on. Departure D90.
 *
 * <h2>Two callers can race, so the second one is a no-op</h2>
 *
 * <p>The join asks the bot for a pending code and the bot can push the same one moments later, or
 * the other way round. Showing it twice would open a second dialog over the first, so the last code
 * shown to each player is remembered for {@link #REPEAT_WINDOW_MS} and a repeat inside that window
 * is reported as {@link Outcome#ALREADY_SHOWN} without showing anything. A <em>different</em> code is
 * always shown: a player who restarted linking in Discord needs the new one.
 *
 * <p>The window is deliberately short. It exists for the race, not to ration the prompt: a player
 * who dismissed the dialog and asks the bot to send it again a minute later gets it again.
 *
 * <h2>Who gets a dialog</h2>
 *
 * <p>A Bedrock player never does. Geyser has no faithful rendering of a Java dialog, and a player
 * on a phone is better served by a line of chat they can read than by a screen that may not draw.
 * Bedrock is recognised the two ways the rest of the plugin already uses: Floodgate's own answer
 * where Floodgate is on this server, and the synthetic UUID shape Floodgate gives every Bedrock
 * player (all-zero top half) where it is only on the proxy. Everyone else is offered to
 * {@link LinkDialogs}, which answers for the server and the client, and anything it declines or
 * fails at becomes chat.
 *
 * <p>Thread-safe. {@link #present} does not block: a dialog is handed to the player's own thread,
 * and chat is sent through {@link PlayerHandle#sendMessage}, which hops by itself.
 */
public final class LinkPrompter {

    /** How long a code shown to a player is not shown to them again. Covers the join/push race. */
    public static final long REPEAT_WINDOW_MS = 15_000L;

    /**
     * How many players' last codes are remembered.
     *
     * <p>Only the window above needs remembering, so the bound is about a burst of joins, not about
     * the player base: an evicted entry costs at worst one repeated prompt.
     */
    static final int REMEMBERED_PLAYERS = 1024;

    /** What {@link #present} did. */
    public enum Outcome {

        /** The prompt was handed to a dialog or sent in chat. */
        SHOWN,

        /** This code was shown to this player moments ago; nothing was sent. */
        ALREADY_SHOWN,

        /** The code has run out; nothing was sent, because a dead code only misleads. */
        EXPIRED
    }

    private final HeimdallLogger logger;
    private final PlatformFacade platform;
    private final LongSupplier clock;

    /** Last code per player, oldest first. Guarded by itself. */
    private final Map<UUID, Shown> recent = new LinkedHashMap<UUID, Shown>(64, 0.75f, false) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, Shown> eldest) {
            return size() > REMEMBERED_PLAYERS;
        }
    };

    public LinkPrompter(HeimdallLogger logger, PlatformFacade platform, LongSupplier clock) {
        if (logger == null || platform == null || clock == null) {
            throw new IllegalArgumentException("logger, platform and clock are required");
        }
        this.logger = logger;
        this.platform = platform;
        this.clock = clock;
    }

    /**
     * Shows the prompt to a player who is online on this server.
     *
     * <p>The code is recorded as shown <em>before</em> it is sent, so of two callers racing on the
     * same code exactly one sends it.
     */
    public Outcome present(final PlayerHandle player, final LinkCodePrompt prompt) {
        if (player == null || prompt == null || player.uuid() == null) {
            throw new IllegalArgumentException("a player and a prompt are required");
        }
        long now = clock.getAsLong();
        if (prompt.isExpired(now)) {
            logger.debug(() -> "not showing " + player.name() + " an expired link code");
            return Outcome.EXPIRED;
        }
        if (!claim(player.uuid(), prompt.code(), now)) {
            logger.debug(() -> "already showed " + player.name() + " this link code moments ago");
            return Outcome.ALREADY_SHOWN;
        }

        if (!isBedrock(player.uuid()) && offerDialog(player, prompt)) {
            logger.debug(() -> "showing " + player.name() + " their link code in a dialog");
            return Outcome.SHOWN;
        }
        sendChat(player, prompt);
        logger.debug(() -> "told " + player.name() + " their link code in chat");
        return Outcome.SHOWN;
    }

    /** Records the code as shown, unless the same code was shown inside the window. */
    private boolean claim(UUID uuid, String code, long now) {
        synchronized (recent) {
            Shown last = recent.get(uuid);
            if (last != null && last.code.equals(code)) {
                long since = now - last.atMs;
                // A clock that stepped backwards reads as "just now" rather than as a negative age
                // that would hold the window open until the clock caught up.
                if (since >= 0 && since < REPEAT_WINDOW_MS) {
                    return false;
                }
            }
            recent.remove(uuid);
            recent.put(uuid, new Shown(code, now));
            return true;
        }
    }

    /**
     * Hands the prompt to the platform's dialog, if it takes it.
     *
     * <p>A dialog implementation that throws is a bug in a reflective path on a server version
     * nobody tested, and the player is told in chat instead: the code reaching them matters more
     * than the shape it arrives in.
     */
    private boolean offerDialog(final PlayerHandle player, final LinkCodePrompt prompt) {
        try {
            return platform.linkDialogs().show(player, prompt, new Runnable() {
                @Override
                public void run() {
                    sendChat(player, prompt);
                }
            });
        } catch (Throwable broken) {
            logger.debug(() -> "the link dialog failed for " + player.name()
                    + "; sending the code in chat: " + broken);
            return false;
        }
    }

    private void sendChat(PlayerHandle player, LinkCodePrompt prompt) {
        for (Component line : prompt.chatLines(clock.getAsLong())) {
            player.sendMessage(line);
        }
    }

    /**
     * Whether this is a Bedrock player.
     *
     * <p>The UUID shape first, because it needs nothing installed here: Floodgate's synthetic UUIDs
     * have an all-zero most significant half, which no Mojang UUID has, and a backend behind a proxy
     * that runs Floodgate sees the same UUID without having Floodgate itself.
     */
    private boolean isBedrock(UUID uuid) {
        if (uuid.getMostSignificantBits() == 0L) {
            return true;
        }
        try {
            BedrockIdentity identity = platform.integrations().floodgate().resolve(uuid.toString());
            return identity != null && Strings.isNotBlank(identity.gamertag());
        } catch (RuntimeException unavailable) {
            // Documented never to throw. If it does, the player is treated as Java: the UUID check
            // above has already caught every Floodgate player it can, so this is a player Floodgate
            // could not answer for, and a dialog is the right guess for one of those.
            return false;
        }
    }

    /** One remembered prompt. */
    private static final class Shown {

        final String code;
        final long atMs;

        Shown(String code, long atMs) {
            this.code = code;
            this.atMs = atMs;
        }
    }
}
