package com.heimdall.shell.bukkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.shell.contract.LoginGate;
import com.heimdall.shell.hotswap.LoginGateHolder;
import com.heimdall.shell.hotswap.ShellLog;
import com.heimdall.shell.hotswap.ShellMessages;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The shell's Bukkit pre-login listener against the real {@code AsyncPlayerPreLoginEvent}: a login
 * is decided by the running core's gate, held while a core is coming, and refused whenever no core
 * decides, never admitted by default (departure D87).
 */
class BukkitLoginGateTest {

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
            ((AsyncPlayerPreLoginEvent) event).disallow(
                    AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST, "not whitelisted");
        }
    };

    private static AsyncPlayerPreLoginEvent login() throws Exception {
        return new AsyncPlayerPreLoginEvent("Steve", InetAddress.getByName("127.0.0.1"),
                UUID.fromString("11111111-2222-3333-4444-555555555555"));
    }

    private LoginGateHolder running(LoginGate gate) {
        LoginGateHolder gates = new LoginGateHolder(log, 2_000L);
        gates.bind(gate);
        gates.state(LoginGateHolder.State.RUNNING);
        return gates;
    }

    @Test
    @DisplayName("with a core running, its gate decides the login")
    void boundGateDecides() throws Exception {
        AsyncPlayerPreLoginEvent event = login();

        new BukkitLoginGate(running(whitelist), log).onPreLogin(event);

        assertEquals(1, decisions.get());
        assertSame(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST, event.getLoginResult());
        assertEquals("not whitelisted", event.getKickMessage());
    }

    @Test
    @DisplayName("a gate that throws refuses the login rather than admitting it, and is not left in flight")
    void throwingGateRefuses() throws Exception {
        LoginGateHolder gates = running(new LoginGate() {
            @Override
            public void decide(Object event) {
                throw new IllegalStateException("zip file closed");
            }
        });
        AsyncPlayerPreLoginEvent event = login();

        new BukkitLoginGate(gates, log).onPreLogin(event);

        assertSame(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, event.getLoginResult());
        assertEquals(ShellMessages.LOGIN_UPDATING, event.getKickMessage());
        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("Steve"), errors.toString());
        assertTrue(gates.drain(0L), "a failed decision must not hold up the next swap");
    }

    @Test
    @DisplayName("a login something else already refused is left alone: no wait, no gate")
    void alreadyRefusedIsSkipped() throws Exception {
        LoginGateHolder gates = new LoginGateHolder(log, 60_000L);
        gates.state(LoginGateHolder.State.SWAPPING);
        gates.bind(whitelist);
        AsyncPlayerPreLoginEvent event = login();
        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, "banned");

        new BukkitLoginGate(gates, log).onPreLogin(event);

        assertEquals(0, decisions.get());
        assertSame(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, event.getLoginResult());
        assertEquals("banned", event.getKickMessage());
    }

    @Test
    @DisplayName("with no core at all, the login is refused at once")
    void noCoreRefuses() throws Exception {
        LoginGateHolder gates = new LoginGateHolder(log, 60_000L);
        gates.state(LoginGateHolder.State.DOWN);
        AsyncPlayerPreLoginEvent event = login();

        long started = System.nanoTime();
        new BukkitLoginGate(gates, log).onPreLogin(event);

        assertTrue(System.nanoTime() - started < 5_000_000_000L, "it waited for a core that is not coming");
        assertSame(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, event.getLoginResult());
        assertEquals(ShellMessages.LOGIN_NO_CORE, event.getKickMessage());
    }

    @Test
    @DisplayName("mid-swap, the login waits and the next core's gate decides it")
    void midSwapIsHeldForTheNextCore() throws Exception {
        final LoginGateHolder gates = new LoginGateHolder(log, 5_000L);
        gates.state(LoginGateHolder.State.SWAPPING);
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
        AsyncPlayerPreLoginEvent event = login();

        new BukkitLoginGate(gates, log).onPreLogin(event);
        binder.join();

        assertEquals(1, decisions.get(), "the next core's gate decided it");
        assertSame(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST, event.getLoginResult());
    }
}
