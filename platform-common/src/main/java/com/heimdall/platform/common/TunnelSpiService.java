package com.heimdall.platform.common;

import com.heimdall.api.HeimdallTunnel;
import com.heimdall.api.HeimdallTunnelProvider;
import com.heimdall.core.BuildConstants;
import com.heimdall.core.json.Envelope;
import com.heimdall.core.json.Payload;
import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.tunnel.TunnelBus;
import com.heimdall.core.tunnel.TunnelClient;
import com.heimdall.core.tunnel.TunnelMessageHandler;
import com.heimdall.core.util.Registration;
import com.heimdall.core.wiring.HeimdallRuntime;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The {@link HeimdallTunnel} SPI, backed by the real tunnel.
 *
 * <p>Thin by design: everything except inbound dispatch is a one-line delegation to
 * {@link TunnelBus}. What it adds is the piece a third-party plugin cannot build for itself —
 * <strong>correlated replies</strong>. A handler is given a responder that echoes the request's own
 * id back as <code>&lt;type&gt;.result</code>, which is what the bot has a future waiting on. A
 * plugin that answered with a fresh id would produce a reply the bot files as unsolicited and a
 * request that times out, and nothing in either log would say why.
 *
 * <h2>Only unclaimed types reach here</h2>
 *
 * <p>{@link #inbound()} is installed as the tunnel's <em>unhandled</em> handler, so the SPI sees a
 * frame only when no Heimdall module subscribed to that type. A third-party plugin therefore cannot
 * shadow the whitelist or role-sync protocol by claiming its message type, deliberately: the SPI is
 * an extension point, not an override point.
 *
 * <h2>Not configured is still a usable SPI, and it heals by itself</h2>
 *
 * <p>A server with no {@code bootstrap.yml} has a tunnel that is idle rather than absent, so this
 * captures a real bus in every state. {@link #isConnected()} is false while it is idle,
 * {@link #publish} drops and {@link #request} fails fast — the same behaviour a consumer already
 * sees during a reconnect, so nobody has to write a second code path for "Heimdall exists but is not
 * set up".
 *
 * <p><strong>This used to be permanent, and that was the 1c TODO known as N7.</strong> The bus was
 * captured at enable and was {@code null} on an unconfigured server, so a server claimed with
 * {@code /hd setup} kept an SPI wired to nothing until it restarted — a third-party plugin would
 * have seen a Heimdall that was demonstrably connected and an SPI that dropped everything.
 *
 * <p>It is closed without a re-install, and deliberately so: 1e made the {@code TunnelClient} itself
 * stable, built on every boot and <em>reconfigured</em> when setup lands rather than constructed
 * then (departure D56 and {@code HeimdallRuntime}'s class javadoc). One object for the life of the
 * plugin means one capture, and nothing here has to know that a setup happened. Re-installing the
 * service would have worked too and would have needed the runtime to know about this class.
 */
public final class TunnelSpiService implements HeimdallTunnel {

    /** How a reply type is derived from the request type, matching v2. */
    private static final String RESULT_SUFFIX = ".result";

    private final HeimdallLogger logger;

    /**
     * The one tunnel this plugin has, for its whole life.
     *
     * <p>Never {@code null} since 1e — an unconfigured server has an idle tunnel, not no tunnel —
     * which is what makes capturing it here safe across a {@code /hd setup}.
     */
    private final TunnelBus bus;

    private final Map<String, InboundHandler> handlers =
            new ConcurrentHashMap<String, InboundHandler>();

    private TunnelSpiService(HeimdallLogger logger, TunnelBus bus) {
        this.logger = logger;
        this.bus = bus;
    }

    /**
     * Builds the service, wires it to the runtime's tunnel and publishes it.
     *
     * <p>One call from each platform entry point, because both do exactly the same thing and the
     * only difference — the Bukkit {@code ServicesManager} registration — is something only the
     * Bukkit module can do and does alongside this.
     *
     * @return the installed service; hand it to {@link #uninstall} on disable
     */
    public static TunnelSpiService install(HeimdallLogger logger, HeimdallRuntime runtime) {
        TunnelClient tunnel = runtime.tunnel();
        TunnelSpiService service = new TunnelSpiService(logger, tunnel);
        tunnel.setUnhandledHandler(service.inbound());
        HeimdallTunnelProvider.install(service);
        return service;
    }

    /** Removes the service from the global holder, if it is still the installed one. */
    public static void uninstall(TunnelSpiService service) {
        if (service != null) {
            service.handlers.clear();
            HeimdallTunnelProvider.uninstall(service);
        }
    }

    /** The handler to install as the tunnel's unhandled-message sink. */
    public TunnelMessageHandler inbound() {
        return new TunnelMessageHandler() {
            @Override
            public void onMessage(Envelope envelope) {
                dispatch(envelope);
            }
        };
    }

    private void dispatch(final Envelope envelope) {
        InboundHandler handler = handlers.get(envelope.type());
        if (handler == null) {
            logger.debug(() -> "no SPI handler for inbound '" + envelope.type() + "'");
            return;
        }
        try {
            handler.handle(envelope.payload(), new Responder() {
                @Override
                public void respond(Payload payload) {
                    reply(envelope, payload);
                }
            });
        } catch (RuntimeException broken) {
            // One misbehaving consumer must not take the tunnel's dispatch loop with it. The bot's
            // request will time out, which is the honest outcome — inventing an error reply on the
            // plugin's behalf would tell the dashboard something the plugin never said.
            logger.error("a HeimdallTunnel consumer threw handling '" + envelope.type() + "'", broken);
        }
    }

    private void reply(Envelope request, Payload payload) {
        bus.reply(
                request.id(),
                request.type() + RESULT_SUFFIX,
                payload == null ? Payload.empty() : payload);
    }

    // ── HeimdallTunnel ───────────────────────────────────────────────────────

    @Override
    public String version() {
        return BuildConstants.VERSION;
    }

    @Override
    public boolean isConnected() {
        return bus.isConnected();
    }

    @Override
    public void publish(String type, Payload payload) {
        bus.send(type, payload == null ? Payload.empty() : payload);
    }

    @Override
    public CompletableFuture<Payload> request(String type, Payload payload, long timeoutMs) {
        // No null branch any more: an idle tunnel already fails a request with "tunnel is not
        // connected", which is the same answer a consumer gets mid-reconnect and the same one they
        // got here before. One code path, one message.
        return bus.sendAndWait(type, payload == null ? Payload.empty() : payload, timeoutMs);
    }

    @Override
    public Registration on(final String type, InboundHandler handler) {
        if (type == null || type.isEmpty() || handler == null) {
            return Registration.NONE;
        }
        final InboundHandler previous = handlers.put(type, handler);
        if (previous != null) {
            logger.warn("a second plugin subscribed to inbound '" + type + "' — the earlier "
                    + "handler has been replaced");
        }
        final InboundHandler registered = handler;
        return Registration.once(new Runnable() {
            @Override
            public void run() {
                // Remove only if it is still ours: a later subscriber has taken over otherwise, and
                // clearing the map blindly would silently unsubscribe them.
                handlers.remove(type, registered);
            }
        });
    }
}
