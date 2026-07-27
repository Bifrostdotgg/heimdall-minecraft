package com.heimdall.core.tunnel;

import com.heimdall.core.concurrent.HeimdallExecutors;
import com.heimdall.core.http.HmacSigner;
import com.heimdall.core.json.Envelope;
import com.heimdall.core.json.Payload;
import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.util.Registration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * The outbound tunnel to the Heimdall bot: one socket, kept alive, reconnected, and multiplexed.
 *
 * <p>The plugin never opens an inbound port. Everything the bot needs to ask a server — run this
 * command, who is online, apply these groups — arrives over a connection the server itself made.
 *
 * <h2>The invariants this class exists to hold</h2>
 *
 * <p>Every one of these was a production incident in v2. They are listed because the code that
 * implements them looks arbitrary until you know which failure it is standing in front of.
 *
 * <ol type="a">
 *   <li><strong>A wedged link is aborted, never closed.</strong> {@link #forceReconnect} calls
 *       {@link TunnelSocket#abort()}. A graceful close waits for the peer's close frame, and a peer
 *       black-holed by a dead NAT entry never sends one — so the client waits forever and never
 *       reconnects.
 *   <li><strong>All four reconnect triggers collapse into one.</strong> The close callback, the
 *       error callback, a failed connect attempt and the heartbeat timeout can all fire for the
 *       same dead connection. They pass through a single compare-and-set in {@link
 *       ReconnectPolicy#tryClaim()}, so exactly one reconnect is scheduled. Without it the server
 *       ends up with several live sockets to the same bot, which presents as commands running twice
 *       rather than as a connection error.
 *   <li><strong>Backoff doubles and resets.</strong> From {@code reconnectDelayMs} to
 *       {@code maxReconnectDelayMs}, back to the base on a successful open.
 *   <li><strong>The socket factory is reused across attempts.</strong> v2 built a fresh HTTP client
 *       per attempt for a while, each carrying a selector thread that never went away.
 *   <li><strong>Three distinct teardowns.</strong> {@link #disconnect()} leaves the instance
 *       reusable (the tunnel switched off by config); {@link #reconnect(String)} rebuilds in place
 *       (a reload); {@link #shutdown()} latches and is idempotent (the plugin stopping).
 *   <li><strong>No pending request outlives its connection.</strong> Every teardown fails every
 *       outstanding future. A correlation id cannot survive a reconnect — the bot has forgotten it
 *       — so leaving one to time out blocks a caller on an answer that was already impossible.
 * </ol>
 *
 * <h2>Ownership</h2>
 *
 * <p><strong>This class does not own its executors.</strong> They come from {@link
 * HeimdallExecutors}, which is the only thing that shuts them down. {@link #shutdown()} stops the
 * tunnel, not the pools — v2 shut its scheduler down here and then had to reconstruct one on every
 * reconnect, which is where the "no live scheduler" branches in its reconnect path came from.
 *
 * <h2>Threading</h2>
 *
 * <p>Every public method is safe from any thread. Socket callbacks arrive on the library's reading
 * thread and are kept to parsing and routing; heartbeat, reconnect and timeouts run on {@code
 * heimdall-ws}; subscriber handlers run on {@code heimdall-io} or an executor they named.
 */
public final class TunnelClient implements TunnelBus {

    /** Close code for a deliberate, orderly disconnect. */
    private static final int NORMAL_CLOSURE = 1000;

    private final HeimdallLogger logger;
    private final HeimdallExecutors executors;
    private final ScheduledExecutorService wsScheduler;
    private final TunnelSocketFactory socketFactory;
    private final LongSupplier clock;

    private final SubscriptionRegistry subscriptions;
    private final PendingRequests pending;
    private final ReconnectPolicy reconnectPolicy;
    private final HandshakeNegotiator negotiator;
    private final TunnelHeartbeat heartbeat;
    private final TunnelDispatcher dispatcher;

    private volatile TunnelSettings settings;
    private volatile TunnelSocket socket;
    private volatile boolean connected;
    private volatile long lastInboundMs;
    private volatile ScheduledFuture<?> reconnectTask;
    private volatile HealthSnapshotSource healthSource;
    private volatile CapabilitySource capabilitySource;
    private volatile TunnelMessageHandler unhandledHandler;

    private final AtomicBoolean shutdown = new AtomicBoolean();

    /**
     * Which connection attempt a callback belongs to.
     *
     * <p><strong>New in v3.</strong> Bumped on every attempt and on every deliberate teardown; a
     * listener captures its value and ignores anything that arrives once it no longer matches.
     * Without it, a late {@code onClose} from a socket that was aborted minutes ago schedules a
     * reconnect that kills the healthy socket which replaced it — and a graceful
     * {@link #disconnect()} immediately reconnects itself, because its own close callback looks
     * exactly like an unexpected drop. v2 had both bugs and papered over the second by destroying
     * its scheduler.
     */
    private final AtomicLong generation = new AtomicLong();

    private final FrameSender frames = new FrameSender() {
        @Override
        public void sendFrame(Envelope envelope) {
            sendRaw(envelope.toJson());
        }
    };

    private TunnelClient(Builder builder) {
        this.logger = builder.logger;
        this.executors = builder.executors;
        this.wsScheduler = builder.executors.ws();
        this.socketFactory = builder.socketFactory;
        this.clock = builder.clock;
        this.settings = builder.settings;
        this.healthSource = builder.healthSource;
        this.capabilitySource = builder.capabilitySource;
        this.lastInboundMs = builder.clock.getAsLong();

        this.subscriptions = new SubscriptionRegistry(builder.logger);
        this.pending = new PendingRequests(builder.executors.ws());
        this.reconnectPolicy = new ReconnectPolicy(
                builder.settings.reconnectDelayMs(), builder.settings.maxReconnectDelayMs());
        this.negotiator = new HandshakeNegotiator(
                builder.logger,
                builder.executors.ws(),
                frames,
                builder.identitySource,
                new CapabilitySource() {
                    @Override
                    public java.util.Set<String> capabilities() {
                        CapabilitySource source = capabilitySource;
                        return source == null ? java.util.Collections.<String>emptySet() : source.capabilities();
                    }
                },
                builder.configPushHandler);
        this.heartbeat = new TunnelHeartbeat(builder.logger, builder.executors.ws(), this);
        this.dispatcher = new TunnelDispatcher(
                builder.logger, this, negotiator, pending, subscriptions);
    }

    public static Builder builder(HeimdallLogger logger, HeimdallExecutors executors) {
        return new Builder(logger, executors);
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    /**
     * Starts connecting, asynchronously. Safe to call repeatedly and after {@link #disconnect()}.
     *
     * <p>Does nothing once {@link #shutdown()} has been called, and logs rather than throws when the
     * settings are not complete enough to connect — an unconfigured server should boot and say so,
     * not fail to load.
     */
    public void connect() {
        if (shutdown.get()) {
            return;
        }
        TunnelSettings current = settings;
        if (!current.isConfigured()) {
            logger.warn("tunnel not started: no endpoint, guild or credentials configured yet");
            return;
        }
        reconnectPolicy.updateBounds(current.reconnectDelayMs(), current.maxReconnectDelayMs());
        reconnectPolicy.reset();
        doConnect();
    }

    /**
     * Closes the connection and stops reconnecting, leaving the instance reusable.
     *
     * <p>For the tunnel being switched off by configuration. A later {@link #connect()} or
     * {@link #reconnect(String)} brings it back.
     */
    public void disconnect() {
        teardown("tunnel disconnected", NORMAL_CLOSURE, "Heimdall tunnel disabled");
        reconnectPolicy.reset();
        logger.info("tunnel disconnected (disabled)");
    }

    /**
     * Rebuilds the connection in place, optionally under a new guild id.
     *
     * <p>What {@code /hd reload} calls. Reuses this instance and its executors rather than building
     * a throwaway client, which would orphan the old socket's threads.
     *
     * @param guildId the guild to connect as, or {@code null}/empty to keep the current one
     */
    public void reconnect(String guildId) {
        if (shutdown.get()) {
            return;
        }
        if (guildId != null && !guildId.isEmpty()) {
            settings = settings.toBuilder().guildId(guildId).build();
        }
        TunnelSettings current = settings;
        reconnectPolicy.updateBounds(current.reconnectDelayMs(), current.maxReconnectDelayMs());
        // A manual retry is a fresh start: an operator who just fixed the endpoint should not wait
        // out a backoff that grew to thirty seconds while it was wrong.
        reconnectPolicy.reset();

        if (socket == null) {
            connect();
            return;
        }
        forceReconnect("Reload requested");
    }

    /**
     * Swaps the settings, reconnecting if anything that shapes the connection changed.
     *
     * <p>Timing-only changes (heartbeat interval, backoff bounds) are applied without dropping a
     * working link; a different endpoint, guild, server id or credential cannot be applied to a
     * socket that is already open, so those reconnect.
     */
    public void applySettings(TunnelSettings updated) {
        if (updated == null) {
            throw new IllegalArgumentException("settings are required");
        }
        TunnelSettings previous = settings;
        settings = updated;
        reconnectPolicy.updateBounds(updated.reconnectDelayMs(), updated.maxReconnectDelayMs());
        boolean connectionShapeChanged =
                !previous.endpoint().equals(updated.endpoint())
                        || !previous.guildId().equals(updated.guildId())
                        || !previous.serverId().equals(updated.serverId())
                        || !previous.apiKey().equals(updated.apiKey());
        if (connectionShapeChanged && socket != null) {
            forceReconnect("Tunnel settings changed");
        }
    }

    /**
     * Stops the tunnel for good. Idempotent.
     *
     * <p>Latches: nothing reconnects afterwards. Every pending request is failed rather than left
     * to its deadline, because the deadline is scheduled on a pool that is about to stop.
     *
     * <p>Does <strong>not</strong> shut the executors down — see the ownership note on the class.
     */
    public void shutdown() {
        if (!shutdown.compareAndSet(false, true)) {
            return;
        }
        teardown("tunnel is shutting down", NORMAL_CLOSURE, "Plugin shutting down");
        subscriptions.clear();
        logger.info("tunnel client shut down");
    }

    // ── TunnelBus ────────────────────────────────────────────────────────────

    @Override
    public void send(String type, Payload payload) {
        sendRaw(Envelope.fresh(type, payload).toJson());
    }

    @Override
    public void reply(String requestId, String type, Payload payload) {
        sendRaw(Envelope.of(requestId, type, payload).toJson());
    }

    @Override
    public CompletableFuture<Payload> sendAndWait(String type, Payload payload) {
        return sendAndWait(type, payload, settings.requestTimeoutMs());
    }

    @Override
    public CompletableFuture<Payload> sendAndWait(String type, Payload payload, long timeoutMs) {
        if (!isConnected()) {
            CompletableFuture<Payload> failed = new CompletableFuture<Payload>();
            failed.completeExceptionally(
                    new IllegalStateException("tunnel is not connected; cannot send '" + type + "'"));
            return failed;
        }
        Envelope envelope = Envelope.fresh(type, payload);
        CompletableFuture<Payload> future = pending.register(envelope.id(), type, timeoutMs);
        if (!sendRaw(envelope.toJson())) {
            // The link dropped between the check and the write. Fail now rather than let the
            // caller wait out a deadline for a frame that never left the process.
            CompletableFuture<Payload> abandoned = pending.forget(envelope.id());
            if (abandoned != null) {
                abandoned.completeExceptionally(
                        new IllegalStateException("tunnel dropped while sending '" + type + "'"));
            }
        }
        return future;
    }

    @Override
    public Registration subscribe(String type, TunnelMessageHandler handler) {
        return subscriptions.subscribe(type, handler, executors.io());
    }

    @Override
    public Registration subscribe(String type, TunnelMessageHandler handler, Executor executor) {
        return subscriptions.subscribe(type, handler, executor);
    }

    @Override
    public ProtocolMode mode() {
        return negotiator.mode();
    }

    @Override
    public boolean isConnected() {
        TunnelSocket current = socket;
        return connected && current != null;
    }

    // ── Wiring ───────────────────────────────────────────────────────────────

    /** The config version the bot last advertised, or -1. */
    public int configVersion() {
        return negotiator.configVersion();
    }

    /** The settings currently in force. */
    public TunnelSettings settings() {
        return settings;
    }

    /** Registers a listener for {@link ProtocolMode} changes. */
    public Registration onModeChange(ProtocolModeListener listener) {
        return negotiator.onModeChange(listener);
    }

    /** Sets the health snapshot source; {@code null} stops health messages being sent. */
    public void setHealthSource(HealthSnapshotSource source) {
        this.healthSource = source;
    }

    /**
     * Sets what capabilities {@code identify} declares — in production, {@code ModuleManager}.
     *
     * <p>A setter rather than a constructor argument because the dependency is genuinely circular:
     * the module manager hands each module a bus backed by this client, and this client asks the
     * module manager what to declare.
     */
    public void setCapabilitySource(CapabilitySource source) {
        this.capabilitySource = source;
    }

    /**
     * Sets the last-chance handler for messages nothing subscribed to.
     *
     * <p>Where the public {@code HeimdallTunnel} SPI plugs in from the platform layer in phase 1c,
     * so a third-party plugin can see traffic Heimdall itself has no opinion about.
     */
    public void setUnhandledHandler(TunnelMessageHandler handler) {
        this.unhandledHandler = handler;
    }

    // ── Collaborator hooks (package-private) ─────────────────────────────────

    /** Sends a frame if the link is up. */
    void sendFrame(Envelope envelope) {
        frames.sendFrame(envelope);
    }

    /** The configured health source, or {@code null}. */
    HealthSnapshotSource healthSource() {
        return healthSource;
    }

    /** How long since anything at all arrived from the bot. */
    long millisSinceLastInbound() {
        return clock.getAsLong() - lastInboundMs;
    }

    /** Records that the bot is demonstrably alive. Called for every parseable inbound frame. */
    void markAlive() {
        lastInboundMs = clock.getAsLong();
    }

    /**
     * Hands a frame nothing subscribed to to the fallback handler, on {@code heimdall-io}.
     *
     * @return whether a handler was registered at all
     */
    boolean dispatchUnhandled(final Envelope envelope) {
        final TunnelMessageHandler handler = unhandledHandler;
        if (handler == null) {
            return false;
        }
        try {
            executors.io().execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        handler.onMessage(envelope);
                    } catch (RuntimeException e) {
                        logger.error("unhandled-message handler threw on '" + envelope.type() + "'", e);
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            logger.debug("dropped unhandled '" + envelope.type() + "': the IO pool is shutting down");
        }
        return true;
    }

    /**
     * The heartbeat, so a test can drive one tick instead of waiting thirty seconds for it.
     *
     * <p>Package-private, and the alternative is worse: the timeout check is the invariant most
     * worth testing and the only other way to reach it is a real sleep, which is how a suite
     * acquires the flakiness that later gets blamed on "timing".
     */
    TunnelHeartbeat heartbeat() {
        return heartbeat;
    }

    /** The backoff and single-flight state, so a test can assert on both directly. */
    ReconnectPolicy reconnectPolicy() {
        return reconnectPolicy;
    }

    /** The correlation map, so a test can assert nothing was left outstanding. */
    PendingRequests pendingRequests() {
        return pending;
    }

    /**
     * Aborts a connection believed to be dead and schedules a replacement.
     *
     * <p>Invariant (a): {@link TunnelSocket#abort()}, never a graceful close. Invariant (f): every
     * pending future is failed here, not left to its deadline.
     */
    void forceReconnect(String reason) {
        TunnelSocket dead = detachSocket();
        heartbeat.stop();
        negotiator.onClosed();
        pending.failAll(reason);
        if (dead != null) {
            try {
                dead.abort();
            } catch (RuntimeException e) {
                logger.debug("aborting a dead socket threw, which changes nothing: " + e);
            }
        }
        logger.warn("tunnel: " + reason + " — aborting and reconnecting");
        scheduleReconnect();
    }

    // ── Connecting ───────────────────────────────────────────────────────────

    private void doConnect() {
        if (shutdown.get()) {
            return;
        }
        TunnelSettings current = settings;
        final long attempt = generation.incrementAndGet();

        String url;
        try {
            url = TunnelUrls.upgradeUrl(current, new HmacSigner(current.apiKey()));
        } catch (RuntimeException e) {
            logger.error("could not build the tunnel URL; check the configured endpoint", e);
            scheduleReconnect();
            return;
        }

        logger.info("tunnel connecting to " + TunnelUrls.sanitize(url));
        try {
            AtomicReference<TunnelSocket> self = new AtomicReference<TunnelSocket>();
            TunnelSocket created = socketFactory.create(url, new SocketCallbacks(attempt, self));
            self.set(created);
            socket = created;
            created.connect();
        } catch (RuntimeException e) {
            logger.warn("tunnel connection failed: " + e);
            scheduleReconnect();
        }
    }

    /**
     * Schedules the next attempt — at most one, however many callers ask.
     *
     * <p>Invariant (b) lives here. The gate is released when the attempt <em>starts</em>, not when
     * it succeeds, so a connect that fails immediately can schedule the one after it.
     */
    private void scheduleReconnect() {
        if (shutdown.get()) {
            return;
        }
        if (!reconnectPolicy.tryClaim()) {
            return;
        }
        long delay = reconnectPolicy.nextDelayMs();
        logger.info("tunnel reconnecting in " + delay + "ms");
        try {
            reconnectTask = wsScheduler.schedule(new Runnable() {
                @Override
                public void run() {
                    reconnectPolicy.release();
                    doConnect();
                }
            }, delay, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            // Shutting down. Release the gate anyway so nothing is left latched in a state a later
            // connect() would have to clear.
            reconnectPolicy.release();
            logger.debug("reconnect not scheduled: the tunnel scheduler is shutting down");
        }
    }

    /** Shared body of disconnect and shutdown: stop everything, close politely, fail everything. */
    private void teardown(String reason, int closeCode, String closeReason) {
        // Bumping first is what stops the graceful close below from looking like an unexpected drop
        // and immediately reconnecting.
        generation.incrementAndGet();
        cancelReconnect();
        heartbeat.stop();
        negotiator.onClosed();
        TunnelSocket live = detachSocket();
        if (live != null) {
            try {
                live.close(closeCode, closeReason);
            } catch (RuntimeException e) {
                logger.debug("closing the tunnel socket threw: " + e);
            }
        }
        pending.failAll(reason);
    }

    private void cancelReconnect() {
        ScheduledFuture<?> scheduled = reconnectTask;
        reconnectTask = null;
        if (scheduled != null) {
            scheduled.cancel(false);
        }
        reconnectPolicy.release();
    }

    private TunnelSocket detachSocket() {
        TunnelSocket current = socket;
        socket = null;
        connected = false;
        return current;
    }

    /**
     * Writes a frame.
     *
     * @return whether it was handed to an open socket. Callers that are merely announcing something
     *     ignore this; {@code sendAndWait} does not, because a caller waiting on a reply has to be
     *     told the question never left the building.
     */
    private boolean sendRaw(String json) {
        TunnelSocket current = socket;
        if (current == null || !connected) {
            return false;
        }
        try {
            current.sendText(json);
            return true;
        } catch (RuntimeException e) {
            logger.debug("tunnel send failed: " + e);
            return false;
        }
    }

    /**
     * The listener for one connection attempt.
     *
     * <p>Every callback checks its generation first. Anything from a superseded attempt is dropped
     * — see the note on {@link #generation}.
     */
    private final class SocketCallbacks implements TunnelSocketListener {

        private final long attempt;
        private final AtomicReference<TunnelSocket> self;

        SocketCallbacks(long attempt, AtomicReference<TunnelSocket> self) {
            this.attempt = attempt;
            this.self = self;
        }

        @Override
        public void onOpen() {
            if (isStale()) {
                // A socket from an attempt we have already moved past. Close it rather than leak
                // it: nothing else holds a reference, so nothing else can.
                TunnelSocket orphan = self.get();
                if (orphan != null) {
                    orphan.abort();
                }
                return;
            }
            connected = true;
            markAlive();
            reconnectPolicy.reset();
            logger.info("tunnel connected");

            TunnelSettings current = settings;
            negotiator.onOpen(current);
            heartbeat.start(current);
        }

        @Override
        public void onText(String text) {
            if (isStale()) {
                return;
            }
            dispatcher.dispatch(text);
        }

        @Override
        public void onError(Throwable error) {
            if (isStale()) {
                return;
            }
            logger.warn("tunnel error: " + error);
            connectionLost();
        }

        @Override
        public void onClose(int code, String reason) {
            if (isStale()) {
                return;
            }
            logger.info("tunnel disconnected: code=" + code + " reason=" + reason);
            connectionLost();
        }

        private void connectionLost() {
            detachSocket();
            heartbeat.stop();
            negotiator.onClosed();
            pending.failAll("tunnel disconnected");
            scheduleReconnect();
        }

        private boolean isStale() {
            return attempt != generation.get();
        }
    }

    /** The mutable writer. Logger, executors, socket factory and settings are all required. */
    public static final class Builder {

        private final HeimdallLogger logger;
        private final HeimdallExecutors executors;
        private TunnelSocketFactory socketFactory;
        private TunnelSettings settings = TunnelSettings.builder().build();
        private IdentitySource identitySource;
        private CapabilitySource capabilitySource;
        private ConfigPushHandler configPushHandler = new ConfigPushHandler() {
            @Override
            public void onConfigPush(Payload document) {
                // No-op default so the client is usable before RemoteConfig is wired in. The push
                // is still acknowledged by the negotiator, which is what keeps the bot from
                // re-sending it forever.
            }
        };
        private HealthSnapshotSource healthSource;
        private LongSupplier clock = new LongSupplier() {
            @Override
            public long getAsLong() {
                return System.currentTimeMillis();
            }
        };

        private Builder(HeimdallLogger logger, HeimdallExecutors executors) {
            if (logger == null || executors == null) {
                throw new IllegalArgumentException("logger and executors are required");
            }
            this.logger = logger;
            this.executors = executors;
        }

        public Builder settings(TunnelSettings value) {
            this.settings = value;
            return this;
        }

        /** The socket implementation. Defaults to {@link NvTunnelSocketFactory} when left unset. */
        public Builder socketFactory(TunnelSocketFactory value) {
            this.socketFactory = value;
            return this;
        }

        public Builder identitySource(IdentitySource value) {
            this.identitySource = value;
            return this;
        }

        public Builder capabilitySource(CapabilitySource value) {
            this.capabilitySource = value;
            return this;
        }

        public Builder configPushHandler(ConfigPushHandler value) {
            if (value != null) {
                this.configPushHandler = value;
            }
            return this;
        }

        public Builder healthSource(HealthSnapshotSource value) {
            this.healthSource = value;
            return this;
        }

        /**
         * Replaces the system clock used for the heartbeat's silence measurement.
         *
         * <p>For tests, which need to make thirty seconds pass without waiting for them. Nothing in
         * production should call this.
         */
        public Builder clock(LongSupplier value) {
            if (value != null) {
                this.clock = value;
            }
            return this;
        }

        public TunnelClient build() {
            if (settings == null) {
                throw new IllegalArgumentException("settings are required");
            }
            if (socketFactory == null) {
                socketFactory = new NvTunnelSocketFactory(settings.connectTimeoutMs());
            }
            return new TunnelClient(this);
        }
    }
}
