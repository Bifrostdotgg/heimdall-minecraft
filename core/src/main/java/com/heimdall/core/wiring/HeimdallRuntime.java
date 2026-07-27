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
import com.heimdall.core.session.PlayerSessionEvents;
import com.heimdall.core.tunnel.HealthSnapshotSource;
import com.heimdall.core.tunnel.IdentitySource;
import com.heimdall.core.tunnel.TunnelClient;
import com.heimdall.core.tunnel.TunnelSettings;
import com.heimdall.core.util.Registration;
import com.heimdall.core.util.Strings;
import java.io.IOException;
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

    /**
     * The guild in force. Volatile because guild discovery writes it from {@code heimdall-io} while
     * whatever asked is on a server thread.
     */
    private volatile String guildId;

    private final HeimdallExecutors executors;
    private final RemoteConfig remoteConfig;
    private final LoginPipeline loginPipeline;
    private final ChatPipeline chatPipeline;
    private final PlayerSessionEvents playerSessions;
    private final ModuleManager modules;

    /** {@code null} once the guild is known, or on a server that was never set up. */
    private final GuildDiscovery guildDiscovery;

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
        // Explicit beats cached beats nothing. The cached value is what a restart during a bot
        // outage runs on — see BootstrapConfig#guildId — and is overwritten by whatever `identify`
        // next answers.
        String supplied = builder.guildId == null ? "" : builder.guildId.trim();
        this.guildId = supplied.isEmpty() ? bootstrap.guildId() : supplied;

        logger.setDebugEnabled(bootstrap.debug());

        this.executors = builder.executors == null
                ? new HeimdallExecutors(logger)
                : builder.executors;
        this.loginPipeline = new LoginPipeline(logger);
        this.chatPipeline = new ChatPipeline(logger);

        Path cachePath = platform.dataDirectory().resolve("config-cache.json");
        this.remoteConfig = new RemoteConfig(logger, cachePath, ConfigDocument.empty());

        this.api = bootstrap.isConfigured() ? buildApiClient(builder) : null;
        // Built even with no guild yet: TunnelClient carries `reconnect(guildId)` for exactly this,
        // so discovery fills the gap in place rather than the runtime having to construct a client
        // later and re-point everything that already holds a reference to the old one.
        this.tunnel = bootstrap.isConfigured() ? buildTunnel(builder) : null;
        this.guildDiscovery = needsGuildDiscovery() ? buildGuildDiscovery() : null;

        this.playerSessions = new PlayerSessionEvents(logger, executors.io());

        this.modules = new ModuleManager(ModuleEnvironment.builder()
                .logger(logger)
                .executors(executors)
                .tunnel(tunnel)
                .remoteConfig(remoteConfig)
                .loginPipeline(loginPipeline)
                .chatPipeline(chatPipeline)
                .platform(platform)
                .playerSessions(playerSessions)
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

    private boolean needsGuildDiscovery() {
        return api != null && Strings.isBlank(guildId);
    }

    private GuildDiscovery buildGuildDiscovery() {
        return new GuildDiscovery(logger, api, executors.scheduler(), new java.util.function.Consumer<String>() {
            @Override
            public void accept(String resolved) {
                adoptGuild(resolved);
            }
        });
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
        if (guildDiscovery != null) {
            // The discovering state. Everything above is already running — commands answer, modules
            // are enabled, the HTTP client has credentials — and the one thing missing is the guild
            // the tunnel URL is keyed by. Said once, here, rather than on every retry.
            logger.info("discovering which guild this server's token belongs to; the tunnel stays "
                    + "idle until it answers");
            guildDiscovery.start();
            return;
        }
        if (!tunnel.settings().isConfigured()) {
            // A guild we have, but something else the tunnel needs is missing. Not the discovering
            // state, and not something retrying would fix.
            logger.info("tunnel idle: this server has a guild but incomplete tunnel settings");
            return;
        }
        tunnel.connect();
    }

    /**
     * Adopts a freshly discovered guild: HTTP client, disk cache, then the tunnel.
     *
     * <p>Runs on {@code heimdall-io}, from {@link GuildDiscovery}'s completion.
     *
     * <p>The order is deliberate. The API client is re-pointed first because a login arriving
     * during this must use the new guild or none; the bootstrap file is written next so a restart
     * in the following second does not have to ask again; and the tunnel is dialled last, because
     * it is the only step whose failure is retried by something other than this method.
     *
     * <p>Persisting is best-effort. A read-only data directory costs one {@code identify} per boot,
     * which is a great deal better than refusing to connect.
     */
    private void adoptGuild(String resolved) {
        guildId = resolved;
        if (api != null) {
            api.reconfigure(ApiSettingsFactory.fromBootstrap(bootstrap, resolved).build());
        }
        try {
            bootstrapStore.save(bootstrap.toBuilder().guildId(resolved).build());
        } catch (IOException | RuntimeException notPersisted) {
            logger.warn("could not cache the resolved guild in " + bootstrapStore.file()
                    + "; this server will ask again on its next boot: " + notPersisted);
        }
        if (closed) {
            return;
        }
        // reconnect() rather than connect(): it accepts the guild, cancels anything the backoff has
        // armed, and works whether or not a socket exists. See TunnelClient#reconnect.
        tunnel.reconnect(resolved);
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

        if (guildDiscovery != null) {
            guarded("stopping guild discovery", new Runnable() {
                @Override
                public void run() {
                    guildDiscovery.close();
                }
            });
        }

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

    /**
     * Where the platform adapters push join and quit, and where modules subscribe.
     *
     * <p>Live from construction, like {@link #modules()}: a platform registers its listeners before
     * {@link #start()}, and a module can be enabled by the first reconcile inside it.
     */
    public PlayerSessionEvents playerSessions() {
        return playerSessions;
    }

    /**
     * The guild this server belongs to, or {@code ""} while discovery is still asking.
     *
     * <p>A status command reads this to tell "not set up" apart from "set up, still discovering",
     * which are the two states an operator confuses.
     */
    public String guildId() {
        return guildId;
    }

    /** Whether the guild is still being resolved — the state in which the tunnel stays idle. */
    public boolean isDiscoveringGuild() {
        return guildDiscovery != null && !guildDiscovery.isResolved();
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
        private HeimdallExecutors executors;
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

        /**
         * The pools to use, if the caller has already built them.
         *
         * <p><strong>Ownership transfers.</strong> {@link HeimdallRuntime#close()} shuts these down
         * like any others — this is not a "borrowed executor" hook. It exists because a platform
         * facade needs a pool at construction and the runtime needs the facade, so somebody has to
         * create them first; making that a loan instead would leave two objects each believing the
         * other would stop the threads.
         *
         * <p>Left unset, the runtime builds its own.
         */
        public Builder executors(HeimdallExecutors value) {
            this.executors = value;
            return this;
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
