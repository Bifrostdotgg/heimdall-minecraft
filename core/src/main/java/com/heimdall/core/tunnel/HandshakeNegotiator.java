package com.heimdall.core.tunnel;

import com.heimdall.core.BuildConstants;
import com.heimdall.core.json.Envelope;
import com.heimdall.core.json.Payload;
import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.util.Registration;
import java.util.Collections;
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
        cancelDeadline();

        String expected = identifyId;
        if (expected != null && !expected.equals(envelope.id())) {
            // The bot echoes the identify's id. A mismatch means this ack belongs to a handshake
            // from a previous socket, arriving late — accepting it would let a stale frame decide
            // the mode of a connection it was never part of.
            logger.debug("ignoring identify_ack for a stale handshake id " + envelope.id());
            return;
        }

        boolean accepted = envelope.payload().bool("accepted", false);
        if (!accepted) {
            String reason = envelope.payload().string("reason", "no reason given");
            // Severe, not warn: the plugin is newer than the bot it is pointed at, every pushed
            // setting is now inert, and nothing will fix itself. The socket deliberately stays
            // open — the bot keeps it open too — so the tunnel still carries v2 traffic.
            logger.severe("the bot refused this plugin's protocol version — running in v2 "
                    + "compatibility mode on cached config. Reason: " + reason);
            transitionTo(ProtocolMode.V2_COMPAT);
            return;
        }

        configVersion = envelope.payload().intValue("configVersion", -1);
        logger.info("tunnel negotiated protocol v" + TunnelSettings.PROTOCOL_VERSION
                + " (bot config version " + configVersion + ")");
        transitionTo(ProtocolMode.V3);
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
                    if (mode == ProtocolMode.UNKNOWN) {
                        logger.info("no identify_ack within the negotiation window — this bot "
                                + "speaks v2; using cached configuration");
                        transitionTo(ProtocolMode.V2_COMPAT);
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
