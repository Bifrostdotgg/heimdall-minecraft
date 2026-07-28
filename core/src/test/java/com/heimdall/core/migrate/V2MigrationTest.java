package com.heimdall.core.migrate;

import static com.heimdall.core.migrate.V2ConfigReaderTest.copyFixture;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.config.BootstrapConfig;
import com.heimdall.core.config.BootstrapStore;
import com.heimdall.core.config.ServerRole;
import com.heimdall.core.json.Payload;
import com.heimdall.core.log.LogLevel;
import com.heimdall.core.log.RecordingLogger;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The whole first-boot migration, against real directories and real v2 files.
 *
 * <p>Assertions about the bootstrap deliberately read the <strong>file on disk</strong> as well as
 * the returned object where the difference matters. The guild is the case: it has to land under the
 * {@code guildIdCache} key rather than a bare {@code guildId}, and a returned
 * {@link BootstrapConfig} cannot tell those apart — both round-trip through
 * {@code BootstrapConfig.guildId()}. Only the text says which key was written.
 */
class V2MigrationTest {

    /** Exactly the settings keys {@code WhitelistSettings} reads, in the order they are written. */
    private static final List<String> WHITELIST_SETTING_KEYS = Arrays.asList(
            "cacheWindow", "extendOnJoin", "extendOnLeave", "maxExtensionHours", "prewarmEnabled",
            "prewarmIntervalMinutes", "cleanupIntervalMinutes", "apiFallbackMode", "bypassUuids",
            "apiUnavailableMessage", "apiUnavailableAllowedMessage");

    private final RecordingLogger logger = new RecordingLogger(true);

    private final V2Migration migration = new V2Migration(logger);

    private BootstrapStore storeIn(Path dir) {
        return new BootstrapStore(logger, dir.resolve("bootstrap.yml"));
    }

    private static String readBootstrap(Path dir) throws IOException {
        return new String(Files.readAllBytes(dir.resolve("bootstrap.yml")), StandardCharsets.UTF_8);
    }

    private static Path write(Path dir, String fileName, String content) throws IOException {
        Path target = dir.resolve(fileName);
        Files.write(target, content.getBytes(StandardCharsets.UTF_8));
        return target;
    }

    private static List<String> keys(Payload payload) {
        return new ArrayList<String>(payload.keys());
    }

    // ── End to end ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("a Bukkit config.yml migrates: bootstrap written, guild cached, old file kept")
    void yamlMigratesEndToEnd(@TempDir Path dir) throws IOException {
        Path source = copyFixture("config.yml", dir, "config.yml");
        BootstrapStore store = storeIn(dir);

        MigrationResult result = migration.run(Collections.singletonList(dir), store);

        assertEquals(MigrationResult.Status.MIGRATED, result.status());
        assertEquals(source, result.source());

        BootstrapConfig bootstrap = result.bootstrap();
        assertNotNull(bootstrap);
        assertEquals("https://api.bifrost.gg", bootstrap.endpoint());
        assertEquals("hwl_9f4c1b7ae25d40188c3e6a0f2d5b7c91", bootstrap.token());
        assertEquals("", bootstrap.tokenId(), "v2 has no token id — this is legacy mode");
        assertEquals("survival-01", bootstrap.serverId());
        assertEquals("1046473775487176835", bootstrap.guildId());
        assertEquals(ServerRole.AUTO, bootstrap.role());
        assertTrue(bootstrap.debug());
        assertTrue(result.legacyToken());

        // What is actually on disk, which is the only place the key name is visible.
        String written = readBootstrap(dir);
        assertTrue(written.contains("guildIdCache:"), written);
        assertTrue(written.contains("1046473775487176835"), written);
        assertFalse(written.contains("\nguildId:"), "the bare key is a setting; this is a cache");
        assertTrue(written.contains("tokenId: ''"), written);
        assertEquals(bootstrap, storeIn(dir).load(), "and it reads back as what we built");

        // The v2 file moved, and moved rather than vanished.
        Path backup = dir.resolve("config.yml" + V2Migration.BACKUP_SUFFIX);
        assertEquals(backup, result.backup());
        assertTrue(Files.isRegularFile(backup));
        assertFalse(Files.exists(source));

        assertModulesShape(result.modules());
        Payload whitelist = result.modules().child("whitelist");
        assertTrue(whitelist.bool("enabled", false));
        Payload settings = whitelist.child("settings");
        assertEquals(90L, settings.longValue("cacheWindow", -1L));
        assertEquals(120L, settings.longValue("extendOnJoin", -1L));
        assertEquals(180L, settings.longValue("extendOnLeave", -1L));
        assertEquals(12L, settings.longValue("maxExtensionHours", -1L));
        assertTrue(settings.bool("prewarmEnabled", false));
        assertEquals(15L, settings.longValue("prewarmIntervalMinutes", -1L));
        assertEquals(45L, settings.longValue("cleanupIntervalMinutes", -1L));
        assertEquals("deny", settings.string("apiFallbackMode", ""));
        assertEquals(2, settings.strings("bypassUuids").size());
        assertTrue(settings.string("apiUnavailableMessage", "").startsWith("§c"));

        assertTrue(result.modules().child("rolesync").bool("enabled", false));
        assertFalse(result.modules().child("console").bool("enabled", true));
        Payload updates = result.modules().child("updates");
        assertTrue(updates.bool("enabled", false));
        assertFalse(updates.child("settings").bool("notifyAdmins", true));
        assertEquals(6L, updates.child("settings").longValue("checkIntervalHours", -1L));
    }

