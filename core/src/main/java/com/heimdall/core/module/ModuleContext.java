package com.heimdall.core.module;

import com.heimdall.core.command.CommandSpec;
import com.heimdall.core.concurrent.HeimdallExecutors;
import com.heimdall.core.http.HeimdallApi;
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
import com.heimdall.core.session.PlayerDeathListener;
import com.heimdall.core.session.PlayerSessionListener;
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

    /**
     * The bot's HTTP API. <strong>Never {@code null}</strong>, in any state.
     *
     * <p>Before 1e a module took an {@code ApiClient} as a constructor argument and tolerated
     * {@code null} for a server that had not been set up. That is what made {@code /hd setup}
     * unable to work without a restart: the reference was captured once, at registration, and
     * nothing could re-hand a live one afterwards (departure D56).
     *
     * <p>So this is a gateway rather than a client. There is one per plugin, it is created before
     * any module is registered, and core reconfigures the transport underneath it as the server is
     * set up and as its guild resolves — a module that captures the value returned here is still
     * holding the right thing an hour later.
     *
     * <p>A call made while the bot cannot be asked comes back as an already-failed future carrying
     * {@link com.heimdall.core.http.ApiUnavailableException}, which names which of the two reasons
     * applies. A module that would rather branch than catch — because "no guild yet" is a reason to
     * run a configured fallback rather than to report an error — asks
     * {@link com.heimdall.core.http.HeimdallApi#isUsable()} first.
     */
    HeimdallApi api();

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

    // ── Sessions ─────────────────────────────────────────────────────────────

    /**
     * Called when a player joins, on {@code heimdall-io} rather than on the server's event thread.
     *
     * <p>Not a pipeline: there is no decision left to arbitrate once somebody is on the server. See
     * {@link com.heimdall.core.session.PlayerSessionEvents} for what moving off the event thread
     * costs — join and quit are no longer ordered relative to each other.
     */
    Registration onPlayerJoin(PlayerSessionListener listener);

    /**
     * Called when a player leaves, on {@code heimdall-io}.
     *
     * <p>The handle names somebody who has already gone; every {@code PlayerHandle} method tolerates
     * that by doing nothing.
     */
    Registration onPlayerQuit(PlayerSessionListener listener);

    /**
     * Called when a player dies, on {@code heimdall-io}, carrying the server's own death message.
     *
     * <p>Its own interface rather than a third {@link PlayerSessionListener} verb because it carries
     * a string the other two do not — see {@link com.heimdall.core.session.PlayerDeathListener}.
     *
     * <p><strong>Never fires on a proxy.</strong> Neither Velocity nor BungeeCord has a death event,
     * so a module that relays deaths relays the ones its backends report and nothing on the
     * gatekeeper. That is a fact about the platforms, not a gap to compensate for.
     */
    Registration onPlayerDeath(PlayerDeathListener listener);

    // ── Commands ─────────────────────────────────────────────────────────────

    /**
     * Registers a command, unregistered when this module is disabled.
     *
     * <p>That last part is why this is here rather than being a direct call on the platform facade:
     * v2 had no way to take a command back, so a switched-off feature still answered.
     *
     * <p>On the Bukkit family the name must also appear in {@code plugin.yml} — the platform cannot
     * invent one at runtime — and a name it does not know produces a warning and
     * {@link Registration#NONE}, not a failed enable.
     */
    Registration registerCommand(CommandSpec spec);

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
