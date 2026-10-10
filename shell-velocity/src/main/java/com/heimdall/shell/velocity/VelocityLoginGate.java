package com.heimdall.shell.velocity;

import com.heimdall.shell.contract.LoginGate;
import com.heimdall.shell.hotswap.LoginGateHolder;
import com.heimdall.shell.hotswap.ShellLog;
import com.heimdall.shell.hotswap.ShellMessages;
import com.velocitypowered.api.event.AwaitingEventExecutor;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import net.kyori.adventure.text.Component;

/**
 * The shell's permanent login handler on Velocity: asks the running core's gate, holds a login while
 * a core is starting or swapping, and refuses it when no core will decide (departure D87).
 *
 * <p>Registered once, as a functional handler at {@code PostOrder.FIRST}, exactly where the core's
 * own listener sat before the split. With a gate bound it decides synchronously on the event thread,
 * as before. Without one it returns an async task that waits for the next core, so no event thread
 * is held while it does.
 *
 * <p>The refusal is the proxy's own Adventure {@code Component}, which the shell can build directly:
 * unlike the core, it relocates no Adventure.
 */
final class VelocityLoginGate implements AwaitingEventExecutor<LoginEvent> {

    private final LoginGateHolder gates;
    private final ShellLog log;

    VelocityLoginGate(LoginGateHolder gates, ShellLog log) {
        this.gates = gates;
        this.log = log;
    }

    @Override
    public EventTask executeAsync(final LoginEvent event) {
        if (!event.getResult().isAllowed()) {
            // Already refused by another plugin; its reason stands.
            return null;
        }
        LoginGate now = gates.current();
        if (now != null) {
            decide(now, event);
            return null;
        }
        return EventTask.async(new Runnable() {
            @Override
            public void run() {
                LoginGateHolder.Decision decision = gates.await();
                if (decision.gate() == null) {
                    deny(event, decision.refusal());
                    return;
                }
                decide(decision.gate(), event);
            }
        });
    }

    private void decide(LoginGate gate, LoginEvent event) {
        try {
            gate.decide(event);
        } catch (Throwable broken) {
            log.error("the core's login gate failed for " + event.getPlayer().getUsername()
                    + "; refusing the login", broken);
            deny(event, ShellMessages.LOGIN_UPDATING);
        }
    }

    private static void deny(LoginEvent event, String reason) {
        event.setResult(ResultedEvent.ComponentResult.denied(Component.text(reason)));
    }
}
