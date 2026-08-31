package com.heimdall.module.punishments;

import com.heimdall.core.module.ModuleContext;
import com.heimdall.core.util.Registration;

/**
 * Optional LiteBans hook and importer. Reflection so the jar loads without LiteBans.
 * Event types live in {@link LiteBansEventBridge}, loaded only when Events is present.
 */
final class LiteBansSupport {

    private static volatile Registration hook = Registration.NONE;

    private LiteBansSupport() {
    }

    static void tryHook(final HeimdallPunishmentsModule module, final ModuleContext context) {
        try {
            Class.forName("litebans.api.Events");
            Class<?> bridge = Class.forName("com.heimdall.module.punishments.LiteBansEventBridge");
            Object handle = bridge.getDeclaredMethod("install", ModuleContext.class)
                    .invoke(null, context);
            if (handle instanceof Registration) {
                hook = (Registration) handle;
            }
            context.logger().info("LiteBans is present; import and event hook are available");
        } catch (ClassNotFoundException absent) {
            context.logger().debug(() -> "LiteBans is not on the classpath; hook idle");
        } catch (Throwable failed) {
            context.logger().warn("LiteBans probe failed: " + failed);
        }
    }

    static void unhook() {
        Registration current = hook;
        hook = Registration.NONE;
        current.close();
    }

    static void importNow(final ModuleContext context) {
        PunishmentImporters.importAll(context);
    }
}
