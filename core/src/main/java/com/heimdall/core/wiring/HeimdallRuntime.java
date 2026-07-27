package com.heimdall.core.wiring;

import com.heimdall.core.concurrent.HeimdallExecutors;
import com.heimdall.core.config.BootstrapConfig;
import com.heimdall.core.config.BootstrapStore;
import com.heimdall.core.http.ApiClient;
import com.heimdall.core.http.BedrockIdentityProvider;
import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.module.ModuleEnvironment;
import com.heimdall.core.module.ModuleManager;
import com.heimdall.core.pipeline.ChatPipeline;
import com.heimdall.core.pipeline.LoginPipeline;
import com.heimdall.core.platform.PlatformFacade;
import com.heimdall.core.remoteconfig.ConfigDocument;
import com.heimdall.core.remoteconfig.RemoteConfig;
import com.heimdall.core.tunnel.HealthSnapshotSource;
import com.heimdall.core.tunnel.IdentitySource;
import com.heimdall.core.tunnel.TunnelClient;
import com.heimdall.core.tunnel.TunnelSettings;
import com.heimdall.core.util.Registration;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Everything the plugin is, assembled once, in an order that has to be right.
 *
 * <h2>Why this is in core rather than in each platform module</h2>
 *
 * <p>v2's two entry points were 1,086 and 1,311 lines, and they were <em>different</em> — the
 * Velocity one had fixes the Paper one never got, in code that was meant to do the same thing. Wiring
 * is exactly the kind of work where duplication is invisible: both files compile, both boot, and the
 * divergence only shows up as a bug that reproduces on one platform.
 *
 * <p>None of the assembly needs a server. It needs a {@link PlatformFacade}, which is the seam that
 * exists precisely so this code does not. So it lives here, is written once, and the platform
 * entry points shrink to "build a facade, hand it over, start".
 *
 * <h2>Not configured is a first-class state</h2>
 *
 * <p>A fresh install has no {@code bootstrap.yml}. That is the normal case, not a failure: the
 * plugin still enables, still registers its commands, and still loads its modules on their defaults
 * — it simply does not dial the bot, and says so once. Anything else means a server operator who
 * dropped the jar in gets a stack trace instead of an instruction.
 *
 * <p>{@link #tunnel()} and {@link #api()} are therefore {@code null} in that state, and everything
 * that could reach for them is either absent or given the offline substitute the module system
 * already provides.
 *
 * <h2>Construction and start are separate</h2>
 *
 * <p>{@link Builder#build()} assembles; {@link #start()} sets things in motion. The gap is where
 * modules get registered — {@link #modules()} is live before anything is running, so a platform can
 * register its module set without the manager having to accept a registry it did not build.
 *
 * <h2>Shutdown order is the reverse, and the executors go last</h2>
 *
 * <p>Modules stop, then the tunnel, then the pools. Shutting the pools down first would strand every
 * in-flight disable on a rejected task, and the symptom — a mirror that did not flush — appears one
 * boot later as data loss.
 */
public final class HeimdallRuntime implements AutoCloseable {

    private final HeimdallLogger logger;
    private final PlatformFacade platform;
    private final BootstrapStore bootstrapStore;
    private final BootstrapConfig bootstrap;
    private final String guildId;

    private final HeimdallExecutors executors;
    private final RemoteConfig remoteConfig;
    private final LoginPipeline loginPipeline;
    private final ChatPipeline chatPipeline;
    private final ModuleManager modules;

    /** {@code null} when this server has not been set up yet. */
    private final ApiClient api;

    /** {@code null} when this server has not been set up yet. */
    private final TunnelClient tunnel;

    /** Everything {@link #start()} registered, closed in reverse on the way out. */
    private final List<Registration> registrations = new ArrayList<Registration>();

    private boolean started;
    private boolean closed;

    private HeimdallRuntime(Builder builder) {
        this.logger = builder.logger;
        this.platform = builder.platform;
        this.bootstrapStore = builder.bootstrapStore;
        this.bootstrap = bootstrapStore.load();
        this.guildId = builder.guildId == null ? "" : builder.guildId.trim();

        logger.setDebugEnabled(bootstrap.debug());

        this.executors = new HeimdallExecutors(logger);
        this.loginPipeline = new LoginPipeline(logger);
        this.chatPipeline = new ChatPipeline(logger);

        Path cachePath = platform.dataDirectory().resolve("config-cache.json");
        this.remoteConfig = new RemoteConfig(logger, cachePath, ConfigDocument.empty());

        this.api = bootstrap.isConfigured() ? buildApiClient(builder) : null;
        this.tunnel = bootstrap.isConfigured() ? buildTunnel(builder) : null;

        this.modules = new ModuleManager(ModuleEnvironment.builder()
                .logger(logger)
                .executors(executors)
                .tunnel(tunnel)
                .remoteConfig(remoteConfig)
                .loginPipeline(loginPipeline)
                .chatPipeline(chatPipeline)
                .platform(platform)
                .build());

        if (tunnel != null) {
            // Set after the manager exists: the dependency genuinely runs both ways — the manager
            // hands each module a bus backed by the client, and the client asks the manager what to
            // declare. See CapabilitySource.
            tunnel.setCapabilitySource(modules);
        }
    }

    public static Builder builder(HeimdallLogger logger, PlatformFacade platform) {
        return new Builder(logger, platform);
    }

    private ApiClient buildApiClient(Builder builder) {
        ApiClient client = new ApiClient(
                logger,
                ApiSettingsFactory.fromBootstrap(bootstrap, guildId).build(),
                executors.io());
        if (builder.bedrockIdentityProvider != null) {
            client.setBedrockIdentityProvider(builder.bedrockIdentityProvider);
        }
        return client;
    }

    private TunnelClient buildTunnel(Builder builder) {
        TunnelSettings settings = TunnelSettings.builder()
                .endpoint(bootstrap.endpoint())
                .guildId(guildId)
                .serverId(bootstrap.serverId())
                .apiKey(bootstrap.token())
                .build();
        return TunnelClient.builder(logger, executors)
                .settings(settings)
                .identitySource(builder.identitySource)
                .healthSource(builder.healthSource)
                .configPushHandler(remoteConfig)
                .build();
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    /**
     * Loads cached configuration, starts the modules, and dials the bot if there is one to dial.
     *
     * <p>Idempotent: a second call does nothing. Never throws for a configuration problem — the
     * whole point of the not-configured state is that the plugin boots far enough to explain
     * itself.
     */
    public void start() {
        if (started || closed) {
            return;
        }
        started = true;

        // Before anything else: the plugin should be configured from its first moment rather than
        // from whenever the bot answers, and a module enabled below reads its settings immediately.
        remoteConfig.loadFromCache();

        if (tunnel != null) {
            registrations.add(tunnel.onModeChange(remoteConfig));
        }
        registrations.add(modules.followRemoteConfig());
        modules.reconcileFromConfig();

        if (tunnel == null) {
            logger.info("not set up yet — run /hd setup <code> to connect this server to Discord "
                    + "(see " + bootstrapStore.file() + ")");
            return;
        }
        if (!tunnel.settings().isConfigured()) {
            // Configured enough to sign HTTP requests, but the tunnel URL is keyed by guild and
            // nothing has resolved one yet. Deliberately not a warning: it is the state every
            // server is in until the setup flow (phase 1e) fills it in, and the HTTP client works
            // regardless.
            logger.info("tunnel idle: this server has credentials but no guild id yet");
            return;
        }
        tunnel.connect();
    }

    /**
     * Stops everything, in reverse.
     *
     * <p>Idempotent, and every step is contained: one failure on the way out must not skip the
     * steps after it, because the last of them is the one that stops the threads.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;

        guarded("stopping modules", new Runnable() {
            @Override
            public void run() {
                modules.shutdown();
            }
        });

        Collections.reverse(registrations);
        for (final Registration registration : registrations) {
            guarded("closing a runtime registration", new Runnable() {
                @Override
                public void run() {
                    registration.close();
                }
            });
        }
        registrations.clear();

        if (tunnel != null) {
            guarded("shutting the tunnel down", new Runnable() {
                @Override
                public void run() {
                    tunnel.shutdown();
                }
            });
        }

        // Last, and bounded: everything above may have scheduled its final work here.
        guarded("shutting the executors down", new Runnable() {
            @Override
            public void run() {
                executors.shutdown();
            }
        });
    }

    private void guarded(String what, Runnable step) {
        try {
            step.run();
        } catch (RuntimeException e) {
            logger.error(what + " failed; continuing with the rest of shutdown", e);
        }
    }

    // ── Accessors ────────────────────────────────────────────────────────────

    /** Whether {@code bootstrap.yml} carries enough to talk to the bot at all. */
    public boolean isConfigured() {
        return bootstrap.isConfigured();
    }

    /** What was on disk at construction. Immutable; re-reading is a restart or a reload. */
    public BootstrapConfig bootstrap() {
        return bootstrap;
    }

    /** The store the bootstrap came from, so the setup flow can write to the same file. */
    public BootstrapStore bootstrapStore() {
        return bootstrapStore;
    }

    /** The shared pools. Borrowed by everything, shut down only by {@link #close()}. */
    public HeimdallExecutors executors() {
        return executors;
    }

    public RemoteConfig remoteConfig() {
        return remoteConfig;
    }

    public LoginPipeline loginPipeline() {
        return loginPipeline;
    }

    public ChatPipeline chatPipeline() {
        return chatPipeline;
    }

    /** Live from construction, so a platform can register its modules before {@link #start()}. */
    public ModuleManager modules() {
        return modules;
    }

    /** The HTTP client, or {@code null} on a server that has not been set up. */
    public ApiClient api() {
        return api;
    }

    /** The tunnel, or {@code null} on a server that has not been set up. */
    public TunnelClient tunnel() {
        return tunnel;
    }

    /** The mutable writer. Only the logger, the platform and the bootstrap store are required. */
    public static final class Builder {

        private final HeimdallLogger logger;
        private final PlatformFacade platform;

        private BootstrapStore bootstrapStore;
        private String guildId = "";
        private IdentitySource identitySource;
        private HealthSnapshotSource healthSource;
        private BedrockIdentityProvider bedrockIdentityProvider;

        private Builder(HeimdallLogger logger, PlatformFacade platform) {
            if (logger == null || platform == null) {
                throw new IllegalArgumentException("logger and platform are required");
            }
            this.logger = logger;
            this.platform = platform;
        }

        /** Where {@code bootstrap.yml} lives. Defaults to one under the platform's data directory. */
        public Builder bootstrapStore(BootstrapStore value) {
            this.bootstrapStore = value;
            return this;
        }

        /**
         * The guild this server belongs to.
         *
         * <p>Not a bootstrap field: a server can be configured with a token alone and resolve its
         * guild from the bot, which is what the setup flow does in phase 1e. Until then this is
         * empty and the tunnel stays idle while the HTTP client works.
         */
        public Builder guildId(String value) {
            this.guildId = value;
            return this;
        }

        public Builder identitySource(IdentitySource value) {
            this.identitySource = value;
            return this;
        }

        public Builder healthSource(HealthSnapshotSource value) {
            this.healthSource = value;
            return this;
        }

        public Builder bedrockIdentityProvider(BedrockIdentityProvider value) {
            this.bedrockIdentityProvider = value;
            return this;
        }

        public HeimdallRuntime build() {
            if (bootstrapStore == null) {
                bootstrapStore =
                        new BootstrapStore(logger, platform.dataDirectory().resolve("bootstrap.yml"));
            }
            return new HeimdallRuntime(this);
        }
    }
}
