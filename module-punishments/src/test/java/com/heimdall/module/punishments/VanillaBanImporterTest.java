package com.heimdall.module.punishments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.http.model.PunishmentImportRow;
import com.heimdall.core.log.RecordingLogger;
import com.heimdall.core.punish.PunishmentIp;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VanillaBanImporterTest {

    @TempDir
    Path root;

    @Test
    void readsPlayersAndHashesIps() throws Exception {
        Files.write(root.resolve("banned-players.json"), (
                "[{\"uuid\":\"11111111-1111-1111-1111-111111111111\",\"name\":\"Steve\","
                        + "\"reason\":\"grief\",\"source\":\"Admin\"}]"
        ).getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("banned-ips.json"), (
                "[{\"ip\":\"203.0.113.9\",\"reason\":\"proxy\",\"source\":\"Admin\"}]"
        ).getBytes(StandardCharsets.UTF_8));

        List<PunishmentImportRow> rows = VanillaBanImporter.read(root, new RecordingLogger(true));
        assertEquals(2, rows.size());
        PunishmentImportRow.hashIps(rows, "salt");
        boolean sawBan = false;
        boolean sawIp = false;
        for (int i = 0; i < rows.size(); i++) {
            PunishmentImportRow row = rows.get(i);
            assertNull(row.ip);
            if ("ban".equals(row.type)) {
                sawBan = true;
                assertEquals("Steve", row.targetName);
            }
            if ("ipban".equals(row.type)) {
                sawIp = true;
                assertEquals(PunishmentIp.hash("203.0.113.9", "salt"), row.ipDigest);
            }
        }
        assertTrue(sawBan && sawIp);
    }
}