    @Test
    @DisplayName("a Velocity config.json migrates the same way")
    void jsonMigratesEndToEnd(@TempDir Path dir) throws IOException {
        Path source = copyFixture("config.json", dir, "config.json");
        BootstrapStore store = storeIn(dir);

        MigrationResult result = migration.run(Collections.singletonList(dir), store);

        assertEquals(MigrationResult.Status.MIGRATED, result.status());
        assertEquals(source, result.source());

        BootstrapConfig bootstrap = result.bootstrap();
        assertNotNull(bootstrap);
        assertEquals("https://api.bifrost.gg", bootstrap.endpoint(), "the trailing slash is normalised");
        assertEquals("hwl_3b8e5d21c74f4a06b19d8e33f0a25c47", bootstrap.token());
        assertEquals("", bootstrap.tokenId());
        assertEquals("proxy-lobby", bootstrap.serverId());
        assertEquals("1046473775487176835", bootstrap.guildId());
        assertEquals(ServerRole.AUTO, bootstrap.role(), "the file format is not a role");
        assertTrue(result.legacyToken());

        String written = readBootstrap(dir);
        assertTrue(written.contains("guildIdCache:"), written);
        assertTrue(written.contains("1046473775487176835"), written);
        assertFalse(written.contains("\nguildId:"));

        Path backup = dir.resolve("config.json" + V2Migration.BACKUP_SUFFIX);
        assertEquals(backup, result.backup());
        assertTrue(Files.isRegularFile(backup));
        assertFalse(Files.exists(source));

        assertModulesShape(result.modules());
        Payload settings = result.modules().child("whitelist").child("settings");
        assertEquals(45L, settings.longValue("cacheWindow", -1L));
        assertEquals(0L, settings.longValue("maxExtensionHours", -1L), "0 is carried, not defaulted");
        assertEquals("allow", settings.string("apiFallbackMode", ""));
        assertEquals(Collections.singletonList("069a79f4-44e9-4726-a5be-fca90e38aaf5"),
                settings.strings("bypassUuids"));

        // The three blocks v2's Velocity build never wrote come across as its code defaults, so an
        // imported document is complete whichever platform it was migrated from.
        assertTrue(settings.bool("prewarmEnabled", false));
        assertEquals(5L, settings.longValue("prewarmIntervalMinutes", -1L));
        assertFalse(result.modules().child("rolesync").bool("enabled", true));
        assertTrue(result.modules().child("console").bool("enabled", false));
        assertTrue(result.modules().child("updates").bool("enabled", false));
        assertEquals(12L, result.modules().child("updates").child("settings")
                .longValue("checkIntervalHours", -1L));
    }

