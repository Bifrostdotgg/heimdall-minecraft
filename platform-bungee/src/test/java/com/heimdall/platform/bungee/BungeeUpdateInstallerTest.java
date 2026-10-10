package com.heimdall.platform.bungee;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.http.model.PluginRelease;
import com.heimdall.core.log.LogLevel;
import com.heimdall.core.log.RecordingLogger;
import com.heimdall.core.testing.JarServer;
import com.heimdall.core.update.DownloadPolicy;
import com.heimdall.core.update.DownloadRefusedException;
import com.heimdall.core.update.InstallOutcome;
import com.heimdall.core.update.UpdateDownloader;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import net.md_5.bungee.api.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The data-directory fallback is for a jar that could not be replaced, never for a download that
 * was refused (departure D87): a checksum or host refusal fetched again into the data directory
 * would either fail a second time or, worse, look to the operator like an install.
 */
class BungeeUpdateInstallerTest {

    private static final byte[] JAR = "PK the new release".getBytes(StandardCharsets.UTF_8);
    private static final byte[] OLD = "PK the running release".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path dir;

    private final RecordingLogger logger = new RecordingLogger(true);
    private JarServer server;
    private Path ownJar;
    private Path dataDirectory;
    private BungeeUpdateInstaller installer;

    @BeforeEach
    void setUp() throws Exception {
        server = new JarServer(JAR);
        Path plugins = Files.createDirectories(dir.resolve("plugins"));
        ownJar = Files.write(plugins.resolve("Heimdall.jar"), OLD);
        dataDirectory = Files.createDirectories(plugins.resolve("Heimdall"));
        final File jarFile = ownJar.toFile();
        Plugin plugin = new Plugin(null, null) {
            @Override
            public File getFile() {
                return jarFile;
            }
        };
        installer = new BungeeUpdateInstaller(logger, plugin, dataDirectory);
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private PluginRelease release(String url, String sha256) {
        return PluginRelease.builder().version("3.1.0").downloadUrl(url).sha256(sha256).build();
    }

    private long jarsInDataDirectory() throws Exception {
        try (Stream<Path> files = Files.list(dataDirectory)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".jar")).count();
        }
    }

    @Test
    @DisplayName("a checksum mismatch is refused once, not retried into the data directory")
    void mismatchIsNotRetried() throws Exception {
        UpdateDownloader downloader = new UpdateDownloader(logger, JarServer.loopbackPolicy());
        String wrong = JarServer.sha256("something else".getBytes(StandardCharsets.UTF_8));

        assertThrows(DownloadRefusedException.class, () -> installer.install(
                release(server.url("/heimdall.jar"), wrong), downloader));

        assertEquals(1, server.requests(), "the refused bytes were fetched again");
        assertEquals(0, jarsInDataDirectory());
        assertArrayEquals(OLD, Files.readAllBytes(ownJar));
        assertFalse(logger.logged(LogLevel.WARN, "falling back"), logger.records().toString());
    }

    @Test
    @DisplayName("a malformed checksum is refused without any fetch, and without the fallback")
    void malformedHashIsNotRetried() throws Exception {
        UpdateDownloader downloader = new UpdateDownloader(logger, JarServer.loopbackPolicy());

        assertThrows(DownloadRefusedException.class, () -> installer.install(
                release(server.url("/heimdall.jar"), "sha256:not-hex"), downloader));

        assertEquals(0, server.requests());
        assertEquals(0, jarsInDataDirectory());
    }

    @Test
    @DisplayName("a URL outside the pinned repository is refused, and not retried")
    void untrustedUrlIsNotRetried() throws Exception {
        UpdateDownloader downloader = new UpdateDownloader(logger, DownloadPolicy.github());

        assertThrows(DownloadRefusedException.class, () -> installer.install(
                release("https://example.com/heimdall.jar", null), downloader));

        assertEquals(0, jarsInDataDirectory());
        assertArrayEquals(OLD, Files.readAllBytes(ownJar));
    }

    @Test
    @DisplayName("a jar that really cannot be replaced still falls back to the data directory")
    void lockedJarFallsBack() throws Exception {
        // A directory where the .part file would go: the write fails the way a locked or
        // read-only plugins directory does, with an ordinary IOException.
        Files.createDirectories(ownJar.resolveSibling("Heimdall.jar.part"));
        Files.write(ownJar.resolveSibling("Heimdall.jar.part").resolve("keep"), OLD);
        UpdateDownloader downloader = new UpdateDownloader(logger, JarServer.loopbackPolicy());

        InstallOutcome outcome = installer.install(
                release(server.url("/heimdall.jar"), JarServer.sha256(JAR)), downloader);

        assertTrue(outcome.installed(), outcome.message());
        assertEquals(dataDirectory.resolve("heimdall-3.1.0.jar"), outcome.target());
        assertArrayEquals(JAR, Files.readAllBytes(outcome.target()));
        assertTrue(logger.logged(LogLevel.WARN, "falling back"), logger.records().toString());
    }
}
