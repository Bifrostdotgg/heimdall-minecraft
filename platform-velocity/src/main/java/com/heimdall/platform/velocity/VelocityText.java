package com.heimdall.platform.velocity;

import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.text.Msg;
import java.lang.reflect.Method;
import net.kyori.adventure.text.Component;

/**
 * The one place Heimdall's text meets Velocity's, and the reason it needs reflection.
 *
 * <h2>Two Adventures, same names, different classes</h2>
 *
 * <p>Heimdall shades Adventure and {@code :app} relocates it into {@code com.heimdall.libs.kyori},
 * because a plugin that ships {@code net.kyori.adventure} unrelocated collides with whatever else
 * the server has loaded — and on Paper that is the server's own copy, at whatever version that
 * Paper line happens to carry.
 *
 * <p>Velocity's API is built on Adventure too, and its {@code Component} is the server's,
 * <em>unrelocated</em>. So {@code player.disconnect(component)} cannot be written as a normal
 * method call: Shadow rewrites every {@code net.kyori} reference in every class it merges,
 * including the descriptor at that call site, so the compiled call would ask for
 * {@code com.heimdall.libs.kyori.text.Component} from a method that takes the server's. The result
 * is a {@link NoSuchMethodError} at the exact moment a player is supposed to be told why they were
 * refused.
 *
 * <p>Relocation cannot be turned off for one consumer, and turning it off entirely breaks the
 * Bukkit side (the Adventure platform binding pins the version it needs). So the four calls that
 * cross the boundary go through reflection, they are all in this file, and everything else on
 * Velocity uses plain types. See departure D44.
 *
 * <h2>Why the class names are assembled at runtime</h2>
 *
 * <p>Shadow's remapper rewrites string constants that match a relocation pattern, not just
 * bytecode references — so a literal {@code "net.kyori.adventure.text.Component"} would be
 * relocated too, and {@code Class.forName} would look for a class that is not there. Even
 * {@code "net.kyori" + ".adventure"} is folded by javac into one constant before Shadow sees it.
 * Joining an array of fragments is the form that survives, because no fragment matches the pattern
 * on its own.
 *
 * <h2>Degrading</h2>
 *
 * <p>If any lookup fails the bridge reports {@link #isUsable()} false and every method becomes a
 * no-op that logs once. A proxy that cannot render a kick reason still refuses the login — the
 * player sees Velocity's default message instead of ours, which is worse than working and far
 * better than a plugin that fails to load.
 */
final class VelocityText {

    /**
     * Fragments, joined at runtime.
     *
     * <p>Not a constant, and not concatenated with {@code +}: javac folds compile-time constant
     * concatenation into a single string literal, which is exactly the shape Shadow's remapper
     * rewrites. Nothing here matches {@code net.kyori} on its own.
     */
    private static final String[] ADVENTURE = {"net", "kyori", "adventure"};

    private static final String COMPONENT_RESULT =
            "com.velocitypowered.api.event.ResultedEvent$ComponentResult";

    private final HeimdallLogger logger;

    /** The <em>server's</em> Component class, never ours. {@code null} if it could not be found. */
    private final Class<?> componentType;

    /** {@code LegacyComponentSerializer.legacySection()}, as the server's copy of it. */
    private final Object serializer;

    /** {@code serializer.deserialize(String)}. */
    private final Method deserialize;

    /** {@code ResultedEvent.ComponentResult.denied(Component)}. */
    private final Method denied;

    VelocityText(HeimdallLogger logger) {
        this.logger = logger;

        Class<?> componentType = null;
        Object serializer = null;
        Method deserialize = null;
        Method denied = null;
        try {
            componentType = Class.forName(name("text", "Component"));
            Class<?> serializerType =
                    Class.forName(name("text", "serializer", "legacy", "LegacyComponentSerializer"));
            serializer = serializerType.getMethod("legacySection").invoke(null);
            deserialize = serializerType.getMethod("deserialize", String.class);
            denied = Class.forName(COMPONENT_RESULT).getMethod("denied", componentType);
        } catch (Throwable unavailable) {
            logger.warn("could not bridge Heimdall's text onto Velocity's; players will see the "
                    + "proxy's default messages rather than Heimdall's: " + unavailable);
            componentType = null;
            serializer = null;
            deserialize = null;
            denied = null;
        }
        this.componentType = componentType;
        this.serializer = serializer;
        this.deserialize = deserialize;
        this.denied = denied;
    }

