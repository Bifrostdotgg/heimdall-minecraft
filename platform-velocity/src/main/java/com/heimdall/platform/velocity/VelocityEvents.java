package com.heimdall.platform.velocity;

import com.heimdall.core.util.Registration;
import com.velocitypowered.api.event.EventHandler;
import com.velocitypowered.api.event.EventManager;
import com.velocitypowered.api.event.PostOrder;

/**
 * Registers one functional event handler and hands back a handle that removes exactly that one.
 *
 * <h2>Why not {@code register(plugin, listenerObject)}</h2>
 *
 * <p>Annotation-based registration builds a method handle for each {@code @Subscribe} method and
 * caches it in Velocity's {@code untargetedMethodHandlers}, keyed strongly by the {@code Method}.
 * That cache entry outlives the listener: a swapped-out core stays pinned until Velocity happens to
 * evict it, which only happens on some later registration by some plugin. A functional
 * {@code EventHandler} goes through no reflection and no cache at all, and
 * {@code unregister(plugin, handler)} removes it by identity, which is the only removal that does
 * not also take the shell's own handlers with it (departure D87).
 *
 * <p>The plugin argument is always the shell's {@code @Plugin} instance: Velocity resolves it to a
 * plugin container, and only the shell is a plugin as far as Velocity knows.
 */
final class VelocityEvents {

    private VelocityEvents() {
    }

    /**
     * Registers {@code handler} for {@code type} at {@code order}.
     *
     * <p>The {@link PostOrder} overload is deprecated in the 3.4 API in favour of a raw
     * {@code short} priority, and is used anyway because it is the one whose orders mean exactly
     * what the {@code @Subscribe(order = ...)} annotations they replace meant, on every Velocity this
     * plugin loads on. It is still abstract on the interface, so every proxy implements it.
     */
    @SuppressWarnings("deprecation")
    static <E> Registration listen(
            final EventManager events,
            final Object plugin,
            Class<E> type,
            PostOrder order,
            final EventHandler<E> handler) {
        events.register(plugin, type, order, handler);
        return Registration.once(new Runnable() {
            @Override
            public void run() {
                events.unregister(plugin, handler);
            }
        });
    }
}
