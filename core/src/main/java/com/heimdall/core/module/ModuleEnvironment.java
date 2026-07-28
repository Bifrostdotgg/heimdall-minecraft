package com.heimdall.core.module;

import com.heimdall.core.concurrent.HeimdallExecutors;
import com.heimdall.core.http.ApiClient;
import com.heimdall.core.http.ApiSettings;
import com.heimdall.core.http.HeimdallApi;
import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.pipeline.ChatPipeline;
import com.heimdall.core.pipeline.LoginPipeline;
import com.heimdall.core.platform.PlatformFacade;
import com.heimdall.core.remoteconfig.RemoteConfig;
import com.heimdall.core.session.PlayerSessionEvents;
import com.heimdall.core.tunnel.TunnelBus;

/**
 * The collaborators every module shares, gathered once.
 *
 * <p>Exists so {@code ModuleManager} and {@code ModuleContext} do not each grow a seven-argument
 * constructor that has to be edited in two places every time the set changes — and so a test can
 * assemble a partial one (a fake bus, a real pipeline, no platform) without a factory.
 *
 * <p>Immutable. Everything in it is borrowed: the executors and the pipelines belong to whoever
 * built them, and nothing here is shut down by the module system.
 */
public final class ModuleEnvironment {

    private final HeimdallLogger logger;
    private final HeimdallExecutors executors;
    private final HeimdallApi api;
    private final TunnelBus tunnel;
    private final RemoteConfig remoteConfig;
    private final LoginPipeline loginPipeline;
    private final ChatPipeline chatPipeline;
    private final PlatformFacade platform;
    private final PlayerSessionEvents playerSessions;

    private ModuleEnvironment(Builder builder) {
        if (builder.logger == null || builder.executors == null || builder.platform == null) {
            throw new IllegalArgumentException("logger, executors and platform are required");
        }
        if (builder.remoteConfig == null) {
            throw new IllegalArgumentException("remoteConfig is required");
        }
        if (builder.loginPipeline == null || builder.chatPipeline == null) {
            throw new IllegalArgumentException("both pipelines are required");
        }
        this.logger = builder.logger;
        this.executors = builder.executors;
        // Defaulted rather than required, and to a real gateway over an unconfigured client rather
        // than to null. A test assembling a partial environment gets one that answers
        // NOT_CONFIGURED — which is a state production really has — instead of a null that would
        // put a guard clause in every module that talks to the bot.
        this.api = builder.api == null
                ? new HeimdallApi(new ApiClient(
                        builder.logger, ApiSettings.builder().build(), builder.executors.io()))
                : builder.api;
        this.tunnel = builder.tunnel;
        this.remoteConfig = builder.remoteConfig;
        this.loginPipeline = builder.loginPipeline;
        this.chatPipeline = builder.chatPipeline;
        this.platform = builder.platform;
        // Defaulted rather than required. Every caller that has one passes it; a test assembling a
        // partial environment should not have to build a dispatcher it will never push an event
        // into, and a null here would put a null check in every module that subscribes.
        this.playerSessions = builder.playerSessions == null
                ? new PlayerSessionEvents(builder.logger, builder.executors.io())
                : builder.playerSessions;
    }

    public static Builder builder() {
        return new Builder();
    }

    public HeimdallLogger logger() {
        return logger;
    }

    public HeimdallExecutors executors() {
        return executors;
    }

    /**
     * The bot's HTTP API, as the gateway modules see it.
     *
     * <p>Never {@code null}: an unset one is built over a client with no configuration, which
     * answers {@link HeimdallApi.Availability#NOT_CONFIGURED} to everything. That is a state a real
     * server is in on its first boot, so a module exercised against it is exercised against
     * something honest rather than against a null check.
     */
    public HeimdallApi api() {
        return api;
    }

    /**
     * The tunnel.
     *
     * <p>May be {@code null} — a server that has not been set up yet has no tunnel, and modules
     * still have to be able to load so the setup command can explain that. Modules see a
     * non-null bus regardless; {@link ModuleContext#tunnel()} substitutes a no-op one.
     */
    public TunnelBus tunnel() {
        return tunnel;
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

    public PlatformFacade platform() {
        return platform;
    }

    /**
     * Join and quit notifications, as the platform pushes them in.
     *
     * <p>Never {@code null}: an unset one is built from the logger and the IO pool, so a module can
     * always subscribe even in an environment nothing will ever push an event into.
     */
    public PlayerSessionEvents playerSessions() {
        return playerSessions;
    }

    /** The mutable writer. */
    public static final class Builder {

        private HeimdallLogger logger;
        private HeimdallExecutors executors;
        private HeimdallApi api;
        private TunnelBus tunnel;
        private RemoteConfig remoteConfig;
        private LoginPipeline loginPipeline;
        private ChatPipeline chatPipeline;
        private PlatformFacade platform;
        private PlayerSessionEvents playerSessions;

        private Builder() {
        }

        public Builder logger(HeimdallLogger value) {
            this.logger = value;
            return this;
        }

        public Builder executors(HeimdallExecutors value) {
            this.executors = value;
            return this;
        }

        /** The API gateway. Left unset, one over an unconfigured client is built. */
        public Builder api(HeimdallApi value) {
            this.api = value;
            return this;
        }

        public Builder tunnel(TunnelBus value) {
            this.tunnel = value;
            return this;
        }

        public Builder remoteConfig(RemoteConfig value) {
            this.remoteConfig = value;
            return this;
        }

        public Builder loginPipeline(LoginPipeline value) {
            this.loginPipeline = value;
            return this;
        }

        public Builder chatPipeline(ChatPipeline value) {
            this.chatPipeline = value;
            return this;
        }

        public Builder platform(PlatformFacade value) {
            this.platform = value;
            return this;
        }

        /** The dispatcher the platform adapters push into. Left unset, one is built. */
        public Builder playerSessions(PlayerSessionEvents value) {
            this.playerSessions = value;
            return this;
        }

        public ModuleEnvironment build() {
            return new ModuleEnvironment(this);
        }
    }
}
