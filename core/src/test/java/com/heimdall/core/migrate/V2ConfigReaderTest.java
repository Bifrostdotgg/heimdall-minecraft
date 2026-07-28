package com.heimdall.core.migrate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.log.LogLevel;
import com.heimdall.core.log.RecordingLogger;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Reading a real v2 config in both of the formats v2 shipped.
 *
 * <p>The fixtures under {@code src/test/resources/v2/} are the actual files, not paraphrases:
 * {@code config.yml} is v2's shipped {@code src/main/resources/config.yml} off the
 * {@code v2-maintenance} branch with an operator's edits applied, and {@code config.json} is the
 * document {@code VelocityConfigProvider.createDefaultConfig()} writes, likewise filled in. That
 * matters, because the interesting half of this class is the keys the Velocity document does
 * <em>not</em> have — v2 never wrote {@code roleSync}, {@code console} or {@code updates} to JSON, so
 * a Velocity network's real settings for those three are code defaults with nothing on disk to read.
 */
class V2ConfigReaderTest {

    private final RecordingLogger logger = new RecordingLogger(true);

    private final V2ConfigReader reader = new V2ConfigReader(logger);

    /**
     * Copies a packaged fixture into a real directory under a chosen name.
     *
     * <p>Via a file rather than the classpath because both this class and {@link V2MigrationTest}
     * exercise {@code Path}-taking API, and because {@link V2ConfigReader#read} dispatches on the
     * file <em>name</em> — a fixture called {@code partial-config.json} has to be able to arrive as
     * {@code config.json} without being copy-pasted into a second resource file.
     */
    static Path copyFixture(String resourceName, Path directory, String fileName) throws IOException {
        Path target = directory.resolve(fileName);
        try (InputStream in = V2ConfigReaderTest.class.getResourceAsStream("/v2/" + resourceName)) {
            assertNotNull(in, "missing test fixture /v2/" + resourceName);
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    private V2Config readFixture(String resourceName, Path directory, String fileName) throws IOException {
        return reader.read(copyFixture(resourceName, directory, fileName));
    }

    private static Path write(Path directory, String fileName, String content) throws IOException {
        Path target = directory.resolve(fileName);
        Files.write(target, content.getBytes(StandardCharsets.UTF_8));
        return target;
    }

    @Nested
    @DisplayName("a complete Bukkit config.yml")
    class Yaml {

        @Test
        @DisplayName("every field v3 carries forward is read")
        void everyFieldIsRead(@TempDir Path dir) throws IOException {
            V2Config config = readFixture("config.yml", dir, "config.yml");
            assertNotNull(config);

            assertEquals("https://api.bifrost.gg", config.baseUrl());
            assertEquals("hwl_9f4c1b7ae25d40188c3e6a0f2d5b7c91", config.apiKey());
            assertEquals("1046473775487176835", config.guildId());
            assertEquals("survival-01", config.serverId());
            assertTrue(config.debug());

            assertTrue(config.pluginEnabled());
            assertEquals(90L, config.cacheWindowMinutes());
            assertEquals(120L, config.extendOnJoinMinutes());
            assertEquals(180L, config.extendOnLeaveMinutes());
            assertEquals(12L, config.maxExtensionHours());
            assertEquals(45L, config.cleanupIntervalMinutes());
            assertTrue(config.prewarmEnabled());
            assertEquals(15L, config.prewarmIntervalMinutes());
            assertEquals("deny", config.apiFallbackMode());
            assertEquals(
                    Arrays.asList("069a79f4-44e9-4726-a5be-fca90e38aaf5",
                            "853c80ef-3c37-49fd-aa49-938b674adae6"),
                    config.bypassUuids());
            assertEquals("§cWhitelist system is temporarily unavailable. Please try again later.",
                    config.apiUnavailableMessage());
            assertEquals("§eWhitelist API is temporarily unavailable. You have been allowed in from cache.",
                    config.apiUnavailableAllowedMessage());
            assertTrue(config.roleSyncEnabled());
            assertFalse(config.consoleStream());
            assertTrue(config.updateCheckEnabled());
            assertFalse(config.updateNotifyAdmins());
            assertEquals(6L, config.updateCheckIntervalHours());

            assertTrue(config.hasCredentials());
            assertTrue(logger.at(LogLevel.WARN).isEmpty(), "a valid v2 config must not warn");
        }

        @Test
        @DisplayName("v2's shipped placeholder key is not credentials")
        void placeholderKeyIsNotCredentials(@TempDir Path dir) throws IOException {
            V2Config config = reader.readYaml(write(dir, "config.yml",
                    "api:\n"
                            + "  baseUrl: \"https://api.bifrost.gg\"\n"
                            + "  apiKey: \"" + V2Config.PLACEHOLDER_API_KEY + "\"\n"));

            assertNotNull(config);
            assertFalse(config.hasCredentials());
        }

        @Test
        @DisplayName("a partial, fully-quoted file falls back to v2's defaults for the rest")
        void partialFileFallsBackToDefaults(@TempDir Path dir) throws IOException {
            V2Config config = readFixture("partial-config.yml", dir, "config.yml");
            assertNotNull(config);

            // Present, and every one of them quoted.
            assertTrue(config.pluginEnabled(), "\"yes\" is true");
            assertTrue(config.debug(), "\"TRUE\" is true");
            assertFalse(config.prewarmEnabled(), "\"off\" is false");
            assertEquals(75L, config.cacheWindowMinutes());
            assertEquals(3L, config.prewarmIntervalMinutes());
            assertEquals(24L, config.updateCheckIntervalHours());
            assertTrue(config.hasCredentials());

            // Absent, so v2's own defaults.
            assertEquals("", config.guildId());
            assertEquals("", config.serverId());
            assertEquals(120L, config.extendOnJoinMinutes());
            assertEquals(180L, config.extendOnLeaveMinutes());
            assertEquals(24L, config.maxExtensionHours());
            assertEquals(30L, config.cleanupIntervalMinutes());
            assertEquals("whitelist-only", config.apiFallbackMode());
            assertEquals(Collections.emptyList(), config.bypassUuids());
            assertFalse(config.roleSyncEnabled());
            assertTrue(config.consoleStream());
            assertTrue(config.updateCheckEnabled());
            assertTrue(config.updateNotifyAdmins());
            assertEquals("§cWhitelist system is temporarily unavailable. Please try again later.",
                    config.apiUnavailableMessage());
        }

        @Test
        @DisplayName("a file the parser refuses is null, and says so once")
        void malformedFileIsNull(@TempDir Path dir) throws IOException {
            assertNull(readFixture("malformed-config.yml", dir, "config.yml"));

            assertEquals(1, logger.at(LogLevel.WARN).size());
            assertTrue(logger.logged(LogLevel.WARN, "Could not parse the v2 config"));
        }

        @Test
        @DisplayName("an empty file, and a root that is not a mapping, are defaults rather than failures")
        void nonMappingRootsAreDefaults(@TempDir Path dir) throws IOException {
            V2Config empty = reader.readYaml(write(dir, "empty.yml", "# nothing but a comment\n"));
            V2Config scalar = reader.readYaml(write(dir, "scalar.yml", "just a string\n"));
            V2Config list = reader.readYaml(write(dir, "list.yml", "- one\n- two\n"));

            for (V2Config config : Arrays.asList(empty, scalar, list)) {
                assertNotNull(config);
                assertFalse(config.hasCredentials());
                assertEquals(60L, config.cacheWindowMinutes());
            }
            assertTrue(logger.at(LogLevel.WARN).isEmpty(), "these parse; they are just not settings");
        }

        @Test
        @DisplayName("a wrong-typed branch costs that branch, not the file")
        void wrongTypedBranchIsIgnored(@TempDir Path dir) throws IOException {
            V2Config config = reader.readYaml(write(dir, "config.yml",
                    "api:\n"
                            + "  baseUrl: \"https://api.bifrost.gg\"\n"
                            + "  apiKey: \"hwl_real\"\n"
                            + "cache: 5\n"
                            + "bypass:\n"
                            + "  uuids: \"not-a-list\"\n"));

            assertNotNull(config);
            assertTrue(config.hasCredentials());
            assertEquals(60L, config.cacheWindowMinutes());
            assertEquals(Collections.emptyList(), config.bypassUuids());
        }

        @Test
        @DisplayName("a file that cannot be opened is null, not an exception")
        void unreadableFileIsNull(@TempDir Path dir) {
            assertNull(reader.readYaml(dir.resolve("does-not-exist.yml")));
            assertTrue(logger.logged(LogLevel.WARN, "Could not read the v2 config"));
        }
    }

    @Nested
    @DisplayName("a complete Velocity config.json")
    class Json {

        @Test
        @DisplayName("every field v3 carries forward is read")
        void everyFieldIsRead(@TempDir Path dir) throws IOException {
            V2Config config = readFixture("config.json", dir, "config.json");
            assertNotNull(config);

            // The trailing slash survives here and is stripped by BootstrapConfig, not by the reader.
            assertEquals("https://api.bifrost.gg/", config.baseUrl());
            assertEquals("hwl_3b8e5d21c74f4a06b19d8e33f0a25c47", config.apiKey());
            assertEquals("1046473775487176835", config.guildId());
            assertEquals("proxy-lobby", config.serverId());
            assertTrue(config.debug());

            assertTrue(config.pluginEnabled());
            assertEquals(45L, config.cacheWindowMinutes());
            assertEquals(240L, config.extendOnJoinMinutes());
            assertEquals(300L, config.extendOnLeaveMinutes());
            assertEquals(0L, config.maxExtensionHours(), "0 is a supported answer, not a missing one");
            assertEquals(15L, config.cleanupIntervalMinutes());
            assertEquals("allow", config.apiFallbackMode());
            assertEquals(Collections.singletonList("069a79f4-44e9-4726-a5be-fca90e38aaf5"),
                    config.bypassUuids());
            assertEquals("§cThe network is having a moment. Try again shortly.",
                    config.apiUnavailableMessage());

            assertTrue(config.hasCredentials());
            assertTrue(logger.at(LogLevel.WARN).isEmpty());
        }

        @Test
        @DisplayName("the four blocks v2 never wrote to JSON come from v2's code defaults")
        void blocksAbsentFromTheVelocityDocumentUseDefaults(@TempDir Path dir) throws IOException {
            V2Config config = readFixture("config.json", dir, "config.json");
            assertNotNull(config);

            assertTrue(config.prewarmEnabled());
            assertEquals(5L, config.prewarmIntervalMinutes());
            assertFalse(config.roleSyncEnabled());
            assertTrue(config.consoleStream());
            assertTrue(config.updateCheckEnabled());
            assertTrue(config.updateNotifyAdmins());
            assertEquals(12L, config.updateCheckIntervalHours());
        }

        @Test
        @DisplayName("a partial file parses its quoted scalars and defaults the rest")
        void partialFileFallsBackToDefaults(@TempDir Path dir) throws IOException {
            V2Config config = readFixture("partial-config.json", dir, "config.json");
            assertNotNull(config);

            assertTrue(config.pluginEnabled(), "\"true\" is true");
            assertFalse(config.consoleStream(), "\"no\" is false");
            assertFalse(config.updateCheckEnabled(), "\"false\" is false");
            assertEquals(75L, config.cacheWindowMinutes());
            assertEquals(45L, config.cleanupIntervalMinutes());
            assertTrue(config.hasCredentials());

            assertEquals("", config.serverId());
            assertEquals(180L, config.extendOnLeaveMinutes());
            assertEquals("whitelist-only", config.apiFallbackMode());
            assertEquals(Collections.emptyList(), config.bypassUuids(), "an explicit null is absent");
            assertTrue(config.updateNotifyAdmins());
        }

        @Test
        @DisplayName("a truncated document is null, and says so once")
        void malformedFileIsNull(@TempDir Path dir) throws IOException {
            assertNull(readFixture("malformed-config.json", dir, "config.json"));

            assertEquals(1, logger.at(LogLevel.WARN).size());
            assertTrue(logger.logged(LogLevel.WARN, "Could not parse the v2 config"));
        }

        @Test
        @DisplayName("a root that is not an object is defaults rather than a failure")
        void nonObjectRootIsDefaults(@TempDir Path dir) throws IOException {
            V2Config config = reader.readJson(write(dir, "config.json", "[1, 2, 3]"));

            assertNotNull(config);
            assertFalse(config.hasCredentials());
            assertEquals(60L, config.cacheWindowMinutes());
            assertTrue(logger.at(LogLevel.WARN).isEmpty());
        }
    }

    @Test
    @DisplayName("read() picks the parser from the extension, and treats anything else as YAML")
    void readDispatchesOnFileName(@TempDir Path dir) throws IOException {
        V2Config json = reader.read(copyFixture("config.json", dir, "config.json"));
        V2Config yaml = reader.read(copyFixture("config.yml", dir, "config.yml"));

        assertNotNull(json);
        assertNotNull(yaml);
        assertEquals("proxy-lobby", json.serverId());
        assertEquals("survival-01", yaml.serverId());

        // YAML is a superset of JSON, so a JSON document under a .yml name still reads. The reverse
        // is why the default branch is the YAML one rather than the JSON one.
        V2Config jsonUnderYamlName = reader.read(copyFixture("config.json", dir, "renamed.yml"));
        assertNotNull(jsonUnderYamlName);
        assertEquals("proxy-lobby", jsonUnderYamlName.serverId());
    }

    @Test
    @DisplayName("V2Config.of(null) is every default, and nothing throws")
    void nullDocumentIsAllDefaults() {
        V2Config config = V2Config.of(null);

        assertFalse(config.hasCredentials());
        assertEquals("", config.baseUrl());
        assertEquals(60L, config.cacheWindowMinutes());
        assertEquals("whitelist-only", config.apiFallbackMode());
        assertFalse(config.toString().contains("hwl_"), "toString must not carry a key");
    }
}
