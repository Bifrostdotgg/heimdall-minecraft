package com.heimdall.core.tunnel;

import com.heimdall.core.concurrent.HeimdallExecutors;
import com.heimdall.core.json.Payload;
import com.heimdall.core.log.HeimdallLogger;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.LongSupplier;

/**
 * Describes a {@link TunnelClient} before building it.
 *
 * <p>A top-level class rather than a nested one, which is a departure from the convention every
 * other builder in this codebase follows ({@code TunnelSettings}, {@code ServerIdentity},
 * {@code MirrorStore}). The reason is specific to this one: it shares none of the client's state —
 * no socket, no generation counter, no connection flag — so nesting bought no access it needed,
 * while costing ninety lines in the file that holds the reconnect logic. That file is the one place
 * in the tunnel where the density of a reader's attention matters most.
 *
 * <p>Everything has a working default except the logger and the executors. A client built with
 * nothing else set is inert rather than broken: it will decline to connect and say why.
 */
public final class TunnelClientBuilder {

    private final HeimdallLogger logger;
    private final HeimdallExecutors executors;

    private TunnelSocketFactory socketFactory;
    private TunnelSettings settings = TunnelSettings.builder().build();
    private IdentitySource identitySource;
    private CapabilitySource capabilitySource;
    private HealthSnapshotSource healthSource;
    private ScheduledExecutorService wsScheduler;

    private ConfigPushHandler configPushHandler = new ConfigPushHandler() {
        @Override
        public void onConfigPush(Payload document) {
            // No-op default, so the client is usable before RemoteConfig is wired in. The push is
            // still acknowledged by the negotiator, which is what keeps the bot from re-sending it
            // forever.
        }
    };

    private LongSupplier clock = new LongSupplier() {
        @Override
        public long getAsLong() {
            return System.currentTimeMillis();
        }
    };

    TunnelClientBuilder(HeimdallLogger logger, HeimdallExecutors executors) {
        if (logger == null || executors == null) {
            throw new IllegalArgumentException("logger and executors are required");
        }
        this.logger = logger;
        this.executors = executors;
    }

    public TunnelClientBuilder settings(TunnelSettings value) {
        this.settings = value;
        return this;
    }

    /** The socket implementation. Defaults to {@link NvTunnelSocketFactory} when left unset. */
    public TunnelClientBuilder socketFactory(TunnelSocketFactory value) {
        this.socketFactory = value;
        return this;
    }

    public TunnelClientBuilder identitySource(IdentitySource value) {
        this.identitySource = value;
        return this;
    }

    public TunnelClientBuilder capabilitySource(CapabilitySource value) {
        this.capabilitySource = value;
        return this;
    }

    public TunnelClientBuilder configPushHandler(ConfigPushHandler value) {
        if (value != null) {
            this.configPushHandler = value;
        }
        return this;
    }

    public TunnelClientBuilder healthSource(HealthSnapshotSource value) {
        this.healthSource = value;
        return this;
    }

    /**
     * Replaces the system clock used for the heartbeat's silence measurement.
     *
     * <p>For tests, which need to make thirty seconds pass without waiting for them. Nothing in
     * production should call this.
     */
    public TunnelClientBuilder clock(LongSupplier value) {
        if (value != null) {
            this.clock = value;
        }
        return this;
    }

    /**
     * Replaces the scheduler the tunnel's own timing runs on.
     *
     * <p>Package-private, and for tests only: it lets one drive the heartbeat, the backoff and the
     * negotiation deadline by hand instead of against a wall clock. That is what makes the
     * single-flight reconnect assertion able to count connection <em>attempts</em> rather than
     * infer them from a backoff delay — an inference that quietly depended on the ratio between the
     * base delay and the ceiling, and would have died under any future jitter.
     *
     * <p>Production always gets {@link HeimdallExecutors#ws()}.
     */
    TunnelClientBuilder wsScheduler(ScheduledExecutorService value) {
        this.wsScheduler = value;
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

    // ── Read back by TunnelClient's constructor ──────────────────────────────

    HeimdallLogger logger() {
        return logger;
    }

    HeimdallExecutors executors() {
        return executors;
    }

    ScheduledExecutorService wsScheduler() {
        return wsScheduler == null ? executors.ws() : wsScheduler;
    }

    TunnelSocketFactory socketFactory() {
        return socketFactory;
    }

    TunnelSettings settings() {
        return settings;
    }

    IdentitySource identitySource() {
        return identitySource;
    }

    CapabilitySource capabilitySource() {
        return capabilitySource;
    }

    HealthSnapshotSource healthSource() {
        return healthSource;
    }

    ConfigPushHandler configPushHandler() {
        return configPushHandler;
    }

    LongSupplier clock() {
        return clock;
    }
}
