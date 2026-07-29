package com.heimdall.core.wiring;

import com.heimdall.core.json.Envelope;
import com.heimdall.core.json.Payload;
import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.platform.PlatformFacade;
import com.heimdall.core.platform.PlayerHandle;
import com.heimdall.core.platform.UnknownCommandException;
import com.heimdall.core.tunnel.TunnelBus;
import com.heimdall.core.tunnel.TunnelMessageHandler;
import com.heimdall.core.util.Registration;
import com.heimdall.core.util.Strings;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * The three questions the dashboard asks a server directly, and the promise that all three are
 * answered.
 *
 * <p>{@code get_players} backs the Online Players panel, {@code run_command} backs the console
 * command box, and {@code probe_player} backs the mod-probe button on a player's page. Each arrives
 * as a correlated request over the tunnel and each has somebody's browser waiting on the reply — the
 * bot passes whatever comes back straight through, so the payload shapes below <strong>are</strong>
 * the wire contract, transcribed from v2's two entry points rather than invented here.
 *
 * <h2>A request is never left unanswered</h2>
 *
 * <p>This is the whole reason the class exists as a class rather than three lambdas. v3 shipped with
 * the plumbing for all three — {@code TunnelBus.reply}, the correlation map, the dispatcher — and no
 * handler subscribed to any of them, so every one of these frames fell through to the "no handler for
 * tunnel message" debug line and the dashboard sat on a spinner until its own timeout fired. The
 * Online Players panel 504ed after ten seconds on a real proxy; that is what a missing subscription
 * looks like from the outside, and it looks identical to a server that is down.
 *
 * <p>So every path here ends in a {@code reply}: the empty roster, the malformed uuid, the command
 * the server does not have, the probe on a platform with no Trace. An error payload the dashboard can
 * render beats a timeout it cannot explain — which is exactly the rule
 * {@link com.heimdall.core.platform.Integrations#traceProbe} already states for its own half.
 *
 * <p>"Every path" includes the ones that end in <em>somebody else's</em> future never completing.
 * {@code probe_player} hands off to another plugin entirely, so {@link ProbePlayerHandler} arms its
 * own deadline rather than trusting Trace to finish — see {@link #PROBE_DEADLINE_MS}. Without it a
 * hung third-party probe reproduces the exact bug this class was written to fix, one layer further
 * out.
 *
 * <h2>Why these are wired here and not inside a module</h2>
 *
 * <p>The same category as {@code update} in {@link UpdateWiring}: a property of <em>having a
 * tunnel</em>, not a feature a guild opts into. (The placement is not identical —
 * {@code UpdateWiring.install} is called from the two platform bootstraps, because it needs a
 * platform-specific {@code UpdateInstaller}; nothing here needs anything a {@code PlatformFacade}
 * cannot supply, so this is installed once from {@link HeimdallRuntime#start()} instead.) v2 had no
 * module system at all, so v2 parity means "these work whenever the tunnel is up", and homing any of
 * them in a module would quietly make a dashboard button depend on a toggle that says nothing about
 * it. Putting {@code run_command} in {@code module-console} would be the sharpest version of that: a
 * guild that switches off console <em>streaming</em> would find the console <em>command box</em> had
 * stopped working, with the dashboard still offering it. See departure D71.
 *
 * <p>The corollary is that nothing here reads {@code ModuleConfig.enabled()} and no handler can
 * answer "module disabled" — there is no module to be disabled. The one thing a
 * {@code capabilities}-style gate would buy is the ability to refuse, and refusing a question v2
 * always answered is not an improvement.
 *
 * <h2>Threading</h2>
 *
 * <p>All three subscribe on {@code heimdall-io}, never the socket's reading thread, and both
 * asynchronous continuations name that executor too. The reason is departure D27's: a handler that
 * blocks the reading thread stops the tunnel reading, and the bot's liveness sweep then reaps a
 * connection that is working perfectly. {@code run_command} in particular completes on whichever
 * thread the platform dispatched on — the <em>server's main thread</em> on Bukkit — and replying from
 * there would put a socket write on the tick loop.
 */
public final class RemoteRequestWiring {

    /** The bot's request for the online roster. */
    static final String GET_PLAYERS = "get_players";

    /** The reply to {@link #GET_PLAYERS}. v2's type name, and what the bot correlates against. */
    static final String PLAYER_LIST = "player_list";

    /** The bot's request to run a console command. */
    static final String RUN_COMMAND = "run_command";

    /** The reply to {@link #RUN_COMMAND}. */
    static final String COMMAND_RESULT = "command_result";

    /** The bot's request to probe a player's client. */
    static final String PROBE_PLAYER = "probe_player";

    /** The reply to {@link #PROBE_PLAYER}. */
    static final String PROBE_RESULT = "probe_result";

    /**
     * How long {@link ProbePlayerHandler} waits for Trace before answering without it.
     *
     * <p>Ten seconds, chosen to sit comfortably under the bot's own fifteen-second budget for the
     * probe route: a deadline at or above the caller's is not a deadline, it is a second timeout that
     * never gets to fire.
     *
     * <p>This exists because the probe is the one request that hands off to <strong>another
     * plugin</strong>. {@code Integrations.traceProbe} answers promptly for every reason it can see —
     * no Trace, Trace too old, player offline, a proxy — but once Trace's own future is in hand,
     * nothing in this process guarantees it ever completes. A third-party plugin blocked on its own
     * network call would leave this handler silent forever, which is precisely the class of bug this
     * class was written to remove.
     */
    static final long PROBE_DEADLINE_MS = 10_000L;

    private RemoteRequestWiring() {
    }

    /**
     * Subscribes all three handlers.
     *
     * <p>Called once, from {@link HeimdallRuntime#start()}, and <strong>before</strong> the
     * not-configured early return: subscriptions live on the client rather than on a socket, so they
     * survive every reconnect and are already in place when a {@code /hd setup} brings a tunnel up
     * without a restart.
     *
     * @param scheduler where the probe deadline is armed — {@code heimdall-sched}, the plugin's own
     *     general-purpose timer. Not {@code heimdall-ws}: that pool exists so the tunnel's sense of
     *     time is unaffected by anything else, and its javadoc says nothing else may schedule there.
     *     {@code heimdall-sched} can be held for a moment by a blocking whitelist sync, which would
     *     make a deadline fire late — late is a tolerable degradation of a bound whose purpose is to
     *     replace <em>never</em>.
     * @return one handle that unsubscribes all three, closed with the runtime
     */
    public static Registration install(
            HeimdallLogger logger,
            PlatformFacade platform,
            TunnelBus tunnel,
            Executor io,
            ScheduledExecutorService scheduler) {
        List<Registration> handles = new ArrayList<Registration>(3);
        handles.add(tunnel.subscribe(
                GET_PLAYERS, new PlayerListHandler(logger, platform, tunnel), io));
        handles.add(tunnel.subscribe(
                RUN_COMMAND, new RunCommandHandler(logger, platform, tunnel, io), io));
        handles.add(tunnel.subscribe(
                PROBE_PLAYER, new ProbePlayerHandler(logger, platform, tunnel, io, scheduler), io));
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
     * Sends a reply and refuses to let the send be the thing that breaks.
     *
     * <p>The tunnel can drop between a frame arriving and its answer going out. Nothing can be done
     * about that — the bot times out, which is the honest outcome of a dead link — but it must not
     * escape into a handler and skip whatever came after it.
     */
    private static void reply(
            HeimdallLogger logger, TunnelBus tunnel, String id, String type, Payload payload) {
        try {
            tunnel.reply(id, type, payload);
        } catch (RuntimeException failed) {
            logger.debug(() -> "could not reply '" + type + "' to request " + id + ": " + failed);
        }
    }

    /** A one-key error payload, the shape v2 used and the dashboard renders. */
    private static Payload error(String message) {
        return Payload.builder().put("error", Strings.trimToEmpty(message)).build();
    }

    /**
     * The message a failed future is really about.
     *
     * <p>{@code CompletionException} wrapping is an artefact of how the future was composed and says
     * nothing to whoever is reading the dashboard, so it is unwrapped once.
     */
    private static Throwable unwrap(Throwable failure) {
        if (failure instanceof CompletionException && failure.getCause() != null) {
            return failure.getCause();
        }
        return failure;
    }

    /**
     * Answers the Online Players panel.
     *
     * <p>The reply is {@code player_list} carrying {@code {"players": [...]}}, and each row is
     * {@code uuid} and {@code username} plus whatever
     * {@link com.heimdall.core.platform.PlayerDirectory#describe} adds — {@code ip} on the Bukkit
     * family, {@code server} on a proxy. That split is deliberate: the two shared keys are written
     * here, once, so the platforms cannot drift apart on them the way v2's two entry points did,
     * while the third column stays each platform's own answer.
     *
     * <p><strong>An empty roster is a successful answer.</strong> Nobody being online is the ordinary
     * state of most servers, and it must not be reported as a failure — a panel that says "error" when
     * a server is quiet is a panel nobody trusts when it says "error" for a real reason.
     */
    static final class PlayerListHandler implements TunnelMessageHandler {

        private final HeimdallLogger logger;
        private final PlatformFacade platform;
        private final TunnelBus tunnel;

        PlayerListHandler(HeimdallLogger logger, PlatformFacade platform, TunnelBus tunnel) {
            this.logger = logger;
            this.platform = platform;
            this.tunnel = tunnel;
        }

        @Override
        public void onMessage(Envelope envelope) {
            List<Payload> rows = new ArrayList<Payload>();
            String failure = null;
            try {
                Collection<PlayerHandle> online = platform.players().onlinePlayers();
                if (online != null) {
                    for (PlayerHandle player : online) {
                        if (player != null) {
                            rows.add(row(player));
                        }
                    }
                }
            } catch (Throwable broken) {
                // A server part-way through starting or stopping, most likely. Whatever rows were
                // collected still go out, with a note — a partial roster the panel can show beats
                // a spinner that ends in a 504.
                logger.warn("could not read the online roster: " + broken);
                failure = String.valueOf(broken);
            }
            Payload.Builder answer = Payload.builder().putChildren("players", rows);
            if (failure != null) {
                answer.put("error", failure);
            }
            reply(logger, tunnel, envelope.id(), PLAYER_LIST, answer.build());
        }

        /**
         * One roster row: the two keys core owns, then the platform's own column.
         *
         * <p>A {@code null} uuid becomes {@code ""}, matching what {@code trimToEmpty} already does
         * to a null username. {@code String.valueOf} would put the four characters {@code null} on
         * the wire, which the dashboard would render as a player id and link to — an empty string is
         * at least visibly absent. The row is kept rather than skipped so the roster count stays
         * honest and the username, which is what an operator actually reads, still shows.
         */
        private Payload row(PlayerHandle player) {
            UUID uuid = player.uuid();
            Payload.Builder row = Payload.builder()
                    .put("uuid", uuid == null ? "" : uuid.toString())
                    .put("username", Strings.trimToEmpty(player.name()));
            Payload described = platform.players().describe(player);
            if (described != null) {
                row.putAll(described);
            }
            return row.build();
        }
    }

    /**
     * Answers the dashboard's console command box.
     *
     * <p>The reply is {@code command_result} carrying {@code {"output": "..."}} — v2's shape, and the
     * only key the dashboard reads. The word "output" is v2's and is kept, but it was never the
     * command's output on either platform and is not one here either: no server can attribute console
     * lines back to the command that caused them, which is why
     * {@link com.heimdall.core.platform.ConsoleBridge} says so in its signature and why the console
     * module streams the real log separately.
     *
     * <h2>Who is allowed to run this</h2>
     *
     * <p>Nobody is checked here, and that is not an oversight. The bot gates the dashboard route on
     * its own {@code useWebSocket} permission before the frame is ever sent, and the tunnel is
     * HMAC-authenticated on the upgrade — a frame arriving on it came from the bot that holds this
     * server's key. Re-deciding the question with a second, weaker rule inside the plugin would only
     * create somewhere for the two answers to disagree. Same trust boundary as v2's.
     *
     * <h2>An unknown command is reported, not acknowledged</h2>
     *
     * <p>v2 replied "Command dispatched: foo" whether or not {@code foo} existed, because it threw
     * the platform's boolean away. v3's {@code ConsoleBridge} fails the future with
     * {@link UnknownCommandException} instead, and this handler carries that through to the operator
     * as a sentence rather than flattening it back into a cheerful acknowledgement. Departure D72.
     */
    static final class RunCommandHandler implements TunnelMessageHandler {

        private final HeimdallLogger logger;
        private final PlatformFacade platform;
        private final TunnelBus tunnel;
        private final Executor io;

        RunCommandHandler(
                HeimdallLogger logger, PlatformFacade platform, TunnelBus tunnel, Executor io) {
            this.logger = logger;
            this.platform = platform;
            this.tunnel = tunnel;
            this.io = io;
        }

        @Override
        public void onMessage(Envelope envelope) {
            final String id = envelope.id();
            final String command = Strings.trimToEmpty(envelope.payload().string("command", ""));
            if (command.isEmpty()) {
                // v2 hit a bare `break` here and replied nothing at all, so an empty command box
                // burned the bot's full request timeout for a mistake it could have been told about
                // immediately.
                answer(id, "no command was given");
                return;
            }

            CompletableFuture<String> dispatched;
            try {
                dispatched = platform.console().dispatchCommand(command);
            } catch (Throwable refused) {
                // A bridge that throws rather than failing its future. Documented not to, so this is
                // a bug — but the frame is answered either way.
                logger.warn("dispatching '" + command + "' threw: " + refused);
                answer(id, "the command could not be dispatched: " + refused);
                return;
            }
            if (dispatched == null) {
                answer(id, "the command could not be dispatched");
                return;
            }

            // Named executor, never the completing thread: on Bukkit that thread is the server's
            // main thread, and a socket write does not belong on the tick loop.
            dispatched.whenCompleteAsync(new BiConsumer<String, Throwable>() {
                @Override
                public void accept(String acknowledgement, Throwable failure) {
                    answer(id, describe(command, acknowledgement, failure));
                }
            }, io);
        }

        /** What the operator is told, for each of the three ways a dispatch can end. */
        private String describe(String command, String acknowledgement, Throwable failure) {
            if (failure == null) {
                return acknowledgement == null ? "dispatched: " + command : acknowledgement;
            }
            Throwable cause = unwrap(failure);
            if (cause instanceof UnknownCommandException) {
                // The one branch worth spelling out: an operator who typed a verb this server does
                // not have needs to know that, not to be told it ran.
                return "no such command: " + ((UnknownCommandException) cause).command();
            }
            logger.warn("console command '" + command + "' failed: " + cause);
            return "the command failed: " + cause;
        }

        private void answer(String id, String output) {
            reply(logger, tunnel, id, COMMAND_RESULT,
                    Payload.builder().put("output", output).build());
        }
    }

    /**
     * Answers the mod-probe button on a player's dashboard page.
     *
     * <p>The reply is {@code probe_result} carrying either Trace's own result object or
     * {@code {"error": "..."}} — v2's shape on both platforms, including the "not applicable here"
     * case: v2's Velocity arm replied an error rather than staying quiet, because a proxy has no
     * client connection to inspect and the bot is waiting either way.
     *
     * <p>Almost all of the branching lives in
     * {@link com.heimdall.core.platform.Integrations#traceProbe}, which already answers with an error
     * payload for every reason it cannot help — no Trace, Trace too old to support remote probing,
     * player offline, a proxy. This handler owns the three things that facade cannot see: a
     * {@code uuid} that is not a uuid, a future that fails instead of completing, and a future that
     * does <strong>neither</strong>.
     *
     * <h2>The deadline is the point</h2>
     *
     * <p>This is the only one of the three requests whose answer comes from <em>another plugin</em>.
     * Trace hands back a {@code CompletableFuture} and nothing in this process obliges it ever to
     * complete one: Trace blocked on its own network call, or on a player who stopped responding to
     * the probe packet, leaves this handler silent forever — the same dangling request this class
     * exists to abolish, one layer further out and harder to attribute.
     *
     * <p>Java 8 has no {@code orTimeout}, so the deadline is a one-shot on {@code heimdall-sched},
     * cancelled the moment the probe completes. Whichever arrives first wins:
     * {@code answered.compareAndSet} makes sure exactly one reply is sent, because the bot files a
     * second reply on the same id as unsolicited and a duplicate is not a harmless one.
     */
    static final class ProbePlayerHandler implements TunnelMessageHandler {

        private final HeimdallLogger logger;
        private final PlatformFacade platform;
        private final TunnelBus tunnel;
        private final Executor io;
        private final ScheduledExecutorService scheduler;
        private final long deadlineMs;

        ProbePlayerHandler(
                HeimdallLogger logger,
                PlatformFacade platform,
                TunnelBus tunnel,
                Executor io,
                ScheduledExecutorService scheduler) {
            this(logger, platform, tunnel, io, scheduler, PROBE_DEADLINE_MS);
        }

        /**
         * The deadline is injectable so a test can prove the timeout without waiting ten seconds for
         * it. Nothing in production passes anything but {@link #PROBE_DEADLINE_MS}.
         */
        ProbePlayerHandler(
                HeimdallLogger logger,
                PlatformFacade platform,
                TunnelBus tunnel,
                Executor io,
                ScheduledExecutorService scheduler,
                long deadlineMs) {
            this.logger = logger;
            this.platform = platform;
            this.tunnel = tunnel;
            this.io = io;
            this.scheduler = scheduler;
            this.deadlineMs = Math.max(1L, deadlineMs);
        }

        @Override
        public void onMessage(Envelope envelope) {
            final String id = envelope.id();
            String raw = Strings.trimToEmpty(envelope.payload().string("uuid", ""));
            UUID target;
            try {
                if (raw.isEmpty()) {
                    throw new IllegalArgumentException("missing uuid");
                }
                target = UUID.fromString(raw);
            } catch (IllegalArgumentException notAUuid) {
                // v2's #797 / MC-12 fix, kept: an unparseable uuid used to throw out of the switch
                // and leave the request dangling for the bot's full 15-second probe timeout.
                answer(id, error("invalid player uuid: '" + raw + "'"));
                return;
            }

            CompletableFuture<Payload> probe;
            try {
                probe = platform.integrations().traceProbe(target);
            } catch (Throwable broken) {
                logger.warn("the Trace probe for " + target + " threw: " + broken);
                answer(id, error("the probe could not be started: " + broken));
                return;
            }
            if (probe == null) {
                answer(id, error("the probe reported nothing"));
                return;
            }

            // Exactly one of the two racers below gets to answer. A duplicate reply on the same id is
            // filed by the bot as unsolicited, so "both eventually replied" is not a benign outcome.
            final AtomicBoolean answered = new AtomicBoolean();
            final ScheduledFuture<?> deadline = armDeadline(id, answered, target);

            probe.whenCompleteAsync(new BiConsumer<Payload, Throwable>() {
                @Override
                public void accept(Payload result, Throwable failure) {
                    if (deadline != null) {
                        // Cancelled first, so a probe that answers at 9.9s cannot be followed by a
                        // timer firing at 10s into an already-satisfied request.
                        deadline.cancel(false);
                    }
                    if (!answered.compareAndSet(false, true)) {
                        // The deadline already spoke. Trace turning up afterwards is not news the
                        // dashboard can use — its request is long since resolved.
                        logger.debug(() -> "a Trace probe answered after its deadline; dropping it");
                        return;
                    }
                    if (failure != null) {
                        Throwable cause = unwrap(failure);
                        logger.debug(() -> "the Trace probe failed: " + cause);
                        answer(id, error(String.valueOf(cause)));
                        return;
                    }
                    answer(id, result == null ? error("the probe reported nothing") : result);
                }
            }, io);
        }

        /**
         * Schedules the "answer without Trace" fallback, or answers immediately if it cannot be.
         *
         * @return the handle to cancel, or {@code null} when the scheduler is shutting down — in
         *     which case the caller has already been answered and there is nothing to cancel
         */
        private ScheduledFuture<?> armDeadline(
                final String id, final AtomicBoolean answered, final UUID target) {
            try {
                return scheduler.schedule(new Runnable() {
                    @Override
                    public void run() {
                        if (!answered.compareAndSet(false, true)) {
                            return;
                        }
                        logger.warn("the Trace probe for " + target + " did not answer within "
                                + deadlineMs + "ms; replying without it");
                        answer(id, error("the client probe timed out"));
                    }
                }, deadlineMs, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException shuttingDown) {
                // The pools are going down with the plugin, so nothing would ever fire this. Answer
                // now rather than leaving the request to depend on a probe nobody is timing.
                if (answered.compareAndSet(false, true)) {
                    answer(id, error("the server is shutting down"));
                }
                return null;
            }
        }

        private void answer(String id, Payload payload) {
            reply(logger, tunnel, id, PROBE_RESULT, payload);
        }
    }
}
