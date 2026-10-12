package com.heimdall.module.punishments;

import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.platform.ConsoleBridge;
import com.heimdall.core.platform.UnknownCommandException;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;

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
 *   <li>Only a {@code manual} revoke (or one with no cause, from an older bot). An {@code expiry}
 *       is LiteBans' own business and has already happened there. An {@code override} is the
 *       bot retiring an older row because a newer punishment replaced it: when that newer one is
 *       a LiteBans ban, lifting "the ban on this player" would lift the new ban.
 *   <li>Bans, uuid-attached IP bans and mutes. A warn or a kick enforces nothing, and an IP ban
 *       keyed only by an address arrives as a digest, which names no LiteBans target.
 *   <li>Only when LiteBans still has one in force. Every server in a network gets the frame, so
 *       backends sharing one LiteBans database would otherwise each run an unban, and a ban
 *       issued from the dashboard in hook mode was never in LiteBans at all. A failed lookup
 *       dispatches anyway: an unban for a player who is not banned costs one console line, and
 *       a ban left in place is the bug this class exists to fix.
 * </ul>
 *
 * <h2>The command</h2>
 *
 * <p>{@code litebans:unban <uuid> -s} first. The namespaced label matters on Bukkit, where
 * Essentials or vanilla can own a bare {@code unban}. A proxy has no namespaced labels, so an
 * unknown-command failure falls back to the bare verb. {@code -s} because this plugin announces
 * the revoke itself, under the hidden and silent rules; an unconditional LiteBans broadcast
 * beside it would print the unban twice and announce a hidden one to everybody. No reason and no
 * {@code --sender}: both would be free text from a dashboard form spliced into a console command
 * line that LiteBans parses flags out of, so LiteBans records the console as the remover.
 */
final class LiteBansRevokeLift {

    /** The LiteBans command family a revoke maps onto. */
    enum Family {
        BAN("unban"),
        MUTE("unmute");

        final String verb;

        Family(String verb) {
            this.verb = verb;
        }
    }

    /** Whether LiteBans still enforces a punishment of this family on the player. */
    interface ActiveCheck {
        boolean isActive(Family family, UUID uuid) throws Exception;
    }

    /** One lift: what to run, for whom. */
    static final class Request {
        final Family family;
        final UUID uuid;

        Request(Family family, UUID uuid) {
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
    static Request requestFor(String type, String targetUuid, String revokeCause) {
        String cause = revokeCause == null ? "" : revokeCause.trim().toLowerCase(Locale.ROOT);
        if (!cause.isEmpty() && !"manual".equals(cause)) return null;
        Family family;
        if ("ban".equals(type) || "ipban".equals(type)) {
            family = Family.BAN;
        } else if ("mute".equals(type)) {
            family = Family.MUTE;
        } else {
            return null;
        }
        if (targetUuid == null) return null;
        String raw = targetUuid.trim();
        if (raw.length() != 36) return null;
        UUID uuid;
        try {
            uuid = UUID.fromString(raw);
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
        return new Request(family, uuid);
    }

    /**
     * Checks LiteBans on {@code io} and, when it still enforces the punishment, runs the unban.
     *
     * <p>Never throws and never blocks the caller, which is the tunnel's reading thread. The
     * LiteBans lookup is a database query, which LiteBans refuses on the Bukkit main thread; the
     * console bridge hops to whichever thread its platform needs.
     */
    static void lift(final Request request, final ActiveCheck check, Executor io,
            final ConsoleBridge console, final HeimdallLogger logger) {
        if (request == null || check == null) return;
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
                dispatch(request, true, console, logger);
            }
        });
    }

    private static void dispatch(final Request request, final boolean namespaced,
            final ConsoleBridge console, final HeimdallLogger logger) {
        final String command = request.command(namespaced);
        console.dispatchCommand(command).whenComplete(new BiConsumer<String, Throwable>() {
            @Override
            public void accept(String ack, Throwable failure) {
                if (failure == null) {
                    logger.info("Revoked in Heimdall, so ran `" + command + "` to lift it in LiteBans");
                    return;
                }
                Throwable cause = unwrap(failure);
                if (namespaced && cause instanceof UnknownCommandException) {
                    dispatch(request, false, console, logger);
                    return;
                }
                logger.warn("Revoked in Heimdall but LiteBans was not told: `" + command
                        + "` failed (" + cause + "). Lift it in LiteBans by hand.");
            }
        });
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
