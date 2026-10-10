package com.heimdall.shell.bungee;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.shell.contract.LoginGate;
import com.heimdall.shell.hotswap.LoginGateHolder;
import com.heimdall.shell.hotswap.ShellLog;
import com.heimdall.shell.hotswap.ShellMessages;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.md_5.bungee.api.Callback;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.PendingConnection;
import net.md_5.bungee.api.event.LoginEvent;
import net.md_5.bungee.api.plugin.Plugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The shell's BungeeCord login listener against the real {@code AsyncEvent} intent machinery.
 *
 * <p>An intent registered and never completed leaves that player at the login screen forever:
 * {@code AsyncEvent} holds a latch and no clock, and nothing in the proxy notices. So the event is
 * real, {@link LoginEvent#postCall()} runs exactly where BungeeCord's {@code EventBus} runs it,
 * and "the connection proceeds" is BungeeCord's own callback firing, not something this test
 * defines. Every path must release it exactly once, including the ones where no core decides
 * (departure D87).
 */
class BungeeLoginGateTest {

    private static final UUID PLAYER = UUID.fromString("11111111-2222-3333-4444-555555555555");

    /** Through {@code Plugin}'s protected constructor, which exists for exactly this. */
    private static final class TestPlugin extends Plugin {
        TestPlugin() {
            super(null, null);
        }
    }

    private final Plugin plugin = new TestPlugin();
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

    /** BungeeCord's release signal, waitable. */
    private static final class Released implements Callback<LoginEvent> {

        private final AtomicInteger count = new AtomicInteger();
        private final CountDownLatch once = new CountDownLatch(1);

        @Override
        public void done(LoginEvent result, Throwable error) {
            count.incrementAndGet();
            once.countDown();
        }

        boolean await() throws InterruptedException {
            return once.await(5, TimeUnit.SECONDS);
        }

        int count() {
            return count.get();
        }
    }

    private static PendingConnection connection() {
        return (PendingConnection) java.lang.reflect.Proxy.newProxyInstance(
                PendingConnection.class.getClassLoader(),
                new Class<?>[] {PendingConnection.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getUniqueId":
                            return PLAYER;
                        case "getName":
                            return "Steve";
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == args[0];
                        case "toString":
                            return "PendingConnection{Steve}";
                        default:
                            return null;
                    }
                });
    }

    private LoginEvent drive(BungeeLoginGate gate, Released released) {
        LoginEvent event = new LoginEvent(connection(), released);
        gate.onLogin(event);
        event.postCall();
        return event;
    }

    private static LoginGate denying(final String reason) {
        return new LoginGate() {
            @Override
            public void decide(Object event) {
                ((LoginEvent) event).setCancelled(true);
                ((LoginEvent) event).setCancelReason(TextComponent.fromLegacyText(reason));
            }
        };
    }

    private static final LoginGate ADMITTING = new LoginGate() {
        @Override
        public void decide(Object event) {
        }
    };

    private static String reason(LoginEvent event) {
        return TextComponent.toLegacyText(event.getCancelReasonComponents());
    }

    @Test
    @DisplayName("with a core's gate bound, its decision stands and the intent is released once")
    void boundGateDecides() throws Exception {
        LoginGateHolder gates = new LoginGateHolder(log, 2_000L);
        gates.bind(denying("not whitelisted"));
        gates.state(LoginGateHolder.State.RUNNING);
        BungeeLoginGate gate = new BungeeLoginGate(plugin, gates, log);
        Released released = new Released();

        LoginEvent event = drive(gate, released);

        assertTrue(released.await(), "the intent was never completed: the player hangs forever");
        assertEquals(1, released.count());
        assertTrue(event.isCancelled());
        assertTrue(reason(event).contains("not whitelisted"));
        gate.shutdown();
    }

    @Test
    @DisplayName("mid-swap, a login waits for the next core's gate and is decided by it")
    void heldUntilTheNextCoreBinds() throws Exception {
        final LoginGateHolder gates = new LoginGateHolder(log, 5_000L);
        gates.state(LoginGateHolder.State.SWAPPING);
        BungeeLoginGate gate = new BungeeLoginGate(plugin, gates, log);
        Released released = new Released();

        LoginEvent event = drive(gate, released);
        Thread.sleep(200L);
        assertEquals(0, released.count(), "released before any core decided");
        gates.bind(ADMITTING);
        gates.state(LoginGateHolder.State.RUNNING);

        assertTrue(released.await());
        assertFalse(event.isCancelled(), "the new core admitted them");
        gate.shutdown();
    }

    @Test
    @DisplayName("a swap that outlasts the hold refuses the login as 'updating', and releases it")
    void holdExpiresIntoARefusal() throws Exception {
        LoginGateHolder gates = new LoginGateHolder(log, 150L);
        gates.state(LoginGateHolder.State.SWAPPING);
        BungeeLoginGate gate = new BungeeLoginGate(plugin, gates, log);
        Released released = new Released();

        LoginEvent event = drive(gate, released);

        assertTrue(released.await());
        assertTrue(event.isCancelled(), "fail closed: no core decided, so nobody is admitted");
        assertTrue(reason(event).contains(ShellMessages.LOGIN_UPDATING), reason(event));
        gate.shutdown();
    }

    @Test
    @DisplayName("with no core at all, a login is refused at once")
    void noCoreRefusesAtOnce() throws Exception {
        LoginGateHolder gates = new LoginGateHolder(log, 60_000L);
        gates.state(LoginGateHolder.State.DOWN);
        BungeeLoginGate gate = new BungeeLoginGate(plugin, gates, log);
        Released released = new Released();

        long started = System.nanoTime();
        LoginEvent event = drive(gate, released);

        assertTrue(released.await());
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 5_000L,
                "no core is coming, so there is nothing to wait for");
        assertTrue(event.isCancelled());
        assertTrue(reason(event).contains(ShellMessages.LOGIN_NO_CORE), reason(event));
        gate.shutdown();
    }

    @Test
    @DisplayName("a gate that throws refuses the login and still releases it exactly once")
    void throwingGateRefuses() throws Exception {
        LoginGateHolder gates = new LoginGateHolder(log, 2_000L);
        gates.bind(new LoginGate() {
            @Override
            public void decide(Object event) {
                throw new NoClassDefFoundError("a closed classloader");
            }
        });
        gates.state(LoginGateHolder.State.RUNNING);
        BungeeLoginGate gate = new BungeeLoginGate(plugin, gates, log);
        Released released = new Released();

        LoginEvent event = drive(gate, released);

        assertTrue(released.await());
        assertEquals(1, released.count());
        assertTrue(event.isCancelled());
        assertTrue(errors.get(0).contains("refusing the login"), errors.toString());
        gate.shutdown();
    }

    @Test
    @DisplayName("a decision pool that refuses the work refuses the login on the spot")
    void rejectedWorkIsRefusedAndReleased() throws Exception {
        LoginGateHolder gates = new LoginGateHolder(log, 2_000L);
        gates.bind(ADMITTING);
        gates.state(LoginGateHolder.State.RUNNING);
        BungeeLoginGate gate = new BungeeLoginGate(plugin, gates, log);
        gate.shutdown();
        Released released = new Released();

        LoginEvent event = drive(gate, released);

        assertTrue(released.await(), "the gate has to be released even when nothing ran");
        assertTrue(event.isCancelled(), "fail closed: no core decided");
    }

    @Test
    @DisplayName("a connection another plugin already refused is left alone, with no intent")
    void alreadyRefusedIsLeftAlone() throws Exception {
        LoginGateHolder gates = new LoginGateHolder(log, 2_000L);
        gates.bind(ADMITTING);
        gates.state(LoginGateHolder.State.RUNNING);
        BungeeLoginGate gate = new BungeeLoginGate(plugin, gates, log);
        Released released = new Released();
        LoginEvent event = new LoginEvent(connection(), released);
        event.setCancelled(true);
        event.setCancelReason(TextComponent.fromLegacyText("Banned until 2027, appeal at ..."));

        gate.onLogin(event);
        event.postCall();

        assertEquals(1, released.count(), "released by postCall itself: no intent was registered");
        assertTrue(reason(event).contains("appeal"));
        gate.shutdown();
    }
}
