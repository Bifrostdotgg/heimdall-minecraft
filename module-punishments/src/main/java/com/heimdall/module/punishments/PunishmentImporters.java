package com.heimdall.module.punishments;

import com.heimdall.core.http.model.PunishmentImportRow;
import com.heimdall.core.module.ModuleContext;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * One import pass: LiteBans SQL, AdvancedBan, BanManager, vanilla JSON.
 * Salt is required before any IP is hashed.
 */
final class PunishmentImporters {

    private PunishmentImporters() {
    }

    static void importAll(ModuleContext context) {
        String salt = PunishmentSettings.from(context.config()).ipSalt;
        if (salt == null || salt.isEmpty()) {
            context.logger().warn("punishment import refused: ip salt has not been pushed");
            return;
        }
        List<PunishmentImportRow> rows = new ArrayList<PunishmentImportRow>();
        rows.addAll(LiteBansImporter.readAll(context.logger()));
        rows.addAll(AdvancedBanImporter.read(context.logger()));
        rows.addAll(BanManagerImporter.read(context.logger()));
        Path root = serverRoot(context.platform().dataDirectory());
        rows.addAll(VanillaBanImporter.read(root, context.logger()));
        if (rows.isEmpty()) {
            context.logger().warn("punishment import produced no rows");
            return;
        }
        PunishmentImportRow.hashIps(rows, salt);
        context.logger().info("Posting " + rows.size() + " import rows to Heimdall");
        context.api().importPunishmentRows(rows).whenComplete((ok, failure) -> {
            if (failure != null) {
                context.logger().error("punishment import POST failed", failure);
            } else {
                context.logger().info("punishment import finished");
            }
        });
    }

    static Path serverRoot(Path dataDirectory) {
        if (dataDirectory == null) return null;
        Path plugins = dataDirectory.getParent();
        if (plugins == null) return dataDirectory;
        Path root = plugins.getParent();
        return root == null ? plugins : root;
    }
}
