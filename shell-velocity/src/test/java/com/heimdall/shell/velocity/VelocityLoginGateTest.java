package com.heimdall.shell.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.heimdall.shell.contract.LoginGate;
import com.heimdall.shell.hotswap.LoginGateHolder;
import com.heimdall.shell.hotswap.ShellLog;
import com.heimdall.shell.hotswap.ShellMessages;
import com.velocitypowered.api.event.Continuation;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.proxy.Player;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The shell's Velocity login handler against the real {@code LoginEvent}: synchronous with a core
 * running, an async wait without one, and a refusal whenever no core decides (departure D87).
 */
class VelocityLoginGateTest {

    private final List<String> errors = Collections.synchronizedList(new ArrayList<String>());
    private final ShellLog log = new ShellLog() {
        @Override
        public void info(String message) {
        }

        @Override
        public void warn(String message) {
        }

        @Override
        public void error(String message, Throwable cause) {
            errors.add(message);
        }

        @Override
        public void debug(String message) {
        }
    };

    private final AtomicInteger decisions = new AtomicInteger();

    /** A core gate that refuses everyone as "not whitelisted", and counts its calls. */
    private final LoginGate whitelist = new LoginGate() {
        @Override
        public void decide(Object event) {
            decisions.incrementAndGet();
            ((LoginEvent) event).setResult(
                    ResultedEvent.ComponentResult.denied(Component.text("not whitelisted")));
        }
    };

    private static LoginEvent login() {
        Player player = mock(Player.class);
        when(player.getUsername()).thenReturn("Steve");
        return new LoginEvent(player);
    }

    private static String reason(LoginEvent event) {
        Component reason = event.getResult().getReasonComponent().orElse(null);
        assertNotNull(reason, "a refusal carries a reason");
        return ((TextComponent) reason).content();
    }

    /** Runs an async task the way Velocity's event manager would, and waits for its resume. */
    private static void run(EventTask task) {
        assertTrue(task.requiresAsync(), "a wait must never run on an event thread");
        final AtomicInteger resumed = new AtomicInteger();
        task.execute(new Continuation() {
            @Override
            public void resume() {
                resumed.incrementAndGet();
            }

            @Override
            public void resumeWithException(Throwable exception) {
                resumed.incrementAndGet();
            }
        });
        assertEquals(1, resumed.get(), "the event must be released exactly once");
    }

    private LoginGateHolder running(LoginGate gate) {
        LoginGateHolder gates = new LoginGateHolder(log, 2_000L);
        gates.bind(gate);
        gates.state(LoginGateHolder.State.RUNNING);
        return gates;
    }

    @Test
    @DisplayName("with a core running, its gate decides at once, with no async task")
    void boundGateDecidesSynchronously() {
        LoginEvent event = login();

        EventTask task = new VelocityLoginGate(running(whitelist), log).executeAsync(event);

        assertNull(task);
        assertEquals(1, decisions.get());
        assertEquals("not whitelisted", reason(event));
    }

    @Test
    @DisplayName("a gate that throws refuses the login rather than admitting it")
    void throwingGateRefuses() {
        LoginGateHolder gates = running(new LoginGate() {
            @Override
            public void decide(Object event) {
                throw new IllegalStateException("zip file closed");
            }
        });
        LoginEvent event = login();

        assertNull(new VelocityLoginGate(gates, log).executeAsync(event));

        assertFalse(event.getResult().isAllowed());
        assertEquals(ShellMessages.LOGIN_UPDATING, reason(event));
        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("Steve"), errors.toString());
        assertTrue(gates.drain(0L), "a failed decision must not hold up the next swap");
    }

    @Test
    @DisplayName("a login another plugin already refused is left alone")
    void alreadyRefusedIsSkipped() {
        LoginEvent event = login();
        event.setResult(ResultedEvent.ComponentResult.denied(Component.text("banned")));

        EventTask task = new VelocityLoginGate(running(whitelist), log).executeAsync(event);

        assertNull(task);
        assertEquals(0, decisions.get());
        assertEquals("banned", reason(event));
    }

    @Test
    @DisplayName("with no gate, the wait is an async task that refuses after the hold")
    void noGateHoldsThenRefuses() {
        LoginGateHolder gates = new LoginGateHolder(log, 100L);
        gates.state(LoginGateHolder.State.SWAPPING);
        LoginEvent event = login();

        EventTask task = new VelocityLoginGate(gates, log).executeAsync(event);
        assertNotNull(task, "no gate: the handler must not decide on the event thread");
        assertTrue(event.getResult().isAllowed(), "nothing is decided before the task runs");

        long started = System.nanoTime();
        run(task);

        assertTrue(System.nanoTime() - started >= 90_000_000L, "it must actually hold");
        assertFalse(event.getResult().isAllowed());
        assertEquals(ShellMessages.LOGIN_UPDATING, reason(event));
    }

    @Test
    @DisplayName("with no core at all, the task refuses at once")
    void noCoreRefuses() {
        LoginGateHolder gates = new LoginGateHolder(log, 60_000L);
        gates.state(LoginGateHolder.State.DOWN);
        LoginEvent event = login();

        run(new VelocityLoginGate(gates, log).executeAsync(event));

        assertEquals(ShellMessages.LOGIN_NO_CORE, reason(event));
    }

    @Test
    @DisplayName("mid-swap, the task waits and the next core's gate decides it")
    void midSwapIsHeldForTheNextCore() throws Exception {
        final LoginGateHolder gates = new LoginGateHolder(log, 5_000L);
        gates.state(LoginGateHolder.State.SWAPPING);
        LoginEvent event = login();
        EventTask task = new VelocityLoginGate(gates, log).executeAsync(event);
        Thread binder = new Thread(() -> {
            try {
                Thread.sleep(150L);
            } catch (InterruptedException ignored) {
                return;
            }
            gates.bind(whitelist);
            gates.state(LoginGateHolder.State.RUNNING);
        });
        binder.start();

        run(task);
        binder.join();

        assertEquals(1, decisions.get());
        assertEquals("not whitelisted", reason(event));
    }

    /** A gate that admits (decides nothing) only once {@code release} is counted down. */
    private static LoginGate slowAdmitting(final CountDownLatch entered, final CountDownLatch release) {
        return new LoginGate() {
            @Override
            public void decide(Object event) {
                entered.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        };
    }

    @Test
    @DisplayName("a decision that outlives a swap's drain is refused, though the old core admitted it")
    void decisionOutlivingTheDrainIsRefused() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        final LoginGateHolder gates = running(slowAdmitting(entered, release));
        final LoginEvent event = login();
        Thread deciding = new Thread(() -> new VelocityLoginGate(gates, log).executeAsync(event));
        deciding.start();
        assertTrue(entered.await(2, TimeUnit.SECONDS));

        gates.suspend();
        assertFalse(gates.drain(100L));
        release.countDown();
        deciding.join(2_000L);

        assertFalse(event.getResult().isAllowed(), "a stopped core's 'allow' admitted the player");
        assertEquals(ShellMessages.LOGIN_UPDATING, reason(event));
    }
}
