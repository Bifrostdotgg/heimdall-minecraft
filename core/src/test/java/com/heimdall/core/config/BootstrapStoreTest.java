package com.heimdall.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.log.LogLevel;
import com.heimdall.core.log.RecordingLogger;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Reading and writing {@code bootstrap.yml}, including the states that are not errors. */
class BootstrapStoreTest {

    private final RecordingLogger logger = new RecordingLogger(true);

    private BootstrapStore storeIn(Path dir) {
        return new BootstrapStore(logger, dir.resolve("bootstrap.yml"));
    }

    private static void write(Path dir, String yaml) throws IOException {
        Files.write(dir.resolve("bootstrap.yml"), yaml.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(Path dir) throws IOException {
        return new String(Files.readAllBytes(dir.resolve("bootstrap.yml")), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("a missing file is the not-configured state, not a failure")
    void missingFileIsNotConfigured(@TempDir Path dir) {
        BootstrapStore store = storeIn(dir);

        assertFalse(store.exists());
        BootstrapConfig config = store.load();
        assertFalse(config.isConfigured());
        assertEquals(BootstrapConfig.defaults(), config);
        assertTrue(logger.at(LogLevel.SEVERE).isEmpty(), "a fresh install must not log an error");
        assertTrue(logger.at(LogLevel.WARN).isEmpty());
    }

    @Test
    void savedConfigRoundTrips(@TempDir Path dir) throws IOException {
        BootstrapStore store = storeIn(dir);
        BootstrapConfig config = BootstrapConfig.builder()
                .endpoint("https://api.bifrost.gg")
                .tokenId("tok_123")
                .token("s3cr3t")
                .serverId("survival")
                .role(ServerRole.ENFORCER)
                .debug(true)
                // The 1e additions: the login budget, the update knobs and the local-disable set all
                // have to survive a round trip, since bootstrap.yml is the only place they live.
                .timeoutMs(1500)
                .retries(1)
                .retryDelayMs(750)
                .updatesCheckEnabled(false)
                .updatesNotifyAdmins(false)
                .updatesCheckIntervalHours(6)
                .disabledModules("whitelist rolesync")
                .build();

        store.save(config);

        assertTrue(store.exists());
        assertEquals(config, storeIn(dir).load());
    }

    @Test
    @DisplayName("the login budget, update knobs and local disable set are read from the file")
    void operationalKnobsAreRead(@TempDir Path dir) throws IOException {
        write(dir, "endpoint: https://bot.example\n"
                + "token: shhh\n"
                + "timeoutMs: 1500\n"
                + "retries: 1\n"
                + "retryDelayMs: 750\n"
                + "updatesCheckEnabled: false\n"
                + "updatesNotifyAdmins: false\n"
                + "updatesCheckIntervalHours: 6\n"
                + "disabledModules: whitelist rolesync\n");

        BootstrapConfig config = storeIn(dir).load();

        assertEquals(1500, config.timeoutMs());
        assertEquals(1, config.retries());
        assertEquals(750, config.retryDelayMs());
        assertFalse(config.updatesCheckEnabled(), "an operator must be able to turn the check off");
        assertFalse(config.updatesNotifyAdmins());
        assertEquals(6, config.updatesCheckIntervalHours());
        assertEquals("whitelist rolesync", config.disabledModules());
    }

    @Test
    @DisplayName("a file that predates these keys reads their defaults, not zero")
    void missingKnobsDefaultRatherThanZero(@TempDir Path dir) throws IOException {
        write(dir, "endpoint: https://bot.example\ntoken: shhh\n");

        BootstrapConfig config = storeIn(dir).load();

        assertEquals(BootstrapConfig.DEFAULT_TIMEOUT_MS, config.timeoutMs());
        assertEquals(BootstrapConfig.DEFAULT_RETRIES, config.retries());
        assertTrue(config.updatesCheckEnabled(),
                "an absent updatesCheckEnabled must default ON — a pre-1e file did not silently "
                        + "disable its own update check");
        assertEquals("", config.disabledModules());
    }

    @Test
    void everyFieldIsRead(@TempDir Path dir) throws IOException {
        write(dir, "endpoint: https://bot.example/\n"
                + "tokenId: tok_abc\n"
                + "token: shhh\n"
                + "serverId: creative\n"
                + "role: gatekeeper\n"
                + "debug: true\n");

        BootstrapConfig config = storeIn(dir).load();

        assertEquals("https://bot.example", config.endpoint(), "the trailing slash is normalised away");
        assertEquals("tok_abc", config.tokenId());
        assertEquals("shhh", config.token());
        assertEquals("creative", config.serverId());
        assertEquals(ServerRole.GATEKEEPER, config.role());
        assertTrue(config.debug());
        assertTrue(config.isConfigured());
    }

    @Test
    @DisplayName("a key this version does not know survives a load/save cycle")
    void unknownKeysArePreserved(@TempDir Path dir) throws IOException {
        write(dir, "endpoint: https://bot.example\n"
                + "tokenId: tok_abc\n"
                + "token: shhh\n"
                + "futureFeature: enabled\n");

        BootstrapStore store = storeIn(dir);
        BootstrapConfig config = store.load();
        store.save(config.toBuilder().serverId("survival").build());

        String written = read(dir);
        assertTrue(written.contains("futureFeature"),
                "rolling a fleet back a version must not delete the newer version's settings:\n" + written);
        assertTrue(written.contains("survival"));
    }

    @Test
    @DisplayName("unknown keys survive a save that was never preceded by a load")
    void unknownKeysSurviveASaveWithoutALoad(@TempDir Path dir) throws IOException {
        write(dir, "endpoint: https://bot.example\n"
                + "tokenId: tok_abc\n"
                + "token: shhh\n"
                + "futureFeature: enabled\n");

        // A setup flow writing a fresh config, or a second store over the same path. Remembering
        // the unknown keys from an earlier load() would silently delete them here.
        storeIn(dir).save(BootstrapConfig.builder()
                .endpoint("https://bot.example")
                .tokenId("tok_abc")
                .token("shhh")
                .serverId("survival")
                .build());

        assertTrue(read(dir).contains("futureFeature"),
                "the store must not need load-before-save to be non-destructive:\n" + read(dir));
    }

    @Test
    @DisplayName("a key removed from the file between load and save is not resurrected")
    void unknownKeysTrackTheFileNotTheLastLoad(@TempDir Path dir) throws IOException {
        write(dir, "endpoint: https://bot.example\nfutureFeature: enabled\n");
        BootstrapStore store = storeIn(dir);
        BootstrapConfig config = store.load();

        // Something else rewrites the file — another plugin version, an operator with an editor.
        write(dir, "endpoint: https://bot.example\ndifferentFeature: on\n");
        store.save(config);

        String written = read(dir);
        assertTrue(written.contains("differentFeature"), written);
        assertFalse(written.contains("futureFeature"),
                "the file is the source of truth, not a snapshot taken at load time");
    }

    @Test
    @DisplayName("a corrupt existing file does not stop the setup flow writing a good one")
    void saveOverACorruptFileStillWrites(@TempDir Path dir) throws IOException {
        write(dir, "endpoint: [unclosed\n");

        storeIn(dir).save(BootstrapConfig.builder()
                .endpoint("https://bot.example")
                .tokenId("tok")
                .token("shhh")
                .build());

        assertEquals("https://bot.example", storeIn(dir).load().endpoint());
        assertFalse(logger.at(LogLevel.WARN).isEmpty(),
                "losing the old file's unknown keys is worth a line");
    }

    @Test
    @DisplayName("a malformed file is reported and treated as absent")
    void malformedFileDoesNotThrow(@TempDir Path dir) throws IOException {
        write(dir, "endpoint: [unclosed\n");

        BootstrapConfig config = storeIn(dir).load();

        assertFalse(config.isConfigured());
        assertEquals(1, logger.at(LogLevel.SEVERE).size(),
                "the operator has to be told why their server thinks it is unconfigured");
    }

    @Test
    void emptyFileLoadsDefaults(@TempDir Path dir) throws IOException {
        write(dir, "# nothing but a comment\n");

        assertEquals(BootstrapConfig.defaults(), storeIn(dir).load());
    }

    @Test
    @DisplayName("a non-mapping document is refused rather than half-read")
    void scalarDocumentIsRejected(@TempDir Path dir) throws IOException {
        write(dir, "just-a-string\n");

        assertEquals(BootstrapConfig.defaults(), storeIn(dir).load());
        assertEquals(1, logger.at(LogLevel.WARN).size(),
                "an operator whose config is the wrong shape has to be told which file");
        assertTrue(logger.at(LogLevel.WARN).get(0).message.contains("bootstrap.yml"));
    }

    @Test
    @DisplayName("a quoted boolean still means true")
    void quotedBooleanIsRead(@TempDir Path dir) throws IOException {
        write(dir, "debug: \"true\"\n");
        assertTrue(storeIn(dir).load().debug());

        write(dir, "debug: yes\n");
        assertTrue(storeIn(dir).load().debug());

        write(dir, "debug: nonsense\n");
        assertFalse(storeIn(dir).load().debug());
    }

    @Test
    void unknownRoleWarnsAndFallsBackToAuto(@TempDir Path dir) throws IOException {
        write(dir, "role: overlord\n");

        assertEquals(ServerRole.AUTO, storeIn(dir).load().role());
        assertEquals(1, logger.at(LogLevel.WARN).size());
        assertTrue(logger.at(LogLevel.WARN).get(0).message.contains("overlord"),
                "the warning has to quote the value the operator actually typed");
    }

    @Test
    @DisplayName("saving leaves no temp files behind")
    void saveIsAtomicAndTidy(@TempDir Path dir) throws IOException {
        BootstrapStore store = storeIn(dir);
        store.save(BootstrapConfig.builder().endpoint("https://a").tokenId("t").token("s").build());
        store.save(BootstrapConfig.builder().endpoint("https://b").tokenId("t").token("s").build());

        try (Stream<Path> files = Files.list(dir)) {
            List<String> names = files.map(p -> p.getFileName().toString()).collect(Collectors.toList());
            assertEquals(1, names.size(), "a stray .tmp accumulates on every save: " + names);
            assertEquals("bootstrap.yml", names.get(0));
        }
        assertEquals("https://b", storeIn(dir).load().endpoint());
    }

    @Test
    @DisplayName("the file is created even when its directory does not exist yet")
    void saveCreatesTheDirectory(@TempDir Path dir) throws IOException {
        Path nested = dir.resolve("plugins").resolve("Heimdall");
        BootstrapStore store = new BootstrapStore(logger, nested.resolve("bootstrap.yml"));

        store.save(BootstrapConfig.builder().endpoint("https://a").tokenId("t").token("s").build());

        assertTrue(Files.isRegularFile(nested.resolve("bootstrap.yml")));
    }
}
