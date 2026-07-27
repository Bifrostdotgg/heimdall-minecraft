package com.heimdall.core.module;

import com.heimdall.core.concurrent.HeimdallExecutors;
import com.heimdall.core.json.Payload;
import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.mirror.MirrorPolicy;
import com.heimdall.core.mirror.MirrorStore;
import com.heimdall.core.pipeline.ChatMessage;
import com.heimdall.core.pipeline.ChatObserver;
import com.heimdall.core.pipeline.Interceptor;
import com.heimdall.core.pipeline.LoginAttempt;
import com.heimdall.core.platform.PlatformFacade;
import com.heimdall.core.remoteconfig.ModuleConfig;
import com.heimdall.core.remoteconfig.ModuleConfigListener;
import com.heimdall.core.tunnel.TunnelBus;
import com.heimdall.core.util.Registration;

/**
 * Everything a module is allowed to touch, and the reason disabling one is reliable.
 *
 * <p><strong>Every registering method here is tracked.</strong> A subscription, an interceptor, an
 * observer, a scheduled task, a config listener, a mirror — each is recorded against the module that
 * made it, and {@code ModuleManager.disable(id)} closes all of them without knowing what any of them
 * were. The handles are still returned, so a module that wants to unregister something early can;
 * closing twice is a no-op.
 *
 * <p>That inversion is what makes hot-toggling safe. The alternative — trusting each module to undo
 * its own work in {@code disable()} — fails the first time somebody adds a listener and forgets, and
 * the symptom is a "disabled" module still reacting to events, which nobody attributes to the module
 * that was turned off weeks ago.
 *
 * <p>A context belongs to one module and is valid only while that module is enabled. Using one after
 * {@link HeimdallModule#disable()} registers things that will never be unwound; the manager logs a
 * warning if it sees it.
 *
 * <p>Implementations are thread-safe.
 */
public interface ModuleContext {

    /** The module this context belongs to. */
    String moduleId();

    /**
     * The tunnel, with every subscription tracked against this module.
     *
     * <p>Sending is unrestricted; subscribing is where the tracking is, because a subscription is
     * the thing that outlives a module that forgot to clean up.
     */
    TunnelBus tunnel();

    /** A logger that prefixes this module's id, so a log line says which module produced it. */
    HeimdallLogger logger();

    /** The shared pools. Borrowed, never shut down by a module. */
    HeimdallExecutors executors();

    /** The server underneath. */
    PlatformFacade platform();

    // ── Configuration ────────────────────────────────────────────────────────

    /** This module's whole config entry. */
    ModuleConfig config();

    /**
     * This module's settings, as a typed view with defaults.
     *
     * <p>Read on every use rather than captured at enable time: a settings change does not
     * re-enable the module, so a field cached in {@code enable()} would be permanently stale after
     * the first dashboard edit.
     */
    Payload settings();

    /** Subscribes to this module's own config section. Fired only on a real change. */
    Registration onConfigChanged(ModuleConfigListener listener);

    // ── Pipelines ────────────────────────────────────────────────────────────

    /** Registers a login check. Lower priority runs earlier. */
    Registration interceptLogin(Interceptor<LoginAttempt> interceptor, int priority);

    /** Registers a chat check. Lower priority runs earlier. */
    Registration interceptChat(Interceptor<ChatMessage> interceptor, int priority);

    /**
     * Registers a read-only chat observer — what a Discord relay is.
     *
     * <p>Runs only for messages that were allowed, and must not retain them: chat is relayed and
     * never stored.
     */
    Registration observeChat(ChatObserver observer);

    // ── Scheduling ───────────────────────────────────────────────────────────

    /**
     * Schedules repeating work on {@code heimdall-sched}, cancelled when the module is disabled.
     *
     * <p>Not the tunnel's scheduler: that one exists so the heartbeat's sense of time is unaffected
     * by anything else, and a module's poll running there would defeat it.
     */
    Registration scheduleRepeating(Runnable task, long initialDelayMs, long periodMs);

    /** Schedules one-off work on {@code heimdall-sched}, cancelled when the module is disabled. */
    Registration scheduleOnce(Runnable task, long delayMs);

    // ── Persistence ──────────────────────────────────────────────────────────

    /**
     * Opens a disk-backed mirror under this module's own directory, closed when it is disabled.
     *
     * <p>The path is derived rather than supplied, so two modules cannot collide on a filename and
     * a module cannot write outside the plugin's data directory.
     *
     * @param name the mirror's file name, without an extension
     * @param valueType the mirrored value's type, which Gson needs and generics cannot supply
     */
    <T> MirrorStore<T> mirror(String name, Class<T> valueType, MirrorPolicy policy);
}
