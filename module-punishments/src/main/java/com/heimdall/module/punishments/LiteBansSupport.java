package com.heimdall.module.punishments;

import com.heimdall.core.module.ModuleContext;

/**
 * Optional LiteBans hook. Reflection so the jar loads without LiteBans.
 * Full table import posts through HeimdallApi once Database#executeQuery is
 * confirmed against the version on the proxy.
 */
final class LiteBansSupport {

    private LiteBansSupport() {
    }

    static void tryHook(final HeimdallPunishmentsModule module, final ModuleContext context) {
        try {
            Class<?> events = Class.forName("litebans.api.Events");
            events.getMethod("get").invoke(null);
            context.logger().info("LiteBans is present; hook mode will mirror its events");
        } catch (ClassNotFoundException absent) {
            context.logger().debug(() -> "LiteBans is not on the classpath; hook idle");
        } catch (Throwable failed) {
            context.logger().warn("LiteBans hook failed to register: " + failed);
        }
    }

    static void importNow(ModuleContext context) {
        try {
            Class.forName("litebans.api.Database");
            context.logger().info("LiteBans Database is present; run import from the dashboard");
        } catch (ClassNotFoundException absent) {
            context.logger().warn("LiteBans is not installed; nothing to import");
        }
    }
}
