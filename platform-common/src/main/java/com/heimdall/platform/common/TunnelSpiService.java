package com.heimdall.platform.common;

import com.heimdall.core.BuildConstants;
import com.heimdall.core.json.Envelope;
import com.heimdall.core.json.Payload;
import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.tunnel.TunnelBus;
import com.heimdall.core.tunnel.TunnelClient;
import com.heimdall.core.tunnel.TunnelMessageHandler;
import com.heimdall.core.util.Registration;
import com.heimdall.core.wiring.HeimdallRuntime;
import com.heimdall.shell.contract.ShellContext;
import com.heimdall.shell.contract.TunnelBackend;
import java.util.concurrent.CompletableFuture;

/**
 * This core generation's tunnel, offered to the shell as the backend of the public
 * {@code HeimdallTunnel} SPI.
 *
 * <h2>What moved to the shell, and why</h2>
 *
 * <p>Before the hot-swap split this class <em>was</em> the {@code HeimdallTunnel} other plugins
 * held, published through {@code HeimdallTunnelProvider} and the {@code ServicesManager}, with the
 * third-party subscriptions in a map here. Every one of those references outlives a core now: a
 * plugin that cached the tunnel would keep publishing into a stopped generation and would pin its
 * classloader. So the published object, the subscriptions and the correlated-reply rule all live in
 * the shell ({@code ShellTunnel}), and this class only answers the five questions the shell asks of
 * whichever core is running (departure D87).
 *
 * <h2>Only unclaimed types reach the SPI</h2>
 *
 * <p>{@link #inbound(ShellContext)} is installed as the tunnel's <em>unhandled</em> handler, so a
 * frame is offered to third-party subscribers only when no Heimdall module subscribed to its type.
 * A third-party plugin therefore cannot shadow the whitelist or role-sync protocol by claiming its
 * message type: the SPI is an extension point, not an override point.
 *
 * <h2>Not configured is still a usable backend</h2>
 *
 * <p>A server with no {@code bootstrap.yml} has a tunnel that is idle rather than absent, and since
 * 1e the {@code TunnelClient} is one object for the life of the runtime, reconfigured when setup
 * lands (departure D56). So capturing it here at install is safe across a {@code /hd setup}, and an
 * idle tunnel gives a consumer the same answers it gets during a reconnect: disconnected, publishes
 * dropped, requests failed fast.
 */
public final class TunnelSpiService implements TunnelBackend {

    private final HeimdallLogger logger;

    /** The one tunnel this generation has. Never {@code null} since 1e. */
    private final TunnelBus bus;

    TunnelSpiService(HeimdallLogger logger, TunnelBus bus) {
        this.logger = logger;
        this.bus = bus;
    }

    /**
     * Wires the runtime's tunnel to the shell: unclaimed inbound frames go to the SPI's subscribers,
     * and the shell's {@code HeimdallTunnel} forwards to this generation.
     *
     * @return a handle that undoes both; close it before the runtime
     */
    public static Registration install(
            HeimdallLogger logger, HeimdallRuntime runtime, ShellContext shell) {
        final TunnelClient tunnel = runtime.tunnel();
        TunnelSpiService service = new TunnelSpiService(logger, tunnel);
        tunnel.setUnhandledHandler(service.inbound(shell));
        final Registration bound = shell.bindTunnel(service);
        return Registration.once(new Runnable() {
            @Override
            public void run() {
                bound.close();
                tunnel.setUnhandledHandler(null);
            }
        });
    }

    /** The handler to install as the tunnel's unhandled-message sink. */
    TunnelMessageHandler inbound(final ShellContext shell) {
        return new TunnelMessageHandler() {
            @Override
            public void onMessage(Envelope envelope) {
                boolean taken = shell.deliverUnclaimed(
                        envelope.id(), envelope.type(), envelope.payload());
                if (!taken) {
                    logger.debug(() -> "no SPI handler for inbound '" + envelope.type() + "'");
                }
            }
        };
    }

    // ── TunnelBackend ────────────────────────────────────────────────────────

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
        // An idle tunnel already fails a request with "tunnel is not connected", which is the same
        // answer a consumer gets mid-reconnect. One code path, one message.
        return bus.sendAndWait(type, payload == null ? Payload.empty() : payload, timeoutMs);
    }

    @Override
    public void reply(String requestId, String type, Payload payload) {
        bus.reply(requestId, type, payload == null ? Payload.empty() : payload);
    }
}