    /** Whether the bridge resolved. Reported in the boot banner so a failure is visible at once. */
    boolean isUsable() {
        return componentType != null && deserialize != null && denied != null;
    }

    /**
     * Converts one of Heimdall's components into one of the server's.
     *
     * <p>Via §-coded legacy text, which both sides can serialise and neither can misinterpret. The
     * round trip loses click and hover handlers, which nothing on the login path uses.
     *
     * @return the server's Component, or {@code null} if the bridge is unusable
     */
    Object toServerComponent(Component message) {
        if (!isUsable()) {
            return null;
        }
        try {
            return deserialize.invoke(serializer, Msg.toLegacy(message));
        } catch (Throwable failed) {
            logger.debug(() -> "could not convert a component for Velocity: " + failed);
            return null;
        }
    }

    /**
     * Builds Velocity's "login refused, with this reason" result.
     *
     * @return the result to hand to {@code LoginEvent.setResult}, or {@code null} to leave the
     *     event alone
     */
    Object deniedResult(Component reason) {
        Object component = toServerComponent(reason);
        if (component == null) {
            return null;
        }
        try {
            return denied.invoke(null, component);
        } catch (Throwable failed) {
            logger.debug(() -> "could not build a denial result: " + failed);
            return null;
        }
    }

    /**
     * A denial carrying no reason at all, for when the reason could not be rendered.
     *
     * <p>Reached only if {@link #deniedResult} could not serialise — a serializer that threw on a
     * particular string, most plausibly. It depends on strictly less than that path does (no
     * serializer, just {@code Component.empty()}), so it is a genuine second chance rather than the
     * same call spelled differently.
     *
     * <p>The player then sees Velocity's own disconnect text. That is a worse experience than
     * Heimdall's message and it is not a worse <em>decision</em>: whatever else is broken, somebody
     * who should not be on the network is still not on it.
     *
     * @return the result to hand to {@code LoginEvent.setResult}, or {@code null} if even this
     *     cannot be built — which means the proxy's own Adventure is missing, and a Velocity in that
     *     state could not load any plugin
     */
    Object deniedWithoutReason() {
        if (componentType == null || denied == null) {
            return null;
        }
        try {
            return denied.invoke(null, componentType.getMethod("empty").invoke(null));
        } catch (Throwable failed) {
            logger.debug(() -> "could not build an empty denial result: " + failed);
            return null;
        }
    }

    /**
     * Sends a message to anything that can receive one — a player, the console, a command source.
     *
     * <p>Resolved against the receiver's own class rather than against an interface, because the
     * concrete type is what the reflective lookup can see and Velocity's implementations are not
     * public API.
     */
    void send(Object recipient, Component message) {
        Object component = toServerComponent(message);
        if (recipient == null || component == null) {
            return;
        }
        invokeWithComponent(recipient, "sendMessage", component);
    }

    /** Disconnects a player with a reason. A no-op if the bridge is unusable. */
    void disconnect(Object player, Component reason) {
        Object component = toServerComponent(reason);
        if (player == null || component == null) {
            return;
        }
        invokeWithComponent(player, "disconnect", component);
    }

    private void invokeWithComponent(Object target, String methodName, Object component) {
        try {
            Method method = target.getClass().getMethod(methodName, componentType);
            method.setAccessible(true);
            method.invoke(target, component);
        } catch (Throwable failed) {
            logger.debug(() -> "could not call " + methodName + " on " + target.getClass()
                    + ": " + failed);
        }
    }

    /** {@code net.kyori.adventure.<parts>}, assembled so no constant matches the relocation. */
    private static String name(String... parts) {
        StringBuilder builder = new StringBuilder();
        for (String fragment : ADVENTURE) {
            builder.append(fragment).append('.');
        }
        for (int i = 0; i < parts.length; i++) {
            builder.append(parts[i]);
            if (i < parts.length - 1) {
                builder.append('.');
            }
        }
        return builder.toString();
    }
}
