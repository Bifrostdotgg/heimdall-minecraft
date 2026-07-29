package com.heimdall.core.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.config.BootstrapConfig;
import com.heimdall.core.config.BootstrapStore;
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
 * Where the boot half looks for a v2 install, which is a pair of string constants and nothing else.
 *
 * <p>These names are not ours to choose: each platform derives a plugin's directory from something
 * v2 declared, so the only correct value is v2's own declaration copied out. They are pinned here
 * because a wrong one fails in the single way nothing catches — a v2 → v3 upgrade quietly boots as
 * an unconfigured server, which is indistinguishable from a fresh install unless somebody is looking
 * at the filesystem. That is exactly how {@code heimdallwhitelist} shipped (departure D70).
 *
 * <p>The end-to-end test below is the one that would have caught it: it lays out the real
 * directories and goes through {@link MigrationBoot#migrate}, so the constant is <em>used</em> rather
 * than compared against a second copy of itself.
 */
class MigrationBootTest {

    /** Enough of a v2 config to be migratable — credentials are all {@code hasCredentials} wants. */
    private static final String V2_CONFIG_YML = String.join("\n",
            "enabled: true",
            "api:",
            "  baseUrl: \"https://api.bifrost.gg\"",
            "  apiKey: \"hwl_9f4c1b7ae25d40188c3e6a0f2d5b7c91\"",
            "  guildId: \"1046473775487176835\"",
            "server:",
            "  serverId: \"proxy-lobby\"",
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
        // The real layout: Velocity has made plugins/heimdall/ for the new jar, and the v2 install's
        // config is still next door under v2's id. Nothing about this is contrived — it is what the
        // proxy looks like the moment after the jar is swapped.
        Path v3Directory = Files.createDirectories(plugins.resolve("heimdall"));
        Path v2Directory = Files.createDirectories(plugins.resolve("heimdall-whitelist"));
        Path v2Config = v2Directory.resolve("config.yml");
        Files.write(v2Config, V2_CONFIG_YML.getBytes(StandardCharsets.UTF_8));

        BootstrapStore store = new BootstrapStore(logger, v3Directory.resolve("bootstrap.yml"));
        MigrationResult result = MigrationBoot.migrate(
                logger, store, v3Directory, MigrationBoot.V2_VELOCITY_DIRECTORY);

        assertEquals(MigrationResult.Status.MIGRATED, result.status());
        assertEquals(v2Config, result.source());
        assertTrue(store.exists(), "the bootstrap lands in v3's own directory");

        BootstrapConfig bootstrap = store.load();
        assertEquals("https://api.bifrost.gg", bootstrap.endpoint());
        assertEquals("hwl_9f4c1b7ae25d40188c3e6a0f2d5b7c91", bootstrap.token());
        assertEquals("proxy-lobby", bootstrap.serverId());
        assertTrue(result.legacyToken(), "v2 had no token id, so this is legacy mode");

        assertTrue(Files.isRegularFile(v2Directory.resolve("config.yml" + V2Migration.BACKUP_SUFFIX)),
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
}
