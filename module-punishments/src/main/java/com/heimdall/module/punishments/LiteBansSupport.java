package com.heimdall.module.punishments;

import com.heimdall.core.http.model.PunishmentImportRow;
import com.heimdall.core.module.ModuleContext;
import java.util.List;

/**
 * Optional LiteBans hook and importer. Reflection so the jar loads without LiteBans.
 */
final class LiteBansSupport {

    private LiteBansSupport() {
    }

    static void tryHook(final HeimdallPunishmentsModule module, final ModuleContext context) {
        try {
            Class.forName("litebans.api.Database");
            context.logger().info("LiteBans is present; import is available. Event hook is a later slice.");
        } catch (ClassNotFoundException absent) {
            context.logger().debug(() -> "LiteBans is not on the classpath; hook idle");
        } catch (Throwable failed) {
            context.logger().warn("LiteBans probe failed: " + failed);
        }
    }

    static void importNow(final ModuleContext context) {
        List<PunishmentImportRow> rows = LiteBansImporter.readAll(context.logger());
        if (rows.isEmpty()) {
            context.logger().warn("LiteBans import produced no rows");
            return;
        }
        String salt = PunishmentSettings.from(context.config()).ipSalt;
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
