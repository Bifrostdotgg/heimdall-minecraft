package com.heimdall.shell.bukkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import com.heimdall.shell.hotswap.JulShellLog;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.function.Predicate;
import java.util.logging.Logger;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The post-swap sweep on Bukkit's real {@link HandlerList}: whatever a retired core's classloader
 * defined, listener or executor, comes off; everything else stays (departure D87).
 *
 * <p>The "retired core" here is a child classloader with a proxy class defined in it, which is the
 * property the sweep actually keys on: adventure-platform-bukkit's leftover listener is a class from
 * the core's loader registered against the shell's plugin, and so is this.
 */
class BukkitSweepTest {

    /** A real event type with its own handler list. */
    public static final class SweepEvent extends Event {

        private static final HandlerList HANDLERS = new HandlerList();

        @Override
        public HandlerList getHandlers() {
            return HANDLERS;
        }

        public static HandlerList getHandlerList() {
            return HANDLERS;
        }
    }

    private static final InvocationHandler INERT = new InvocationHandler() {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if ("hashCode".equals(method.getName())) {
                return System.identityHashCode(proxy);
            }
            if ("equals".equals(method.getName())) {
                return proxy == args[0];
            }
            return null;
        }
    };

    private static final class ShellListener implements Listener {
    }

    private static final EventExecutor SHELL_EXECUTOR = new EventExecutor() {
        @Override
        public void execute(Listener listener, Event event) {
        }
    };

    @Test
    @DisplayName("listeners and executors from the retired core come off; the shell's stay")
    void sweepsOnlyTheRetiredCore() throws Exception {
        Plugin plugin = mock(Plugin.class);
        final URLClassLoader retired = new URLClassLoader(new URL[0],
                BukkitSweepTest.class.getClassLoader());
        Listener leaked = (Listener) Proxy.newProxyInstance(
                retired, new Class<?>[] {Listener.class}, INERT);
        EventExecutor leakedExecutor = (EventExecutor) Proxy.newProxyInstance(
                retired, new Class<?>[] {EventExecutor.class}, INERT);
        Listener kept = new ShellListener();
        Listener keptButExecutorLeaked = new ShellListener();

        HandlerList handlers = SweepEvent.getHandlerList();
        handlers.register(new RegisteredListener(
                leaked, SHELL_EXECUTOR, EventPriority.NORMAL, plugin, false));
        handlers.register(new RegisteredListener(
                kept, SHELL_EXECUTOR, EventPriority.LOW, plugin, false));
        handlers.register(new RegisteredListener(
                keptButExecutorLeaked, leakedExecutor, EventPriority.MONITOR, plugin, false));

        int removed = BukkitSweep.listeners(plugin, new Predicate<Class<?>>() {
            @Override
            public boolean test(Class<?> type) {
                return type.getClassLoader() == retired;
            }
        }, new JulShellLog(Logger.getLogger("sweep-test")));

        assertEquals(2, removed);
        RegisteredListener[] left = handlers.getRegisteredListeners();
        assertEquals(1, left.length);
        assertEquals(kept, left[0].getListener());
        retired.close();
        HandlerList.unregisterAll(kept);
    }
}
