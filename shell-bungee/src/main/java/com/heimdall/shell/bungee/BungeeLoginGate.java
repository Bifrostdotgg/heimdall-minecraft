package com.heimdall.shell.bungee;

import com.heimdall.shell.hotswap.LoginGateHolder;
import com.heimdall.shell.hotswap.ShellLog;
import com.heimdall.shell.hotswap.ShellMessages;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.PendingConnection;
import net.md_5.bungee.api.event.LoginEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.event.EventPriority;

/**
 * The shell's permanent login listener on BungeeCord: asks the running core's gate, holds a login
 * while a core is starting or swapping, and refuses it when no core will decide (departure D87).
 *
 * <h2>The shell owns the intent, and the thread</h2>
 *
 * <p>BungeeCord's {@code LoginEvent} is an {@code AsyncEvent}: a plugin that wants to decide later
 * registers an intent during dispatch and completes it when done, and an intent never completed
 * hangs that player's connection with no timeout anywhere to rescue it. Before the split the core
 * registered the intent and ran the decision on its own {@code heimdall-io} pool, which a swap shuts
 * down: a decision queued behind the drain would be dropped and its intent never completed.
 *
 * <p>So the shell registers the intent (an intent can only be registered during dispatch, and the
 * core that would decide may not exist yet), runs the decision on its own small pool of daemon
 * threads that no swap touches, and completes the intent in a {@code finally} however the decision
 * ends. A pool that refuses the work completes the intent with a refusal on the spot.
 */
final class BungeeLoginGate implements Listener {

    /** Concurrent decisions in flight. Logins beyond it are refused, never queued unboundedly. */
    static final int MAX_IN_FLIGHT = 256;

    private final Plugin plugin;
    private final LoginGateHolder gates;
    private final ShellLog log;
    private final ThreadPoolExecutor threads;

    BungeeLoginGate(Plugin plugin, LoginGateHolder gates, ShellLog log) {
        this.plugin = plugin;
        this.gates = gates;
        this.log = log;
        final AtomicInteger count = new AtomicInteger();
        this.threads = new ThreadPoolExecutor(0, MAX_IN_FLIGHT, 30L, TimeUnit.SECONDS,
                new SynchronousQueue<Runnable>(), new ThreadFactory() {
                    @Override
                    public Thread newThread(Runnable task) {
                        Thread thread = new Thread(task, "heimdall-login-" + count.incrementAndGet());
                        thread.setDaemon(true);
                        thread.setContextClassLoader(BungeeLoginGate.class.getClassLoader());
                        return thread;
                    }
                });
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onLogin(final LoginEvent event) {
        final PendingConnection connection = event.getConnection();
        if (connection == null || event.isCancelled()) {
            // Already refused by another plugin; its reason stands.
            return;
        }
        // On the event thread, before returning. From here on exactly one path must complete it.
        event.registerIntent(plugin);
        final AtomicBoolean completed = new AtomicBoolean();
        try {
            threads.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        decide(event, connection);
                    } finally {
                        complete(event, completed);
                    }
                }
            });
        } catch (RejectedExecutionException full) {
            log.warn("refusing " + connection.getName() + ": " + MAX_IN_FLIGHT
                    + " logins are already being decided");
            deny(event, ShellMessages.LOGIN_UPDATING);
            complete(event, completed);
        }
    }

    private void decide(LoginEvent event, PendingConnection connection) {
        LoginGateHolder.Decision decision = gates.await();
        if (decision.gate() == null) {
            deny(event, decision.refusal());
            return;
        }
        try {
            decision.gate().decide(event);
        } catch (Throwable broken) {
            log.error("the core's login gate failed for " + connection.getName()
                    + "; refusing the login", broken);
            deny(event, ShellMessages.LOGIN_UPDATING);
        }
    }

    @SuppressWarnings("deprecation")
    private void deny(LoginEvent event, String reason) {
        // Cancelled AND given a reason, in that order: BungeeCord reads isCancelled() first. The
        // BaseComponent... overload is deprecated on modern BungeeCord but is the one that exists on
        // the 1.16 API floor this shell compiles against (departure D74).
        event.setCancelled(true);
        try {
            event.setCancelReason(TextComponent.fromLegacyText(reason));
        } catch (Throwable unrenderable) {
            log.debug("could not set the refusal reason: " + unrenderable);
        }
    }

    private void complete(LoginEvent event, AtomicBoolean completed) {
        if (!completed.compareAndSet(false, true)) {
            return;
        }
        try {
            event.completeIntent(plugin);
        } catch (Throwable alreadyGone) {
            log.error("could not release the login gate for a connection; if this recurs, players "
                    + "will be left waiting at the login screen", alreadyGone);
        }
    }

    /** Stops the decision threads. In-flight decisions finish; the JVM is going away anyway. */
    void shutdown() {
        threads.shutdown();
    }
}