    /** The module ids and the whitelist settings keys, exactly and in order. */
    private static void assertModulesShape(Payload modules) {
        assertEquals(Arrays.asList("whitelist", "rolesync", "console", "updates"), keys(modules));
        for (String id : keys(modules)) {
            assertEquals(Arrays.asList("enabled", "settings"), keys(modules.child(id)),
                    id + " must use the nested {enabled, settings} form");
        }
        assertEquals(WHITELIST_SETTING_KEYS, keys(modules.child("whitelist").child("settings")));
        assertTrue(modules.child("rolesync").child("settings").isEmpty());
        assertTrue(modules.child("console").child("settings").isEmpty());
        assertEquals(Arrays.asList("notifyAdmins", "checkIntervalHours"),
                keys(modules.child("updates").child("settings")));
    }

    // ── The refusals ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("an existing bootstrap.yml stops everything, and the v2 file is not touched")
    void existingBootstrapIsNeverOverwritten(@TempDir Path dir) throws IOException {
        Path source = copyFixture("config.yml", dir, "config.yml");
        BootstrapStore store = storeIn(dir);
        store.save(BootstrapConfig.builder()
                .endpoint("https://already.example")
                .tokenId("tok_live")
                .token("s3cr3t")
                .build());
        String before = readBootstrap(dir);

        MigrationResult result = migration.run(Collections.singletonList(dir), store);

        assertEquals(MigrationResult.Status.ALREADY_CONFIGURED, result.status());
        assertNull(result.source());
        assertNull(result.bootstrap());
        assertTrue(result.modules().isEmpty());
        assertFalse(result.legacyToken());
        assertEquals(before, readBootstrap(dir), "the live bootstrap must be byte-identical");
        assertTrue(Files.isRegularFile(source), "the v2 file is left exactly where it was");
        assertFalse(Files.exists(dir.resolve("config.yml" + V2Migration.BACKUP_SUFFIX)));
    }

    @Test
    @DisplayName("nothing to migrate is silent")
    void nothingFoundIsSilent(@TempDir Path dir) {
        MigrationResult result = migration.run(
                Arrays.asList(dir, dir.resolve("absent")), storeIn(dir));

        assertEquals(MigrationResult.Status.NOT_FOUND, result.status());
        assertNull(result.source());
        assertFalse(storeIn(dir).exists());
        assertTrue(logger.at(LogLevel.INFO).isEmpty(), "a fresh install must say nothing about v2");
        assertTrue(logger.at(LogLevel.WARN).isEmpty());
    }

    @Test
    @DisplayName("a config still on v2's placeholder key writes nothing and renames nothing")
    void placeholderKeyIsUnusable(@TempDir Path dir) throws IOException {
        Path source = write(dir, "config.yml",
                "enabled: false\n"
                        + "api:\n"
                        + "  baseUrl: \"https://api.bifrost.gg\"\n"
                        + "  apiKey: \"" + V2Config.PLACEHOLDER_API_KEY + "\"\n"
                        + "  guildId: \"\"\n");

        MigrationResult result = migration.run(Collections.singletonList(dir), storeIn(dir));

        assertEquals(MigrationResult.Status.UNUSABLE, result.status());
        assertEquals(source, result.source());
        assertNull(result.bootstrap());
        assertTrue(result.modules().isEmpty());
        assertFalse(result.legacyToken());
        assertFalse(storeIn(dir).exists(), "nothing may be written");
        assertTrue(Files.isRegularFile(source), "nothing may be renamed");
        assertFalse(Files.exists(dir.resolve("config.yml" + V2Migration.BACKUP_SUFFIX)));
        assertTrue(logger.logged(LogLevel.WARN, V2Config.PLACEHOLDER_API_KEY));
        assertTrue(result.detail().contains(source.toString()));
    }

    @Test
    @DisplayName("a config the parser refuses writes nothing and renames nothing")
    void malformedConfigIsUnusable(@TempDir Path dir) throws IOException {
        Path source = copyFixture("malformed-config.yml", dir, "config.yml");
        String before = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);

        MigrationResult result = migration.run(Collections.singletonList(dir), storeIn(dir));

