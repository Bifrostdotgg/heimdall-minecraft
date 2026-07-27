package com.heimdall.core.tunnel;

import com.heimdall.core.BuildConstants;
import com.heimdall.core.json.Envelope;
import com.heimdall.core.json.Payload;
import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.util.Registration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * The v3 capability handshake: send {@code identify}, decide what the bot speaks, take the config.
 *
 * <h2>The problem it solves</h2>
 *
 * <p>A v2 bot answers {@code identify} with <strong>nothing at all</strong>. That is the deployed
 * contract, not a bug, so "no reply" cannot be treated as a failure — a client that reconnected on
 * silence would loop forever against every bot that has not been upgraded. But it also cannot be
 * waited on indefinitely, because until the question is settled the plugin does not know whether
 * config is coming.
 *
 * <p>So: send {@code identify} declaring {@code protocolVersion} and {@code capabilities}, then arm
 * a deadline. An {@code identify_ack} inside it means {@link ProtocolMode#V3}; the deadline
 * expiring means {@link ProtocolMode#V2_COMPAT} and life goes on with cached config.
 *
 * <h2>Renegotiation</h2>
 *
 * <p>Every connection negotiates from scratch. A fleet is upgraded one bot at a time and a
 * reconnect may well land on a different one — caching "this bot is v2" across connections would
 * leave a server running on stale config until somebody restarted it.
 *
 * <h2>Threading</h2>
 *
 * <p>{@link #onOpen} and {@link #handle} run on the socket's reading thread; the deadline fires on
 * {@code heimdall-ws}. The mode is volatile and transitions go through one synchronized method, so
 * the ack and the deadline racing each other produces exactly one outcome and one listener call.
 */
final class HandshakeNegotiator {

    private final HeimdallLogger logger;
    private final ScheduledExecutorService wsScheduler;
    private final FrameSender sender;
    private final IdentitySource identitySource;
    private final CapabilitySource capabilitySource;
    private final ConfigPushHandler configHandler;

    private final CopyOnWriteArrayList<ProtocolModeListener> listeners =
            new CopyOnWriteArrayList<ProtocolModeListener>();

    private volatile ProtocolMode mode = ProtocolMode.UNKNOWN;
    private volatile String identifyId;
    private volatile ScheduledFuture<?> deadline;
    private volatile int configVersion = -1;

    /**
     * What the bot said it will honour, from the last {@code identify_ack}.
     *
     * <p>Empty until a v3 ack arrives, and empty for a bot that acked with the older boolean shape —
     * "it told us nothing" and "it accepted nothing" are the same value here, which is why the
     * warning about unaccepted capabilities is skipped when it is empty rather than treating the
     * whole declared set as refused.
     */
    private volatile List<String> acceptedCapabilities = Collections.emptyList();

    HandshakeNegotiator(
            HeimdallLogger logger,
            ScheduledExecutorService wsScheduler,
            FrameSender sender,
            IdentitySource identitySource,
            CapabilitySource capabilitySource,
            ConfigPushHandler configHandler) {
        this.logger = logger;
        this.wsScheduler = wsScheduler;
        this.sender = sender;
        this.identitySource = identitySource;
        this.capabilitySource = capabilitySource;
        this.configHandler = configHandler;
    }

    /** The mode this connection negotiated. */
    ProtocolMode mode() {
        return mode;
    }

    /** The capabilities the bot acknowledged. Empty while disconnected or against a v2 bot. */
    List<String> acceptedCapabilities() {
        return acceptedCapabilities;
    }

    /** The config version the bot last advertised, or -1 if it never did. */
    int configVersion() {
        return configVersion;
    }

    /** Registers a mode listener. Fired only on an actual change. */
    Registration onModeChange(final ProtocolModeListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener is required");
        }
        listeners.add(listener);
        return Registration.once(new Runnable() {
            @Override
            public void run() {
                listeners.remove(listener);
            }
        });
    }

    /** Sends {@code identify} and arms the negotiation deadline. Called on every socket open. */
    void onOpen(TunnelSettings settings) {
        transitionTo(ProtocolMode.UNKNOWN);
        configVersion = -1;
        acceptedCapabilities = Collections.emptyList();

        Envelope identify = Envelope.fresh("identify", buildIdentifyPayload(settings));
        identifyId = identify.id();
        sender.sendFrame(identify);
        armDeadline(settings.negotiationTimeoutMs());
    }

    /** Cancels the deadline and forgets the negotiated mode. Called on every teardown. */
    void onClosed() {
        cancelDeadline();
        identifyId = null;
        configVersion = -1;
        acceptedCapabilities = Collections.emptyList();
        transitionTo(ProtocolMode.UNKNOWN);
    }

    /**
     * Handles the two v3-only message types.
     *
     * @return whether this negotiator consumed the frame
     */
    boolean handle(Envelope envelope) {
        if ("identify_ack".equals(envelope.type())) {
            handleIdentifyAck(envelope);
            return true;
        }
        if ("config.push".equals(envelope.type())) {
            handleConfigPush(envelope);
            return true;
        }
        return false;
    }

    private void handleIdentifyAck(Envelope envelope) {
        if (identifyId == null) {
            // No handshake is in flight, so this ack belongs to a connection already torn down.
            //
            // The id is deliberately NOT compared against the identify's. An earlier draft required
            // the ack to echo it, on the assumption that the bot correlates them — it does not, it
            // sends a fresh id. That guard discarded every ack the real bot sends, so the plugin
            // timed out into v2-compat against a perfectly good v3 bot, on every connection, while
            // the bot believed it had negotiated v3. Cross-socket delivery is already impossible
            // without it: TunnelClient's socket callbacks carry a generation and stale ones are
            // inert (departure D24), so the id check was a second layer built on a false premise
            // about the first.
            //
            // The deadline is cancelled AFTER this check, never before. Disarming on a stale ack
            // would leave a live handshake with no deadline at all, so a bot that then went silent
            // would wedge the connection at UNKNOWN forever.
            //
            // This branch is also what makes a repeat ack inert: the id is cleared the moment the
            // first one is accepted, so a second on the same socket lands here.
            logger.debug("ignoring identify_ack that arrived outside a handshake");
            return;
        }

        cancelDeadline();
        // Cleared here, with the deadline, because the handshake is now settled whichever branch
        // below runs. Without it the guard above never fires again on a live socket, so a SECOND
        // identify_ack — a bot re-sending one, a replayed frame — is fully reprocessed: it resets
        // acceptedCapabilities and configVersion, re-logs the negotiation, and an `accepted: false`
        // in it would demote a working V3 link to V2_COMPAT for the rest of the connection. The
        // guard's own comment already claimed this was handled; now it is.
        identifyId = null;

        Payload payload = envelope.payload();

        // `accepted` is the LIST of capabilities the bot will honour, in the client's own spelling —
        // not a yes/no. There is no refusal frame in the protocol at all: a capability the bot does
        // not support is simply absent, and an empty list is a successful handshake with a bot that
        // recognised none of what this build declared.
        //
        // The boolean is still read, first and only to detect an explicit refusal, for a bot that
        // answers the older shape. Reading it as the primary signal is what an earlier draft did,
        // and against the real bot `bool("accepted", false)` over a JSON array returns the fallback
        // — so every connection logged "the bot refused this plugin's protocol version" and dropped
        // to v2-compat.
        Boolean legacyFlag = payload.optBool("accepted");
        if (Boolean.FALSE.equals(legacyFlag)) {
            String reason = payload.string("reason", "no reason given");
            // Severe, not warn: every pushed setting is now inert and nothing will fix itself. The
            // socket deliberately stays open — the bot keeps it open too — so v2 traffic continues.
            logger.severe("the bot refused this plugin's protocol version — running in v2 "
                    + "compatibility mode on cached config. Reason: " + reason);
            transitionTo(ProtocolMode.V2_COMPAT);
            return;
        }

        acceptedCapabilities = Collections.unmodifiableList(payload.strings("accepted"));
        reportUnacceptedCapabilities();

        configVersion = payload.intValue("configVersion", -1);
        int botProtocol = payload.intValue("protocolVersion", 0);
        logger.info("tunnel negotiated protocol v" + TunnelSettings.PROTOCOL_VERSION
                + (botProtocol > 0 ? " with a v" + botProtocol + " bot" : "")
                + " (bot config version " + configVersion + ")");
        transitionTo(ProtocolMode.V3);
    }

    /**
     * Says which declared capabilities the bot will not honour.
     *
     * <p>Worth a line because the failure it describes is otherwise perfectly silent: the module is
     * enabled, it runs, and it simply never receives configuration or traffic for the thing it
     * claimed. That is the same shape as the capability-id mismatch in departure N5, and this log is
     * how somebody notices it rather than deduces it.
     *
     * <p>Only when the bot answered with a list at all. A bot that acked with a boolean has said
     * nothing about individual capabilities, and inventing a warning out of its silence would name
     * every capability this build has.
     */
    private void reportUnacceptedCapabilities() {
        if (capabilitySource == null || acceptedCapabilities.isEmpty()) {
            return;
        }
        Set<String> declared;
        try {
            declared = capabilitySource.capabilities();
        } catch (RuntimeException unavailable) {
            return;
        }
        List<String> unaccepted = new ArrayList<String>();
        for (String capability : declared) {
            if (!acceptedCapabilities.contains(capability)) {
                unaccepted.add(capability);
            }
        }
        if (!unaccepted.isEmpty()) {
            logger.warn("the bot does not support " + unaccepted + " — those modules will run but "
                    + "will never be configured or driven by it");
        }
    }

    private void handleConfigPush(Envelope envelope) {
        Payload document = envelope.payload();
        int version = document.intValue("version", -1);
        try {
            configHandler.onConfigPush(document);
        } catch (RuntimeException e) {
            // Still acked below. A config the plugin could not apply is a plugin problem; leaving
            // the bot to conclude the push was lost and re-send it forever makes it everyone's.
            logger.error("failed to apply pushed config version " + version, e);
        }
        configVersion = version;
        sender.sendFrame(Envelope.of(
                envelope.id(), "config.ack", Payload.builder().put("version", version).build()));
    }

    /**
     * The {@code identify} payload: v2's metadata plus the three v3 fields.
     *
     * <p>The v2 fields are sent unchanged and in the same shape, because a v2 bot receiving this
     * message must still be able to read every one of them — the v3 additions are exactly that,
     * additions.
     */
    private Payload buildIdentifyPayload(TunnelSettings settings) {
        ServerIdentity identity = identity();
        Set<String> capabilities = capabilities();

        Payload.Builder payload = Payload.builder()
                // Platform-supplied extras go in first so a named field below always wins a
                // collision. A platform that decides to send its own "platform" key should not be
                // able to disagree with the one core derived.
                .putAll(identity.extra())
                .put("serverId", settings.serverId())
                .put("serverName", identity.serverName().isEmpty()
                        ? settings.serverId() : identity.serverName())
                .put("pluginVersion", BuildConstants.VERSION)
                .put("platform", identity.platform())
                .put("serverSoftware", identity.serverSoftware())
                .put("mcVersion", identity.mcVersion())
                .put("startedAt", identity.startedAtMs())
                // ── v3 ──────────────────────────────────────────────────────────────────
                .put("protocolVersion", TunnelSettings.PROTOCOL_VERSION)
                .putStrings("capabilities", capabilities)
                .put("role", identity.role().wireName());
        return payload.build();
    }

    private ServerIdentity identity() {
        if (identitySource == null) {
            return ServerIdentity.builder().build();
        }
        try {
            ServerIdentity identity = identitySource.identity();
            return identity == null ? ServerIdentity.builder().build() : identity;
        } catch (RuntimeException e) {
            // An unidentified server on the dashboard beats a server that cannot connect.
            logger.error("identity source threw; identifying with empty metadata", e);
            return ServerIdentity.builder().build();
        }
    }

    private Set<String> capabilities() {
        if (capabilitySource == null) {
            return Collections.emptySet();
        }
        try {
            Set<String> capabilities = capabilitySource.capabilities();
            return capabilities == null ? Collections.<String>emptySet() : capabilities;
        } catch (RuntimeException e) {
            logger.error("capability source threw; declaring none", e);
            return Collections.emptySet();
        }
    }

    private void armDeadline(long timeoutMs) {
        cancelDeadline();
        try {
            deadline = wsScheduler.schedule(new Runnable() {
                @Override
                public void run() {
                    // The check and the transition must happen together, under the lock. Reading
                    // the mode here and transitioning afterwards leaves a window in which an ack
                    // arriving in between is overwritten — and because a mode is negotiated once
                    // per connection, that demotes a perfectly good v3 link to v2 compatibility for
                    // the entire life of the socket, on cached config, with nothing to say why.
                    if (transitionIfUnknown(ProtocolMode.V2_COMPAT)) {
                        logger.info("no identify_ack within the negotiation window — this bot "
                                + "speaks v2; using cached configuration");
                    }
                }
            }, Math.max(1L, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            // Shutting down; there is nothing left to negotiate with.
            logger.debug("negotiation deadline not armed: the tunnel scheduler is shutting down");
        }
    }

    private void cancelDeadline() {
        ScheduledFuture<?> armed = deadline;
        deadline = null;
        if (armed != null) {
            armed.cancel(false);
        }
    }

    /**
     * Transitions only if the handshake is still undecided — the deadline's compare-and-set.
     *
     * <p>Exists so the deadline's "has an ack arrived yet?" test and its transition are one atomic
     * step. They are the two ends of a genuine race: the ack arrives on the socket's reading thread
     * and the deadline fires on {@code heimdall-ws}, and the loser must lose completely.
     *
     * @return whether this call was the one that moved the mode, so the caller can log only when
     *     something actually happened rather than describing a transition that lost the race
     */
    private synchronized boolean transitionIfUnknown(ProtocolMode next) {
        if (mode != ProtocolMode.UNKNOWN) {
            return false;
        }
        transitionTo(next);
        return true;
    }

    /**
     * Moves to a new mode and notifies listeners, exactly once per real change.
     *
     * <p>Synchronized because the two things that decide the mode genuinely race: the ack arriving
     * on the socket thread and the deadline firing on {@code heimdall-ws}. Without it both can
     * observe {@code UNKNOWN}, both transition, and listeners see the connection become v3 and then
     * v2 for no reason an operator could ever explain.
     */
    private synchronized void transitionTo(ProtocolMode next) {
        ProtocolMode previous = mode;
        if (previous == next) {
            return;
        }
        mode = next;
        for (ProtocolModeListener listener : listeners) {
            try {
                listener.onModeChanged(previous, next);
            } catch (RuntimeException e) {
                logger.error("protocol-mode listener threw", e);
            }
        }
    }
}
