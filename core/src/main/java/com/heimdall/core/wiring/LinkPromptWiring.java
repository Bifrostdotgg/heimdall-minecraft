package com.heimdall.core.wiring;

import com.heimdall.core.http.HeimdallApi;
import com.heimdall.core.http.model.PendingLinkCode;
import com.heimdall.core.json.Envelope;
import com.heimdall.core.json.Payload;
import com.heimdall.core.link.LinkCodePrompt;
import com.heimdall.core.link.LinkPrompter;
import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.platform.PlatformFacade;
import com.heimdall.core.platform.PlayerHandle;
import com.heimdall.core.session.PlayerSessionEvents;
import com.heimdall.core.session.PlayerSessionListener;
import com.heimdall.core.tunnel.TunnelBus;
import com.heimdall.core.tunnel.TunnelMessageHandler;
import com.heimdall.core.util.Registration;
import com.heimdall.core.util.Strings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * The two ways a pending link code reaches a player without them typing {@code /linkdiscord}: on
 * join, and when the bot pushes it to a server they are already on.
 *
 * <p>Both end in {@link LinkPrompter}, which owns the dialog-or-chat decision and the rule that the
 * same code is not shown twice in quick succession, so the two routes racing each other is safe.
 * Departure D90.
 *
 * <h2>Why this is core and not the whitelist module</h2>
 *
 * <p>The server that needs it most is the one with the whitelist module switched off. A whitelisted
 * server already shows the code on its kick screen; a link-only server kicks nobody, so without this
 * a player who started linking in Discord would join and see nothing. Linking is a property of
 * having a bot (the same category as {@code RemoteRequestWiring} and {@code /linkdiscord},
 * departure D71), so this is installed from {@link HeimdallRuntime#start()} beside them, and the
 * capability that tells the bot it may push is declared by core rather than by any module.
 *
 * <h2>On join</h2>
 *
 * <p>Asks {@code pending-link-code}, which reports and never mints, so asking on every join costs
 * the bot a read and creates nothing. The ask waits {@link #JOIN_DELAY_MS} first, so a dialog does
 * not open while the client is still on its loading screen and a chat line is not buried under the
 * server's own join messages. It never blocks the join and can never fail one: it runs after the
 * join has happened, off the event thread, and every failure ends in a debug line. A server whose
 * API is not usable yet (not set up, guild still resolving) does not ask at all.
 *
 * <h2>On push: {@code show_link_code}, always answered</h2>
 *
 * <p>The bot's request carries {@code uuid}, {@code username}, {@code code} and {@code expiresAt},
 * and waits for {@code show_link_code_result}: {@code {shown: true}} when the player is online here
 * and the code was presented (or had been, moments before), otherwise {@code {shown: false,
 * reason}}. Every path replies, for the reason {@code RemoteRequestWiring} gives at length: a
 * request left unanswered looks to the bot exactly like a server that is down. The reasons are
 * short machine words ({@code not_online}, {@code invalid_uuid}, {@code missing_code},
 * {@code expired}, {@code error}) because the bot branches on them rather than showing them.
 *
 * <p>Subscribed on {@code heimdall-io}, never the socket's reading thread (departure D27).
 */
public final class LinkPromptWiring {

    /** The bot's request to show a player their code. */
    static final String SHOW_LINK_CODE = "show_link_code";

    /** The reply to {@link #SHOW_LINK_CODE}. */
    static final String SHOW_LINK_CODE_RESULT = "show_link_code_result";

    /**
     * How long after a join the pending code is asked for.
     *
     * <p>Two seconds: long enough for the client to be past its terrain screen on an ordinary
     * server, short enough that the player is still looking for what to do next.
     */
    static final long JOIN_DELAY_MS = 2_000L;

    private LinkPromptWiring() {
    }

    /**
     * Installs the join listener and the {@code show_link_code} handler.
     *
     * <p>Called once from {@link HeimdallRuntime#start()}, before the not-configured return, for the
     * same reason as {@code RemoteRequestWiring.install}: the subscription lives on the client, so it
     * is already in place when {@code /hd setup} brings the tunnel up without a restart, and the
     * join listener checks {@link HeimdallApi#isUsable()} per join rather than once.
     *
     * @return one handle that removes both, closed with the runtime
     */
    public static Registration install(
            HeimdallLogger logger,
            PlatformFacade platform,
            HeimdallApi api,
            TunnelBus tunnel,
            PlayerSessionEvents sessions,
            Executor io,
            ScheduledExecutorService scheduler) {
        LinkPrompter prompter = new LinkPrompter(logger, platform, System::currentTimeMillis);
        return install(logger, platform, api, tunnel, sessions, io, scheduler, prompter,
                JOIN_DELAY_MS);
    }

    /**
     * The same, with the prompter and the join delay supplied.
     *
     * <p>So a test can share one prompter between the two routes and prove the de-duplication, and
     * can run the join check without waiting two seconds for it. Production passes
     * {@link #JOIN_DELAY_MS}.
     */
    static Registration install(
            HeimdallLogger logger,
            PlatformFacade platform,
            HeimdallApi api,
            TunnelBus tunnel,
            PlayerSessionEvents sessions,
            Executor io,
            ScheduledExecutorService scheduler,
            LinkPrompter prompter,
            long joinDelayMs) {
        List<Registration> handles = new ArrayList<Registration>(2);
        handles.add(sessions.onJoin(
                new JoinPrompt(logger, platform, api, io, scheduler, prompter, joinDelayMs)));
        handles.add(tunnel.subscribe(
                SHOW_LINK_CODE, new ShowLinkCodeHandler(logger, platform, tunnel, prompter), io));
        return combine(handles);
    }

    /** One handle that closes several, in reverse. */
    private static Registration combine(final List<Registration> handles) {
        return Registration.once(new Runnable() {
            @Override
            public void run() {
                Collections.reverse(handles);
                for (Registration handle : handles) {
                    handle.close();
                }
            }
        });
    }

    /**
     * Asks the bot whether a joining player has a code waiting, and shows it if so.
     *
     * <p>The player is looked up again once the answer arrives, rather than trusting the handle the
     * join carried: a player who left during the delay or the request is simply not shown anything.
     */
    static final class JoinPrompt implements PlayerSessionListener {

        private final HeimdallLogger logger;
        private final PlatformFacade platform;
        private final HeimdallApi api;
        private final Executor io;
        private final ScheduledExecutorService scheduler;
        private final LinkPrompter prompter;
        private final long delayMs;

        JoinPrompt(
                HeimdallLogger logger,
                PlatformFacade platform,
                HeimdallApi api,
                Executor io,
                ScheduledExecutorService scheduler,
                LinkPrompter prompter,
                long delayMs) {
            this.logger = logger;
            this.platform = platform;
            this.api = api;
            this.io = io;
            this.scheduler = scheduler;
            this.prompter = prompter;
            this.delayMs = delayMs;
        }

        @Override
        public void onPlayerSession(PlayerHandle player, long timestampMs) {
            if (player == null || player.uuid() == null || Strings.isBlank(player.name())) {
                return;
            }
            if (!api.isUsable()) {
                // Not set up, or the guild is still resolving. Not worth a line per join: the
                // status command already says which.
                return;
            }
            final UUID uuid = player.uuid();
            final String name = player.name();
            if (delayMs <= 0) {
                ask(uuid, name);
                return;
            }
            try {
                scheduler.schedule(new Runnable() {
                    @Override
                    public void run() {
                        ask(uuid, name);
                    }
                }, delayMs, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException shuttingDown) {
                logger.debug("not asking for a pending link code: the scheduler is shutting down");
            }
        }

        private void ask(final UUID uuid, final String name) {
            CompletableFuture<PendingLinkCode> pending;
            try {
                pending = api.pendingLinkCode(name, uuid.toString());
            } catch (RuntimeException refused) {
                logger.debug(() -> "could not ask for " + name + "'s pending link code: " + refused);
                return;
            }
            pending.whenCompleteAsync(new BiConsumer<PendingLinkCode, Throwable>() {
                @Override
                public void accept(PendingLinkCode answer, Throwable failure) {
                    if (failure != null) {
                        // Nothing pending, as far as this join is concerned. A failure here costs
                        // the player nothing they had before: /linkdiscord still works.
                        logger.debug(() -> "no pending link code for " + name + ": " + failure);
                        return;
                    }
                    if (answer == null || !answer.pending()) {
                        return;
                    }
                    show(uuid, name, answer);
                }
            }, io);
        }

        private void show(UUID uuid, final String name, PendingLinkCode answer) {
            try {
                Optional<PlayerHandle> online = platform.players().byUuid(uuid);
                if (!online.isPresent()) {
                    logger.debug(() -> name + " left before their link code could be shown");
                    return;
                }
                prompter.present(online.get(), LinkCodePrompt.of(answer.code(), answer.expiresAt()));
            } catch (Throwable broken) {
                // Contained: this runs on heimdall-io for somebody else's join, and nothing about a
                // prompt is worth an exception escaping into the pool.
                logger.warn("could not show " + name + " their pending link code: " + broken);
            }
        }
    }

    /**
     * Answers the bot's {@code show_link_code}.
     *
     * <p>The player must be online <em>here</em>. The bot may ask several servers on a network, and
     * an honest {@code not_online} from each server the player is not on is how it learns which one
     * took the prompt.
     */
    static final class ShowLinkCodeHandler implements TunnelMessageHandler {

        private final HeimdallLogger logger;
        private final PlatformFacade platform;
        private final TunnelBus tunnel;
        private final LinkPrompter prompter;

        ShowLinkCodeHandler(
                HeimdallLogger logger, PlatformFacade platform, TunnelBus tunnel,
                LinkPrompter prompter) {
            this.logger = logger;
            this.platform = platform;
            this.tunnel = tunnel;
            this.prompter = prompter;
        }

        @Override
        public void onMessage(Envelope envelope) {
            String id = envelope.id();
            Payload answer;
            try {
                answer = decide(envelope.payload());
            } catch (Throwable broken) {
                // A platform whose roster read threw, or a dialog path that broke in a way the
                // prompter did not contain. Still answered: a reply the bot can act on beats a
                // timeout it cannot explain.
                logger.warn("could not show a pushed link code: " + broken);
                answer = notShown("error");
            }
            try {
                tunnel.reply(id, SHOW_LINK_CODE_RESULT, answer);
            } catch (RuntimeException failed) {
                // The tunnel dropped between the frame arriving and its answer going out. The bot
                // times out, which is the honest outcome of a dead link.
                logger.debug(() -> "could not reply '" + SHOW_LINK_CODE_RESULT + "' to request "
                        + id + ": " + failed);
            }
        }

        private Payload decide(Payload request) {
            final String raw = Strings.trimToEmpty(request.string("uuid", ""));
            UUID uuid;
            try {
                if (raw.isEmpty()) {
                    return notShown("invalid_uuid");
                }
                uuid = UUID.fromString(raw);
            } catch (IllegalArgumentException notAUuid) {
                logger.debug(() -> "show_link_code for an unparseable uuid '" + raw + "'");
                return notShown("invalid_uuid");
            }
            String code = Strings.trimToEmpty(request.string("code", ""));
            if (code.isEmpty()) {
                return notShown("missing_code");
            }
            LinkCodePrompt prompt = LinkCodePrompt.of(code, request.string("expiresAt", ""));

            Optional<PlayerHandle> online = platform.players().byUuid(uuid);
            if (!online.isPresent()) {
                return notShown("not_online");
            }
            switch (prompter.present(online.get(), prompt)) {
                case EXPIRED:
                    return notShown("expired");
                case ALREADY_SHOWN:
                    // Shown moments ago by the join check, which is the race this is meant to
                    // survive. The player has the code in front of them, so the honest answer is
                    // yes; the extra key only says why nothing new was sent.
                    return Payload.builder().put("shown", true).put("alreadyShown", true).build();
                case SHOWN:
                default:
                    return Payload.builder().put("shown", true).build();
            }
        }

    }

    /** The {@code shown: false} answer, with the reason the bot branches on. */
    static Payload notShown(String reason) {
        return Payload.builder().put("shown", false).put("reason", reason).build();
    }
}