        assertEquals(MigrationResult.Status.UNUSABLE, result.status());
        assertEquals(source, result.source());
        assertFalse(storeIn(dir).exists());
        assertEquals(before, new String(Files.readAllBytes(source), StandardCharsets.UTF_8),
                "the operator's broken file must be left exactly as they wrote it");
        assertFalse(Files.exists(dir.resolve("config.yml" + V2Migration.BACKUP_SUFFIX)));
        assertTrue(logger.logged(LogLevel.WARN, "could not parse it"));
    }

    // ── Where it looks, and what it does with what it finds ──────────────────

    @Test
    @DisplayName("directories are searched in order, so a v2 install next door is found")
    void searchesDirectoriesInOrder(@TempDir Path root) throws IOException {
        Path v3Dir = Files.createDirectories(root.resolve("Heimdall"));
        Path v2Dir = Files.createDirectories(root.resolve("HeimdallWhitelist"));
        Path source = copyFixture("config.yml", v2Dir, "config.yml");
        BootstrapStore store = storeIn(v3Dir);

        MigrationResult result = migration.run(Arrays.asList(v3Dir, v2Dir), store);

        assertEquals(MigrationResult.Status.MIGRATED, result.status());
        assertEquals(source, result.source());
        assertTrue(store.exists(), "the bootstrap lands in v3's directory, not v2's");
        assertTrue(Files.isRegularFile(v2Dir.resolve("config.yml" + V2Migration.BACKUP_SUFFIX)),
                "the backup stays beside the file it came from");
    }

    @Test
    @DisplayName("the first directory wins, and config.yml wins over config.json within one")
    void firstHitWins(@TempDir Path root) throws IOException {
        Path first = Files.createDirectories(root.resolve("first"));
        Path second = Files.createDirectories(root.resolve("second"));
        copyFixture("config.json", first, "config.json");
        Path preferred = copyFixture("config.yml", first, "config.yml");
        copyFixture("config.yml", second, "config.yml");

        MigrationResult result = migration.run(Arrays.asList(first, second), storeIn(root));

        assertEquals(preferred, result.source());
        assertTrue(Files.isRegularFile(first.resolve("config.json")), "the runner-up is untouched");
        assertTrue(Files.isRegularFile(second.resolve("config.yml")));
    }

    @Test
    @DisplayName("an existing backup is never overwritten; the new one takes the next free name")
    void backupNamesDoNotCollide(@TempDir Path dir) throws IOException {
        copyFixture("config.yml", dir, "config.yml");
        Path taken = write(dir, "config.yml" + V2Migration.BACKUP_SUFFIX, "an earlier migration\n");

        MigrationResult result = migration.run(Collections.singletonList(dir), storeIn(dir));

        assertEquals(MigrationResult.Status.MIGRATED, result.status());
        assertEquals(dir.resolve("config.yml" + V2Migration.BACKUP_SUFFIX + ".1"), result.backup());
        assertTrue(Files.isRegularFile(result.backup()));
        assertEquals("an earlier migration\n",
                new String(Files.readAllBytes(taken), StandardCharsets.UTF_8),
                "the older backup is the config they were actually running — never clobber it");
    }

    @Test
    @DisplayName("running twice migrates once, then does nothing at all")
    void isIdempotent(@TempDir Path dir) throws IOException {
        copyFixture("config.yml", dir, "config.yml");
        BootstrapStore store = storeIn(dir);

        assertEquals(MigrationResult.Status.MIGRATED,
                migration.run(Collections.singletonList(dir), store).status());
        String afterFirst = readBootstrap(dir);

        MigrationResult second = migration.run(Collections.singletonList(dir), store);

        assertEquals(MigrationResult.Status.ALREADY_CONFIGURED, second.status());
        assertEquals(afterFirst, readBootstrap(dir));
        try (Stream<Path> entries = Files.list(dir)) {
            assertEquals(1, entries.filter(path -> path.getFileName().toString()
                    .startsWith("config.yml" + V2Migration.BACKUP_SUFFIX)).count(),
                    "the second run must not manufacture a second backup");
        }
    }

    @Test
    @DisplayName("the operator is told the imported settings stay inert until the server is claimed")
    void detailExplainsTheInertImport(@TempDir Path dir) throws IOException {
        copyFixture("config.yml", dir, "config.yml");

        MigrationResult result = migration.run(Collections.singletonList(dir), storeIn(dir));

        String detail = result.detail();
        assertTrue(detail.contains(result.source().toString()), detail);
        assertTrue(detail.contains(result.backup().toString()), detail);
        assertTrue(detail.contains("inert"), detail);
        assertTrue(detail.contains("/hd setup"), detail);
        assertTrue(logger.logged(LogLevel.INFO, "inert"), "and it is logged, not just returned");
    }
}
