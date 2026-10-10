package com.heimdall.shell.bukkit;

import com.heimdall.shell.hotswap.LoginGateHolder;
import com.heimdall.shell.hotswap.ShellLog;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

/**
 * The shell's permanent pre-login listener: asks the running core's gate, holds a login while a
 * core is starting or swapping, and refuses it when no core will decide (departure D87).
 *
 * <p>{@code AsyncPlayerPreLoginEvent} at {@code LOW}, exactly where the core's own listener sat
 * before the split, for the reasons {@code BukkitLoginListener} gives: off the main thread, so
 * waiting costs no tick, and early, so an identity decision is settled before other plugins spend
 * work on the connection. Waiting here blocks only this connection's own login thread.
 *
 * <p>The reason is a legacy string through {@code disallow(Result, String)}, the one signature that
 * exists on every supported version.
 */
final class BukkitLoginGate implements Listener {

    private final LoginGateHolder gates;
    private final ShellLog log;

    BukkitLoginGate(LoginGateHolder gates, ShellLog log) {
        this.gates = gates;
        this.log = log;
    }

    // No ignoreCancelled: AsyncPlayerPreLoginEvent is not Cancellable, so the flag is inert on it.
    @EventHandler(priority = EventPriority.LOW)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            // Already refused by something else (a ban plugin, an anti-bot). Its reason stands; there
            // is nothing to wait for.
            return;
        }
        LoginGateHolder.Decision decision = gates.await();
        if (decision.gate() == null) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, decision.refusal());
            return;
        }
        boolean stands;
        try {
            stands = gates.decide(decision, event);
        } catch (Throwable broken) {
            // The core's own listener contains everything, so this is a gate that could not run at
            // all: a classloader closed under it, most likely. No core decided, so the login is
            // refused, as it is whenever no core decides.
            log.error("the core's login gate failed for " + event.getName()
                    + "; refusing the login", broken);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    com.heimdall.shell.hotswap.ShellMessages.LOGIN_UPDATING);
            return;
        }
        if (!stands) {
            // Decided by a core that a swap stopped before it finished: not a decision any running
            // core stands behind, so refused, whatever it said (departure D87).
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    com.heimdall.shell.hotswap.ShellMessages.LOGIN_UPDATING);
        }
    }
}
