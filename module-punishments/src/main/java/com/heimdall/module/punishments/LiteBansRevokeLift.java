package com.heimdall.module.punishments;

import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.platform.ConsoleBridge;
import com.heimdall.core.platform.UnknownCommandException;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Lifts a ban or a mute in LiteBans when the bot revokes it, in hook mode.
 *
 * <h2>Why hook mode needs this</h2>
 *
 * <p>In hook mode LiteBans enforces and Heimdall keeps a record: {@link LiteBansEventBridge} posts
 * every LiteBans issue and revoke to the bot, and {@code onLogin} abstains. That bridge only ever
 * ran one way. A revoke that started on the bot side (a dashboard unban, {@code /mc-punish
 * unban}, an accepted appeal) marked the bot's row revoked and evicted this plugin's mirror, and
 * LiteBans kept the player out. The bot reported the ban as lifted, which it was not.
 *
 * <p>So on a {@code punish.revoke} frame the plugin now asks LiteBans to lift it too, by
 * running LiteBans' own {@code unban}/{@code unmute} as the console. LiteBans has no API for
 * removing a punishment; its documented route is the command.
 *
 * <h2>What is lifted, and what is left alone</h2>
 *
 * <ul>
 *   <li>Only a row LiteBans holds: one its bridge reported ({@code source: "hook"}) or one
 *       imported from it. LiteBans' command lifts "the ban on this player", not a row, so a revoke
 *       of a ban issued from the dashboard would otherwise lift whatever unrelated ban LiteBans has
 *       on the same player. A frame with no {@code source} (an older bot) lifts nothing.
 *   <li>A {@code command} row counts as LiteBans' too. Until the bot kept the bridge's
 *       {@code hook} source it stored every bridged row as {@code command}, and those rows are
 *       the bans in force that people appeal. In hook mode nothing else writes one: the native
 *       {@code /hd ban} refuses outside replace mode. What this costs is a row left over from a
 *       spell in replace mode, whose revoke would also lift a LiteBans ban on the same player.
 *   <li>Only a {@code manual} revoke. An {@code expiry} is LiteBans' own business and has already
 *       happened there. An {@code override} is the bot retiring an older row because a newer
 *       punishment replaced it: when that newer one is a LiteBans ban, lifting "the ban on this
 *       player" would lift the new ban.
 *   <li>Bans, uuid-attached IP bans and mutes. A warn or a kick enforces nothing, and an IP ban
 *       keyed only by an address arrives as a digest, which names no LiteBans target.
 *   <li>Only when LiteBans still has one in force, which skips the unban where LiteBans already
 *       lifted it. It is a check, not a lock: backends sharing one LiteBans database all get the
 *       frame at once and can all see the ban, and then each runs the unban. The extras cost a
 *       console line each. A failed lookup dispatches anyway, for the same reason.
 * </ul>
 *
 * <h2>The command</h2>
 *
 * <p>{@code litebans:unban <uuid> -s}. The namespaced label matters on Bukkit, where Essentials or
 * vanilla can own a bare {@code unban}. A proxy has no namespaced labels, so there, and only there,
 * an unknown-command failure falls back to the bare verb. {@code -s} because this plugin announces
 * the revoke itself, under the hidden and silent rules; an unconditional LiteBans broadcast beside
 * it would print the unban twice and announce a hidden one to everybody. No reason and no
 * {@code --sender}: both would be free text from a dashboard form spliced into a console command
 * line that LiteBans parses flags out of, so LiteBans records the console as the remover.
 *
 * <h2>The echo</h2>
 *
 * <p>Our unban fires LiteBans' {@code entryRemoved}, which the bridge would post back as a loose
 * type-and-uuid revoke. The bot has already revoked the row this lift is about, so the echo can
 * only reach others: a second row of that type on the same player, hidden ones included, which
 * the moderator did not ask to lift and may not be allowed to see. {@link #isOwnEcho} lets the
 * bridge drop it.
 */
final class LiteBansRevokeLift {

    /** How long a lift waits for its own echo. LiteBans removes and fires within a tick or two. */
    static final long ECHO_WINDOW_MS = 30_000L;

    /** The id {@code LiteBansImporter} gives a row: the LiteBans table and its id. */
    private static final Pattern LITEBANS_IMPORT_ID = Pattern.compile("^(ban|ipban|mute|warn|kick):\\d+$");

    /** Lifts dispatched and not yet echoed, by {@link #echoKey}, with when they were dispatched. */
    private static final Map<String, Long> AWAITING_ECHO = new ConcurrentHashMap<String, Long>();

    /** The clock the echo window is measured on. A field so a test can move it. */
    static volatile LongSupplier clock = new LongSupplier() {
        @Override
        public long getAsLong() {
            return System.currentTimeMillis();
        }
    };

    /** The LiteBans command family a revoke maps onto. */
    enum Family {
        BAN("unban"),
        MUTE("unmute");

        final String verb;

        Family(String verb) {
            this.verb = verb;
        }

        static Family of(String type) {
            if ("ban".equals(type) || "ipban".equals(type)) return BAN;
            if ("mute".equals(type)) return MUTE;
            return null;
        }
    }

    /** Whether LiteBans still enforces a punishment of this family on the player. */
    interface ActiveCheck {
        boolean isActive(Family family, UUID uuid) throws Exception;
    }

    /** One lift: what to run, for whom. */
    static final class Request {
        /** The frame's own type, {@code ban}, {@code ipban} or {@code mute}. */
        final String type;
        final Family family;
        final UUID uuid;

        Request(String type, Family family, UUID uuid) {
            this.type = type;
            this.family = family;
            this.uuid = uuid;
        }

        String command(boolean namespaced) {
            return (namespaced ? "litebans:" : "") + family.verb + " " + uuid + " -s";
        }
    }

    private LiteBansRevokeLift() {
    }

    /**
     * The lift a {@code punish.revoke} frame calls for, or {@code null} when it calls for none.
     *
     * <p>The uuid is parsed rather than passed through: it is spliced into a console command, and
     * {@link UUID#toString()} can only ever print hex and dashes.
     */
    static Request requestFor(String type, String targetUuid, String revokeCause, String source,
            String importProviderId) {
        if (!heldByLiteBans(source, importProviderId)) return null;
        String cause = revokeCause == null ? "" : revokeCause.trim().toLowerCase(Locale.ROOT);
        if (!"manual".equals(cause)) return null;
        Family family = Family.of(type);
        if (family == null) return null;
        UUID uuid = parseUuid(targetUuid);
        if (uuid == null) return null;
        return new Request(type, family, uuid);
    }

    /**
     * Whether the revoked row is one LiteBans holds. An import row's provider name cannot say:
     * every import is stamped "litebans", AdvancedBan and vanilla included. Its id can, because
     * only {@code LiteBansImporter} writes a bare {@code <table>:<id>}.
     */
    static boolean heldByLiteBans(String source, String importProviderId) {
        if ("hook".equals(source) || "command".equals(source)) return true;
        return "import".equals(source) && importProviderId != null
                && LITEBANS_IMPORT_ID.matcher(importProviderId).matches();
    }

    /**
     * Whether a LiteBans removal is the echo of a lift this class dispatched, consuming it if so.
     * Called by the bridge before it posts a revoke.
     *
     * <p>Matched on the exact type, not the family. One {@code unban} can remove a ban and an IP
     * ban on the same player, and only the one the bot revoked is already settled there. The other
     * removal is news to the bot, so it goes through: a revoked IP ban whose unban also took the
     * player's ordinary ban would otherwise leave that ban showing as in force.
     */
    static boolean isOwnEcho(String type, String targetUuid) {
        UUID uuid = parseUuid(targetUuid);
        if (Family.of(type) == null || uuid == null) return false;
        Long at = AWAITING_ECHO.remove(echoKey(type, uuid));
        return at != null && clock.getAsLong() - at.longValue() <= ECHO_WINDOW_MS;
    }

    /**
     * Checks LiteBans on {@code io} and, when it still enforces the punishment, runs the unban.
     *
     * <p>Never throws and never blocks the caller, which is the tunnel's reading thread. The
     * LiteBans lookup is a database query, which LiteBans refuses on the Bukkit main thread; the
     * console bridge hops to whichever thread its platform needs.
     *
     * @param onProxy whether this server is a proxy, the one place a bare {@code unban} is
     *     LiteBans' own
     */
    static void lift(final Request request, final ActiveCheck check, Executor io,
            final ConsoleBridge console, final HeimdallLogger logger, final boolean onProxy) {
        if (request == null || check == null) return;
        try {
            io.execute(new Runnable() {
                @Override
                public void run() {
                    boolean active;
                    try {
                        active = check.isActive(request.family, request.uuid);
                    } catch (Throwable failed) {
                        logger.debug(() -> "LiteBans lookup failed for " + request.uuid
                                + ", lifting anyway: " + failed);
                        active = true;
                    }
                    if (!active) {
                        logger.debug(() -> "LiteBans has no " + request.family.name().toLowerCase(
                                Locale.ROOT) + " in force on " + request.uuid + "; nothing to lift");
                        return;
                    }
                    expectEcho(request);
                    dispatch(request, true, onProxy, console, logger);
                }
            });
        } catch (RuntimeException rejected) {
            // The pool is gone, which means the plugin is shutting down.
            logger.debug(() -> "LiteBans lift for " + request.uuid + " not scheduled: " + rejected);
        }
    }

    private static void dispatch(final Request request, final boolean namespaced,
            final boolean onProxy, final ConsoleBridge console, final HeimdallLogger logger) {
        final String command = request.command(namespaced);
        console.dispatchCommand(command).whenComplete(new BiConsumer<String, Throwable>() {
            @Override
            public void accept(String ack, Throwable failure) {
                if (failure == null) {
                    logger.info("Revoked in Heimdall, so ran `" + command + "` to lift it in LiteBans");
                    return;
                }
                Throwable cause = unwrap(failure);
                if (namespaced && onProxy && cause instanceof UnknownCommandException) {
                    dispatch(request, false, onProxy, console, logger);
                    return;
                }
                AWAITING_ECHO.remove(echoKey(request.type, request.uuid));
                logger.warn("Revoked in Heimdall but LiteBans was not told: `" + command
                        + "` failed (" + cause + "). Lift it in LiteBans by hand.");
            }
        });
    }

    private static void expectEcho(Request request) {
        long now = clock.getAsLong();
        // Stale entries are lifts LiteBans never echoed; drop them so the map stays bounded.
        Iterator<Map.Entry<String, Long>> stale = AWAITING_ECHO.entrySet().iterator();
        while (stale.hasNext()) {
            if (now - stale.next().getValue().longValue() > ECHO_WINDOW_MS) stale.remove();
        }
        AWAITING_ECHO.put(echoKey(request.type, request.uuid), Long.valueOf(now));
    }

    private static String echoKey(String type, UUID uuid) {
        return type + ":" + uuid;
    }

    private static UUID parseUuid(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.length() != 36) return null;
        try {
            return UUID.fromString(trimmed);
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    /** Forgets every lift awaiting its echo and resets the clock. Tests only. */
    static void clearForTest() {
        AWAITING_ECHO.clear();
        clock = new LongSupplier() {
            @Override
            public long getAsLong() {
                return System.currentTimeMillis();
            }
        };
    }

    /** How many lifts are awaiting their echo. Tests only. */
    static int awaitingEchoForTest() {
        return AWAITING_ECHO.size();
    }
}
