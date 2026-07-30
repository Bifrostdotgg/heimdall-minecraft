package com.heimdall.module.offenses;

import com.heimdall.core.command.CommandSpec;
import com.heimdall.core.concurrent.HeimdallExecutors;
import com.heimdall.core.http.HeimdallApi;
import com.heimdall.core.json.Payload;
import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.mirror.MirrorPolicy;
import com.heimdall.core.mirror.MirrorStore;
import com.heimdall.core.module.ModuleContext;
import com.heimdall.core.pipeline.ChatMessage;
import com.heimdall.core.pipeline.ChatObserver;
import com.heimdall.core.pipeline.Interceptor;
import com.heimdall.core.pipeline.LoginAttempt;
import com.heimdall.core.platform.PlatformFacade;
import com.heimdall.core.remoteconfig.ModuleConfig;
import com.heimdall.core.remoteconfig.ModuleConfigListener;
import com.heimdall.core.session.PlayerDeathListener;
import com.heimdall.core.session.PlayerSessionListener;
import com.heimdall.core.tunnel.TunnelBus;
import com.heimdall.core.util.Registration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A {@link ModuleContext} that hands out real registrations and lets a test hold the timer still.
 *
 * <p>The real {@code ModuleContextImpl} schedules on {@code heimdall-sched}, which is exactly right
 * in production and useless in a test: "the five-minute refresh keeps the old cache when it fails"
 * is not a property anybody should wait five minutes — or fake a clock — to observe. Here a
 * scheduled task is recorded and run by hand, so the cadence assertions are about what was scheduled
 * and the behaviour assertions are about what running it does.
 *
 * <p>Commands go to the {@link PlatformFacade}'s own registrar — {@code RecordingCommands} on a
 * {@code FakePlatform} — so {@code has("offend")} flipping on disable is checked against the same
 * object the module registered with, rather than against a counter this class kept.
 *
 * <p><strong>Everything the offenses module does not use throws.</strong> A silent no-op would let a
 * later change start subscribing to the tunnel or opening a mirror and have the test suite say
 * nothing about it; an exception names the method and the day it was added.
 */
final class TestModuleContext implements ModuleContext {

    /** One {@code scheduleRepeating} call, kept so a test can inspect it and run it. */
    static final class ScheduledTask {

        final Runnable task;
        final long initialDelayMs;
        final long periodMs;

        ScheduledTask(Runnable task, long initialDelayMs, long periodMs) {
            this.task = task;
            this.initialDelayMs = initialDelayMs;
            this.periodMs = periodMs;
        }

        /** Runs it, as {@code heimdall-sched} would when the period elapses. */
        void fire() {
            task.run();
        }
    }

    private final String moduleId;
    private final HeimdallLogger logger;
    private final HeimdallExecutors executors;
    private final HeimdallApi api;
    private final PlatformFacade platform;
    private final List<ScheduledTask> scheduled = Collections.synchronizedList(
            new ArrayList<ScheduledTask>());

    TestModuleContext(
            String moduleId,
            HeimdallLogger logger,
            HeimdallExecutors executors,
            HeimdallApi api,
            PlatformFacade platform) {
        this.moduleId = moduleId;
        this.logger = logger;
        this.executors = executors;
        this.api = api;
        this.platform = platform;
    }

    /** Every repeating task currently scheduled, oldest first. */
    List<ScheduledTask> scheduledTasks() {
        synchronized (scheduled) {
            return new ArrayList<ScheduledTask>(scheduled);
        }
    }

    @Override
    public String moduleId() {
        return moduleId;
    }

    @Override
    public HeimdallLogger logger() {
        return logger;
    }

    @Override
    public HeimdallExecutors executors() {
        return executors;
    }

    @Override
    public HeimdallApi api() {
        return api;
    }

    @Override
    public PlatformFacade platform() {
        return platform;
    }

    @Override
    public Registration registerCommand(CommandSpec spec) {
        return platform.commands().register(spec);
    }

    @Override
    public Registration scheduleRepeating(Runnable task, long initialDelayMs, long periodMs) {
        final ScheduledTask entry = new ScheduledTask(task, initialDelayMs, periodMs);
        scheduled.add(entry);
        return Registration.once(new Runnable() {
            @Override
            public void run() {
                scheduled.remove(entry);
            }
        });
    }

    // ── Everything the module does not use ───────────────────────────────────

    @Override
    public TunnelBus tunnel() {
        throw unused("tunnel");
    }

    @Override
    public ModuleConfig config() {
        throw unused("config");
    }

    @Override
    public Payload settings() {
        throw unused("settings");
    }

    @Override
    public Registration onConfigChanged(ModuleConfigListener listener) {
        throw unused("onConfigChanged");
    }

    @Override
    public Registration interceptLogin(Interceptor<LoginAttempt> interceptor, int priority) {
        throw unused("interceptLogin");
    }

    @Override
    public Registration interceptChat(Interceptor<ChatMessage> interceptor, int priority) {
        throw unused("interceptChat");
    }

    @Override
    public Registration observeChat(ChatObserver observer) {
        throw unused("observeChat");
    }

    @Override
    public Registration onPlayerJoin(PlayerSessionListener listener) {
        throw unused("onPlayerJoin");
    }

    @Override
    public Registration onPlayerQuit(PlayerSessionListener listener) {
        throw unused("onPlayerQuit");
    }

    @Override
    public Registration onPlayerDeath(PlayerDeathListener listener) {
        throw unused("onPlayerDeath");
    }

    @Override
    public Registration scheduleOnce(Runnable task, long delayMs) {
        throw unused("scheduleOnce");
    }

    @Override
    public <T> MirrorStore<T> mirror(String name, Class<T> valueType, MirrorPolicy policy) {
        throw unused("mirror");
    }

    private static UnsupportedOperationException unused(String method) {
        return new UnsupportedOperationException("the offenses module does not use ModuleContext."
                + method + "() — if it now does, this fake needs a real implementation rather than "
                + "a no-op, or the test stops checking the thing it was written for");
    }
}
