package com.heimdall.module.punishments;

import com.heimdall.core.http.model.PunishmentImportRow;
import com.heimdall.core.json.Payload;
import com.heimdall.core.log.HeimdallLogger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads vanilla {@code banned-players.json} and {@code banned-ips.json} from the server root.
 */
final class VanillaBanImporter {

    private VanillaBanImporter() {
    }

    static List<PunishmentImportRow> read(Path serverRoot, HeimdallLogger logger) {
        List<PunishmentImportRow> rows = new ArrayList<PunishmentImportRow>();
        if (serverRoot == null || !Files.isDirectory(serverRoot)) {
            return rows;
        }
        readPlayers(serverRoot.resolve("banned-players.json"), rows, logger);
        readIps(serverRoot.resolve("banned-ips.json"), rows, logger);
        return rows;
    }

    private static void readPlayers(Path file, List<PunishmentImportRow> out, HeimdallLogger logger) {
        Payload root = loadArray(file, logger);
        if (root == null) return;
        int n = 0;
        for (Payload item : root.children("items")) {
            PunishmentImportRow row = new PunishmentImportRow();
            row.type = "ban";
            row.targetUuid = item.string("uuid", "");
            row.targetName = item.string("name", "");
            row.reason = item.string("reason", "");
            row.issuedByName = item.string("source", "Server");
            row.issuedAtMillis = 0L;
            row.importProviderId = "vanilla:player:" + row.targetUuid;
            row.active = true;
            if (row.targetUuid.isEmpty() && (row.targetName == null || row.targetName.isEmpty())) {
                continue;
            }
            out.add(row);
            n++;
        }
        logger.info("vanilla banned-players.json -> " + n + " rows");
    }

    private static void readIps(Path file, List<PunishmentImportRow> out, HeimdallLogger logger) {
        Payload root = loadArray(file, logger);
        if (root == null) return;
        int n = 0;
        for (Payload item : root.children("items")) {
            PunishmentImportRow row = new PunishmentImportRow();
            row.type = "ipban";
            row.ip = item.string("ip", "");
            row.reason = item.string("reason", "");
            row.issuedByName = item.string("source", "Server");
            row.importProviderId = "vanilla:ip:" + row.ip;
            row.active = true;
            if (row.ip == null || row.ip.isEmpty()) continue;
            out.add(row);
            n++;
        }
        logger.info("vanilla banned-ips.json -> " + n + " rows");
    }

    private static Payload loadArray(Path file, HeimdallLogger logger) {
        if (!Files.isRegularFile(file)) return null;
        try {
            String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim();
            if (json.isEmpty() || json.charAt(0) != '[') return Payload.empty();
            return Payload.parse("{\"items\":" + json + "}");
        } catch (Exception e) {
            logger.warn("could not read " + file.getFileName() + ": " + e);
            return null;
        }
    }
}
