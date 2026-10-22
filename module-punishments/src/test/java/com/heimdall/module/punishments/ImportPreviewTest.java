package com.heimdall.module.punishments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.config.ServerRole;
import com.heimdall.core.json.Payload;
import com.heimdall.core.testing.FakeCommandSource;
import com.heimdall.core.tunnel.Capabilities;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code /hd import --dry-run}, the dashboard's Preview import: the same reading and the same
 * batches, each marked {@code dryRun} with the run's id, and refused outright against a bot that
 * would ignore the flag and import for real.
 */
class ImportPreviewTest {

    @TempDir
    Path root;

    private static final String COUNTS = "{\"success\":true,\"data\":{\"imported\":1,\"skippedBy\":{},"
            + "\"enforced\":{\"ban\":1},\"reconciled\":{\"lifted_to_match\":2}}}";

    /** A server root with one vanilla ban, and the plugin's data folder two levels under it. */
    private Path serverWithOneBan() throws Exception {
        Files.write(root.resolve("banned-players.json"), (
                "[{\"uuid\":\"11111111-1111-1111-1111-111111111111\",\"name\":\"Steve\","
                        + "\"reason\":\"grief\",\"source\":\"Admin\"}]"
        ).getBytes(StandardCharsets.UTF_8));
        Path data = root.resolve("plugins").resolve("Heimdall");
        Files.createDirectories(data);
        return data;
    }

    private static void waitFor(java.util.function.BooleanSupplier done) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000L;
        while (!done.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L);
        }
    }

    @Test
    @DisplayName("a preview sends every batch with dryRun and its run id, and reports itself as one")
    void previewMarksEveryBatchAndTheReport() throws Exception {
        Path data = serverWithOneBan();
        try (ScriptedPunishApi bot = new ScriptedPunishApi().importResponds(COUNTS);
                PunishmentsHarness harness = PunishmentsHarness
                        .withApi(data, ServerRole.STANDALONE, bot.baseUrl())
                        .enableReplace()) {
            harness.tunnel.accepting(Capabilities.PUNISHMENTS, Capabilities.PUNISH_PREVIEW);
            FakeCommandSource console = FakeCommandSource.console();

            harness.module.onStaffCommand(console, "import", Arrays.asList("--dry-run"));
            waitFor(() -> console.wasTold("Preview finished"));

            assertTrue(console.wasTold("Preview started"), console.messageText().toString());
            ScriptedPunishApi.Hit batch = bot.lastSuffix("/punishments/import");
            Payload body = Payload.parse(batch.body);
            assertTrue(body.bool("dryRun", false), batch.body);
            assertFalse(body.string("runId", "").isEmpty(), batch.body);
            assertTrue(console.wasTold("would be imported"), console.messageText().toString());
            assertTrue(console.wasTold("Nothing was written"), console.messageText().toString());

            waitFor(() -> bot.countSuffix("/punishments/import/report") >= 3);
            Payload report = Payload.parse(bot.lastSuffix("/punishments/import/report").body);
            assertTrue(report.bool("dryRun", false), report.toJson());
            assertEquals(body.string("runId", ""), report.string("runId", ""),
                    "the batches and the report name the same run");
            assertEquals(1L, report.child("enforced").longValue("ban", 0L), report.toJson());
            assertEquals(2L, report.child("reconciled").longValue("lifted_to_match", 0L));
        }
    }

    @Test
    @DisplayName("a real run sends no dryRun")
    void realRunIsNotAPreview() throws Exception {
        Path data = serverWithOneBan();
        try (ScriptedPunishApi bot = new ScriptedPunishApi().importResponds(COUNTS);
                PunishmentsHarness harness = PunishmentsHarness
                        .withApi(data, ServerRole.STANDALONE, bot.baseUrl())
                        .enableReplace()) {
            FakeCommandSource console = FakeCommandSource.console();

            harness.module.onStaffCommand(console, "import", Collections.<String>emptyList());
            waitFor(() -> console.wasTold("Import finished"));

            Payload body = Payload.parse(bot.lastSuffix("/punishments/import").body);
            assertFalse(body.has("dryRun"), body.toJson());
        }
    }

    @Test
    @DisplayName("a preview is refused against a bot that has not accepted punishpreview@1")
    void previewRefusedByAnOlderBot() throws Exception {
        Path data = serverWithOneBan();
        try (ScriptedPunishApi bot = new ScriptedPunishApi().importResponds(COUNTS);
                PunishmentsHarness harness = PunishmentsHarness
                        .withApi(data, ServerRole.STANDALONE, bot.baseUrl())
                        .enableReplace()) {
            harness.tunnel.accepting(Capabilities.PUNISHMENTS);
            FakeCommandSource console = FakeCommandSource.console();

            harness.module.onStaffCommand(console, "import", Arrays.asList("--dry-run"));
            Thread.sleep(200L);

            assertTrue(console.wasTold("cannot preview"), console.messageText().toString());
            assertEquals(0, bot.countSuffix("/punishments/import"),
                    "an older bot would have imported every row for real");
        }
    }

    @Test
    @DisplayName("the bot asking for a preview over the tunnel starts one")
    void tunnelPreview() throws Exception {
        Path data = serverWithOneBan();
        try (ScriptedPunishApi bot = new ScriptedPunishApi().importResponds(COUNTS);
                PunishmentsHarness harness = PunishmentsHarness
                        .withApi(data, ServerRole.STANDALONE, bot.baseUrl())
                        .enableReplace()) {
            harness.tunnel.push("punish.import", Payload.builder().put("dryRun", true).build());
            waitFor(() -> bot.countSuffix("/punishments/import") > 0);

            assertTrue(Payload.parse(bot.lastSuffix("/punishments/import").body).bool("dryRun", false));
        }
    }

    @Test
    @DisplayName("the module declares punishpreview@1 beside punishments@1")
    void declaresThePreviewCapability() {
        assertEquals("punishpreview@1", Capabilities.PUNISH_PREVIEW);
        assertTrue(new HeimdallPunishmentsModule().capabilities().containsAll(
                Arrays.asList(Capabilities.PUNISHMENTS, Capabilities.PUNISH_PREVIEW)));
    }

    @Test
    @DisplayName("--dry-run and --preview both mean a preview")
    void flags() {
        assertTrue(HeimdallPunishmentsModule.isDryRun(Arrays.asList("--dry-run")));
        assertTrue(HeimdallPunishmentsModule.isDryRun(Arrays.asList("--PREVIEW")));
        assertFalse(HeimdallPunishmentsModule.isDryRun(Collections.<String>emptyList()));
        assertFalse(HeimdallPunishmentsModule.isDryRun(null));
    }
}
