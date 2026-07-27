package com.heimdall.core.module;

import com.heimdall.core.command.CommandSpec;
import com.heimdall.core.concurrent.HeimdallExecutors;
import com.heimdall.core.json.Payload;
import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.log.PrefixedLogger;
import com.heimdall.core.mirror.MirrorPolicy;
import com.heimdall.core.mirror.MirrorStore;
import com.heimdall.core.pipeline.ChatMessage;
import com.heimdall.core.pipeline.ChatObserver;
import com.heimdall.core.pipeline.Interceptor;
import com.heimdall.core.pipeline.LoginAttempt;
import com.heimdall.core.platform.PlatformFacade;
import com.heimdall.core.remoteconfig.ModuleConfig;
import com.heimdall.core.remoteconfig.ModuleConfigListener;
import com.heimdall.core.session.PlayerSessionListener;
import com.heimdall.core.tunnel.ProtocolMode;
import com.heimdall.core.tunnel.TunnelBus;
import com.heimdall.core.tunnel.TunnelMessageHandler;
import com.heimdall.core.util.Registration;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * The real {@link ModuleContext}: every registry call routed through one module's tracking bag.
 *
 * <p>There is deliberately nothing clever here. Each method delegates to the shared collaborator and
 * wraps the returned handle in {@link TrackedRegistrations#track}, so the tracking cannot be
 * forgotten for a new registry by anything short of adding a method that skips it — which is a
 * visible thing to do in review, unlike forgetting to unregister in a module's {@code disable()}.
 */
final class ModuleContextImpl implements ModuleContext {

    private final String moduleId;
    private final ModuleEnvironment environment;
    private final TrackedRegistrations registrations;
    private final HeimdallLogger logger;
    private final TunnelBus trackedBus;

    ModuleContextImpl(
            String moduleId, ModuleEnvironment environment, TrackedRegistrations registrations) {
        this.moduleId = moduleId;
        this.environment = environment;
        this.registrations = registrations;
        this.logger = new PrefixedLogger(environment.logger(), moduleId);
        TunnelBus delegate = environment.tunnel() == null
                ? OfflineTunnelBus.INSTANCE
                : environment.tunnel();
        this.trackedBus = new TrackingTunnelBus(delegate);
    }

    @Override
    public String moduleId() {
        return moduleId;
    }

    @Override
    public TunnelBus tunnel() {
        return trackedBus;
    }

    @Override
    public HeimdallLogger logger() {
        return logger;
    }

    @Override
    public HeimdallExecutors executors() {
        return environment.executors();
    }

    @Override
    public PlatformFacade platform() {
        return environment.platform();
    }

    @Override
    public ModuleConfig config() {
        return environment.remoteConfig().moduleConfig(moduleId);
    }

    @Override
    public Payload settings() {
        return environment.remoteConfig().moduleSettings(moduleId);
    }

    @Override
    public Registration onConfigChanged(ModuleConfigListener listener) {
        return registrations.track(environment.remoteConfig().subscribeModule(moduleId, listener));
    }

    @Override
    public Registration interceptLogin(Interceptor<LoginAttempt> interceptor, int priority) {
        return registrations.track(
                environment.loginPipeline().register(interceptor, priority, moduleId));
    }

    @Override
    public Registration interceptChat(Interceptor<ChatMessage> interceptor, int priority) {
        return registrations.track(
                environment.chatPipeline().register(interceptor, priority, moduleId));
    }

    @Override
    public Registration observeChat(ChatObserver observer) {
        return registrations.track(environment.chatPipeline().observe(observer));
    }

    @Override
    public Registration onPlayerJoin(PlayerSessionListener listener) {
        return registrations.track(environment.playerSessions().onJoin(listener));
    }

    @Override
    public Registration onPlayerQuit(PlayerSessionListener listener) {
        return registrations.track(environment.playerSessions().onQuit(listener));
    }

    @Override
    public Registration registerCommand(CommandSpec spec) {
        if (spec == null) {
            throw new IllegalArgumentException("a command spec is required");
        }
        return registrations.track(environment.platform().commands().register(spec));
    }

    @Override
    public Registration scheduleRepeating(Runnable task, long initialDelayMs, long periodMs) {
        if (task == null) {
            throw new IllegalArgumentException("task is required");
        }
        try {
            final ScheduledFuture<?> handle = environment.executors().scheduler().scheduleAtFixedRate(
                    guard(task), Math.max(0L, initialDelayMs), Math.max(1L, periodMs),
                    TimeUnit.MILLISECONDS);
            return registrations.track(Registration.once(new Runnable() {
                @Override
                public void run() {
                    handle.cancel(false);
                }
            }));
        } catch (RejectedExecutionException e) {
            logger.debug("not scheduling repeating work: the scheduler is shutting down");
            return Registration.NONE;
        }
    }

    @Override
    public Registration scheduleOnce(Runnable task, long delayMs) {
        if (task == null) {
            throw new IllegalArgumentException("task is required");
        }
        try {
            final ScheduledFuture<?> handle = environment.executors().scheduler()
                    .schedule(guard(task), Math.max(0L, delayMs), TimeUnit.MILLISECONDS);
            return registrations.track(Registration.once(new Runnable() {
                @Override
                public void run() {
                    handle.cancel(false);
                }
            }));
        } catch (RejectedExecutionException e) {
            logger.debug("not scheduling one-off work: the scheduler is shutting down");
            return Registration.NONE;
        }
    }

    @Override
    public <T> MirrorStore<T> mirror(String name, Class<T> valueType, MirrorPolicy policy) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("a mirror needs a name");
        }
        // Derived, never supplied: two modules cannot collide on a filename, and a module cannot
        // write outside the plugin's data directory by passing a path with a `..` in it.
        Path directory = environment.platform().dataDirectory().resolve("modules").resolve(moduleId);
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            // MirrorStore's own save path creates parents too, so this is a diagnostic rather than a
            // fatal problem — and a read-only data directory should not stop a module from loading.
            logger.error("could not create the data directory for module '" + moduleId + "'", e);
        }
        final MirrorStore<T> store = MirrorStore.<T>builder(logger, directory.resolve(name + ".json"), valueType)
                .policy(policy)
                .scheduler(environment.executors().scheduler())
                .open();
        registrations.track(Registration.once(new Runnable() {
            @Override
            public void run() {
                store.close();
            }
        }));
        return store;
    }

    /**
     * Wraps a scheduled task so an exception cannot silently cancel it.
     *
     * <p>{@code scheduleAtFixedRate} stops running a task that throws, without a word. A module's
     * poll that dies on its first transient error and never runs again is the failure mode this
     * exists to prevent, and the first anyone would know is that something stopped happening.
     */
    private Runnable guard(final Runnable task) {
        return new Runnable() {
            @Override
            public void run() {
                try {
                    task.run();
                } catch (RuntimeException e) {
                    logger.error("scheduled task failed", e);
                }
            }
        };
    }

    /** A bus that records this module's subscriptions and delegates everything else. */
    private final class TrackingTunnelBus implements TunnelBus {

        private final TunnelBus delegate;

        TrackingTunnelBus(TunnelBus delegate) {
            this.delegate = delegate;
        }

        @Override
        public void send(String type, Payload payload) {
            delegate.send(type, payload);
        }

        @Override
        public void reply(String requestId, String type, Payload payload) {
            delegate.reply(requestId, type, payload);
        }

        @Override
        public CompletableFuture<Payload> sendAndWait(String type, Payload payload) {
            return delegate.sendAndWait(type, payload);
        }

        @Override
        public CompletableFuture<Payload> sendAndWait(String type, Payload payload, long timeoutMs) {
            return delegate.sendAndWait(type, payload, timeoutMs);
        }

        @Override
        public Registration subscribe(String type, TunnelMessageHandler handler) {
            return registrations.track(delegate.subscribe(type, handler));
        }

        @Override
        public Registration subscribe(String type, TunnelMessageHandler handler, Executor executor) {
            return registrations.track(delegate.subscribe(type, handler, executor));
        }

        @Override
        public ProtocolMode mode() {
            return delegate.mode();
        }

        @Override
        public boolean isConnected() {
            return delegate.isConnected();
        }
    }
}
