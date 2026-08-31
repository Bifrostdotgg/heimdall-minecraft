package com.heimdall.module.punishments;

import com.heimdall.core.http.model.PunishmentImportRow;
import com.heimdall.core.module.ModuleContext;
import com.heimdall.core.util.Registration;
import java.util.List;

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
        String salt = PunishmentSettings.from(context.config()).ipSalt;
        if (salt == null || salt.isEmpty()) {
            context.logger().warn("LiteBans import refused: ip salt has not been pushed");
            return;
        }
        List<PunishmentImportRow> rows = LiteBansImporter.readAll(context.logger());
        if (rows.isEmpty()) {
            context.logger().warn("LiteBans import produced no rows");
            return;
        }
        PunishmentImportRow.hashIps(rows, salt);
        context.logger().info("Posting " + rows.size() + " LiteBans rows to Heimdall");
        context.api().importPunishmentRows(rows).whenComplete((ok, failure) -> {
            if (failure != null) {
                context.logger().error("LiteBans import POST failed", failure);
            } else {
                context.logger().info("LiteBans import finished");
            }
        });
    }
}
