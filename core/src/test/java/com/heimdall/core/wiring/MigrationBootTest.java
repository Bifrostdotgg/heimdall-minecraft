package com.heimdall.core.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.config.BootstrapConfig;
import com.heimdall.core.config.BootstrapStore;
import com.heimdall.core.log.LogLevel;
import com.heimdall.core.log.RecordingLogger;
import com.heimdall.core.migrate.MigrationResult;
import com.heimdall.core.migrate.V2Migration;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Where the boot half looks for a v2 install, which is two string constants and one deliberate
 * absence.
 *
 * <p>These names are not ours to choose: each platform derives a plugin's directory from something
 * v2 declared, so the only correct value is v2's own declaration copied out — and on BungeeCord,
 * where v2 never shipped a build at all, the only correct value is no directory. They are pinned here
 * because a wrong one fails in the single way nothing catches — a v2 → v3 upgrade quietly boots as
 * an unconfigured server, which is indistinguishable from a fresh install unless somebody is looking
 * at the filesystem. That is exactly how {@code heimdallwhitelist} shipped (departure D70).
 *
 * <p>The end-to-end test below is the one that would have caught it: it lays out the real
 * directories and goes through {@link MigrationBoot#migrate}, so the constant is <em>used</em> rather
 * than compared against a second copy of itself.
 */
class MigrationBootTest {

    /** Enough of v2's Bukkit {@code config.yml} to be migratable. */
    private static final String V2_CONFIG_YML = String.join("\n",
            "enabled: true",
            "api:",
            "  baseUrl: \"https://api.bifrost.gg\"",
            "  apiKey: \"hwl_9f4c1b7ae25d40188c3e6a0f2d5b7c91\"",
            "  guildId: \"1046473775487176835\"",
            "server:",
            "  serverId: \"survival-01\"",
            "");

    /**
     * A <strong>subset</strong> of v2's Velocity {@code config.json} — enough for credentials and
     * the candidate-file fall-through, and no more.
     *
     * <p>Trimmed, and said so rather than claimed as v2's document: {@code VelocityConfigProvider}
     * also wrote {@code messages}, {@code performance} and three further {@code cache} keys
     * unconditionally, and none of them matter to what this class tests, which is <em>where</em> the
     * file was found. Describing a paraphrase as the real thing is the exact genre of mistake that
     * produced departure D70, so the full-fidelity copy is not duplicated here: it lives at
     * {@code core/src/test/resources/v2/config.json} and {@code V2MigrationTest} is what reads it,
     * asserting every translated value. One authoritative copy, not two that can drift.
     *
     * <p>What is <em>not</em> negotiable is that this is JSON rather than the YAML fixture renamed.
     * v2's proxy build really did write a different file, so a YAML fixture would have the Velocity
     * test pass on a document no v2 proxy has ever had — and would never walk the
     * {@code config.yml}-then-{@code config.json} fall-through that finding this one depends on.
     */
    private static final String V2_CONFIG_JSON = String.join("\n",
            "{",
            "  \"enabled\": true,",
            "  \"api\": {",
            "    \"baseUrl\": \"https://api.bifrost.gg/\",",
            "    \"apiKey\": \"hwl_3b8e5d21c74f4a06b19d8e33f0a25c47\",",
            "    \"guildId\": \"1046473775487176835\",",
            "    \"timeout\": 1500,",
            "    \"retries\": 1,",
            "    \"retryDelay\": 1000",
            "  },",
            "  \"server\": {",
            "    \"serverId\": \"proxy-lobby\",",
            "    \"displayName\": \"Bifrost Network\",",
            "    \"publicIp\": \"play.example.net\"",
            "  },",
            "  \"logging\": { \"debug\": true, \"logDecisions\": true },",
            "  \"cache\": { \"enabled\": true, \"cacheWindow\": 45, \"cleanupInterval\": 15 },",
            "  \"advanced\": { \"apiFallbackMode\": \"allow\" },",
            "  \"bypass\": { \"uuids\": [] },",
            "  \"websocket\": { \"enabled\": true }",
            "}",
            "");

    private final RecordingLogger logger = new RecordingLogger(true);

    @Test
    @DisplayName("v2's Velocity directory is its @Plugin id verbatim, hyphen included")
    void velocityDirectoryIsV2sPluginId() {
        // Source of truth: `@Plugin(id = "heimdall-whitelist", name = "HeimdallWhitelist", ...)` on
        // com.heimdall.whitelist.velocity.HeimdallVelocityPlugin, unchanged across every v2 release
        // (tags v2.0.0 … v2.4.0 and the v2-maintenance branch). Velocity names a plugin's data
        // directory after its ID, not its display name, so the hyphen is load-bearing: on a
        // case-sensitive filesystem `heimdallwhitelist` matches nothing any v2 install ever wrote.
        assertEquals("heimdall-whitelist", MigrationBoot.V2_VELOCITY_DIRECTORY);
    }

    @Test
    @DisplayName("v2's Bukkit directory is its plugin.yml name verbatim")
    void bukkitDirectoryIsV2sPluginName() {
        // Source of truth: `name: HeimdallWhitelist` in v2's src/main/resources/plugin.yml. Bukkit
        // names a plugin's data directory after the declared name, so this one really is CamelCase.
        assertEquals("HeimdallWhitelist", MigrationBoot.V2_BUKKIT_DIRECTORY);
    }

    @Test
    @DisplayName("a v2 Velocity install in plugins/heimdall-whitelist/ migrates on first boot")
    void migratesFromV2sVelocityDirectory(@TempDir Path plugins) throws IOException {
        // The real layout, both halves of it: Velocity has made plugins/heimdall/ for the new jar,
        // and the v2 install's config.json is still next door under v2's id. A proxy's v2 config is
        // JSON — there is no config.yml on this platform — so this also walks the candidate-file
        // fall-through rather than stopping on the first name.
        Path v3Directory = Files.createDirectories(plugins.resolve("heimdall"));
        Path v2Directory = Files.createDirectories(plugins.resolve("heimdall-whitelist"));
        Path v2Config = v2Directory.resolve("config.json");
        Files.write(v2Config, V2_CONFIG_JSON.getBytes(StandardCharsets.UTF_8));

        BootstrapStore store = new BootstrapStore(logger, v3Directory.resolve("bootstrap.yml"));
        MigrationResult result = MigrationBoot.migrate(
                logger, store, v3Directory, MigrationBoot.V2_VELOCITY_DIRECTORY);

        assertEquals(MigrationResult.Status.MIGRATED, result.status());
        assertEquals(v2Config, result.source());
        assertTrue(store.exists(), "the bootstrap lands in v3's own directory");

        BootstrapConfig bootstrap = store.load();
        assertEquals("https://api.bifrost.gg", bootstrap.endpoint(), "the trailing slash is normalised");
        assertEquals("hwl_3b8e5d21c74f4a06b19d8e33f0a25c47", bootstrap.token());
        assertEquals("proxy-lobby", bootstrap.serverId());
        assertTrue(result.legacyToken(), "v2 had no token id, so this is legacy mode");

        assertTrue(Files.isRegularFile(v2Directory.resolve("config.json" + V2Migration.BACKUP_SUFFIX)),
                "the v2 file is kept beside where it was found");
        assertFalse(Files.exists(v2Config));
    }

    @Test
    @DisplayName("a v2 Bukkit install in plugins/HeimdallWhitelist/ migrates on first boot")
    void migratesFromV2sBukkitDirectory(@TempDir Path plugins) throws IOException {
        Path v3Directory = Files.createDirectories(plugins.resolve("Heimdall"));
        Path v2Directory = Files.createDirectories(plugins.resolve("HeimdallWhitelist"));
        Files.write(v2Directory.resolve("config.yml"), V2_CONFIG_YML.getBytes(StandardCharsets.UTF_8));

        BootstrapStore store = new BootstrapStore(logger, v3Directory.resolve("bootstrap.yml"));
        MigrationResult result = MigrationBoot.migrate(
                logger, store, v3Directory, MigrationBoot.V2_BUKKIT_DIRECTORY);

        assertEquals(MigrationResult.Status.MIGRATED, result.status());
        assertEquals(v2Directory.resolve("config.yml"), result.source());
        assertTrue(store.exists());
    }

    @Test
    @DisplayName("a platform v2 never shipped on searches its own directory and nothing beside it")
    void noV2DirectorySearchesOnlyItsOwn(@TempDir Path plugins) throws IOException {
        // BungeeCord. v2 had exactly two entry points, Bukkit and Velocity, so no Bungee proxy has
        // ever had a v2 directory — but a plugins/ folder full of OTHER plugins' directories is
        // ordinary, and one of them being called something v2-ish must not make this go looking.
        Path v3Directory = Files.createDirectories(plugins.resolve("Heimdall"));
        Path other = Files.createDirectories(plugins.resolve("HeimdallWhitelist"));
        Files.write(other.resolve("config.yml"), V2_CONFIG_YML.getBytes(StandardCharsets.UTF_8));

        BootstrapStore store = new BootstrapStore(logger, v3Directory.resolve("bootstrap.yml"));
        MigrationResult result = MigrationBoot.migrate(
                logger, store, v3Directory, MigrationBoot.NO_V2_DIRECTORY);

        assertEquals(MigrationResult.Status.NOT_FOUND, result.status(),
                "the sibling directory was not in the search list, so nothing there is v2's");
        assertFalse(store.exists());
        assertTrue(Files.isRegularFile(other.resolve("config.yml")),
                "and a file in a directory this platform does not own is not touched");
    }

    @Test
    @DisplayName("...and a config dropped into its own directory by hand still migrates")
    void noV2DirectoryStillMigratesItsOwn(@TempDir Path plugins) throws IOException {
        // The reason the own-directory half is kept rather than skipping the migration outright: an
        // operator standing a proxy up beside their backends can copy a v2 config in, and the rule
        // on every platform is that a file they placed themselves is the one they meant.
        Path v3Directory = Files.createDirectories(plugins.resolve("Heimdall"));
        Files.write(v3Directory.resolve("config.yml"), V2_CONFIG_YML.getBytes(StandardCharsets.UTF_8));

        BootstrapStore store = new BootstrapStore(logger, v3Directory.resolve("bootstrap.yml"));
        MigrationResult result = MigrationBoot.migrate(
                logger, store, v3Directory, MigrationBoot.NO_V2_DIRECTORY);

        assertEquals(MigrationResult.Status.MIGRATED, result.status());
        assertEquals(v3Directory.resolve("config.yml"), result.source());
        assertTrue(store.exists());
    }

    @Test
    @DisplayName("the near-miss diagnostic does not fire on an ordinary proxy plugins/ folder")
    void nearMissStaysQuietForAShippedJar(@TempDir Path plugins) throws IOException {
        // The shaded jar is called heimdall-whitelist-<version>.jar and it sits in plugins/, right
        // beside the directory being searched — and V2Migration's near-miss test normalises away
        // hyphens and dots, so its NAME matches "heimdall" + "whitelist" exactly. What stops it
        // being reported is the isDirectory() check, and this is what holds that to account: every
        // Bungee proxy in the fleet would otherwise be told on every boot that its own jar might be
        // a v2 install whose config is in the wrong place.
        Path v3Directory = Files.createDirectories(plugins.resolve("Heimdall"));
        Files.write(plugins.resolve("heimdall-whitelist-3.0.0.jar"), new byte[] {0x50, 0x4B, 3, 4});

        BootstrapStore store = new BootstrapStore(logger, v3Directory.resolve("bootstrap.yml"));
        MigrationResult result = MigrationBoot.migrate(
                logger, store, v3Directory, MigrationBoot.NO_V2_DIRECTORY);

        assertEquals(MigrationResult.Status.NOT_FOUND, result.status());
        assertFalse(logger.logged(LogLevel.INFO, "looks like a v2 install"),
                "a fresh install must boot silently; the near-miss line is for an upgrade that "
                        + "went wrong: " + logger.records());
    }

    @Test
    @DisplayName("but a real v2-looking DIRECTORY beside it still gets the near-miss line")
    void nearMissStillFiresForADirectory(@TempDir Path plugins) throws IOException {
        // The other side of the same coin, and the reason NO_V2_DIRECTORY keeps calling migrate()
        // rather than skipping it: an operator who copies plugins/HeimdallWhitelist/ across from a
        // backend gets told where to put the file, instead of a silent unconfigured boot.
        Path v3Directory = Files.createDirectories(plugins.resolve("Heimdall"));
        Files.createDirectories(plugins.resolve("HeimdallWhitelist"));

        BootstrapStore store = new BootstrapStore(logger, v3Directory.resolve("bootstrap.yml"));
        MigrationResult result = MigrationBoot.migrate(
                logger, store, v3Directory, MigrationBoot.NO_V2_DIRECTORY);

        assertEquals(MigrationResult.Status.NOT_FOUND, result.status());
        assertTrue(logger.logged(LogLevel.INFO, "looks like a v2 install"),
                "the diagnostic is the only thing standing between an operator and a boot that "
                        + "looks exactly like a fresh install: " + logger.records());
    }
}
