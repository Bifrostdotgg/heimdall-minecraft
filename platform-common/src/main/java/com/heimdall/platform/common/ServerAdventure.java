package com.heimdall.platform.common;

/**
 * Class names in the <em>server's</em> Adventure, for the few places that reach it by reflection.
 *
 * <p>Heimdall shades its own Adventure and relocates it into {@code com.heimdall.libs.kyori}. The
 * server's (Paper's, Velocity's) is the unrelocated {@code net.kyori.adventure}, and any API that
 * hands one of its components to a plugin can only be called reflectively from here: a compiled call
 * would have its descriptor rewritten to the relocated type and fail with {@link NoSuchMethodError}.
 * See departure D44.
 *
 * <h2>Why the names are assembled at runtime</h2>
 *
 * <p>Shadow's remapper rewrites string constants that match a relocation pattern, not just bytecode
 * references, so a literal {@code "net.kyori.adventure.text.Component"} would be relocated too, and
 * {@code Class.forName} would look for a class that is not there. Even {@code "net.kyori" +
 * ".adventure"} is folded by javac into one constant before Shadow sees it. Joining an array of
 * fragments is the form that survives, because no fragment matches the pattern on its own.
 * {@code :app:verifyShadowJar} checks every class listed as reaching the server's Adventure for a
 * relocated name in the shipped jar.
 */
public final class ServerAdventure {

    /**
     * Fragments, joined at runtime. Not a constant, and not concatenated with {@code +}: nothing
     * here matches {@code net.kyori} on its own.
     */
    private static final String[] ROOT = {"net", "kyori", "adventure"};

    private ServerAdventure() {
    }

    /**
     * A server component's text, through the server's own {@code PlainTextComponentSerializer}.
     *
     * @throws ReflectiveOperationException when the server has no such serializer, or it is not
     *     shaped as it was; the caller decides whether that is fatal
     */
    public static String plainText(Object component) throws ReflectiveOperationException {
        return serialize(component, "plain", "PlainTextComponentSerializer", "plainText");
    }

    /** As {@link #plainText}, through {@code LegacyComponentSerializer.legacySection()}. */
    public static String legacySection(Object component) throws ReflectiveOperationException {
        return serialize(component, "legacy", "LegacyComponentSerializer", "legacySection");
    }

    /**
     * Resolved through the component's own class loader, which is the server's: Paper and Velocity
     * ship the serializers beside Adventure itself.
     */
    private static String serialize(
            Object component, String serializerPackage, String serializerClass, String factory)
            throws ReflectiveOperationException {
        if (component == null) {
            return "";
        }
        ClassLoader loader = component.getClass().getClassLoader();
        if (loader == null) {
            throw new ClassNotFoundException("the component has no class loader");
        }
        Class<?> type = Class.forName(
                name("text", "serializer", serializerPackage, serializerClass), true, loader);
        Class<?> componentType = Class.forName(name("text", "Component"), false, loader);
        Object serializer = type.getMethod(factory).invoke(null);
        Object text = type.getMethod("serialize", componentType).invoke(serializer, component);
        return text == null ? "" : text.toString();
    }

    /** {@code net.kyori.adventure.<parts>}, assembled so no constant matches the relocation. */
    public static String name(String... parts) {
        StringBuilder name = new StringBuilder();
        for (String fragment : ROOT) {
            name.append(fragment).append('.');
        }
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                name.append('.');
            }
            name.append(parts[i]);
        }
        return name.toString();
    }
}
