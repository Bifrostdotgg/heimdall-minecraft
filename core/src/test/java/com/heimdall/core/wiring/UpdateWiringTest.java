package com.heimdall.core.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.heimdall.core.config.BootstrapStore;
import com.heimdall.core.config.ServerRole;
import com.heimdall.core.http.model.PluginRelease;
import com.heimdall.core.log.RecordingLogger;
import com.heimdall.core.testing.FakePlatform;
import com.heimdall.core.update.DownloadPolicy;
import com.heimdall.core.update.InstallOutcome;
import com.heimdall.core.update.UpdateDownloader;
import com.heimdall.core.update.UpdateInstaller;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The updater the platforms actually get downloads from the repository {@code bootstrap.yml}
 * names, not a hardcoded one (departure D87).
 */
class UpdateWiringTest {

    private final RecordingLogger logger = new RecordingLogger(true);

    private static final UpdateInstaller INSTALLER = new UpdateInstaller() {
        @Override
        public InstallOutcome install(PluginRelease release, UpdateDownloader downloader) {
            return InstallOutcome.failed("not in this test");
        }
    };

    private DownloadPolicy wiredPolicy(Path dir, String bootstrapYaml) throws Exception {
        Files.write(dir.resolve("bootstrap.yml"), bootstrapYaml.getBytes(StandardCharsets.UTF_8));
        BootstrapStore store = new BootstrapStore(logger, dir.resolve("bootstrap.yml"));
        HeimdallRuntime runtime = HeimdallRuntime.builder(logger,
                new FakePlatform(ServerRole.STANDALONE, dir)).bootstrapStore(store).build();
        UpdateWiring.Installed installed = null;
        try {
            installed = UpdateWiring.install(logger, "3.0.0", runtime, INSTALLER);
            return installed.service().downloader().policy();
        } finally {
            if (installed != null) {
                installed.periodicChecks().close();
            }
            runtime.close();
        }
    }

    @Test
    @DisplayName("a fork's configured release repository is the one the downloader is pinned to")
    void forkRepositoryIsUsed(@TempDir Path dir) throws Exception {
        DownloadPolicy policy = wiredPolicy(dir,
                "serverId: survival\nupdatesReleaseRepo: Someone/heimdall-fork\n");

        assertEquals("Someone/heimdall-fork", policy.releaseRepo());
    }

    @Test
    @DisplayName("with nothing configured, the downloader is pinned to the official repository")
    void officialByDefault(@TempDir Path dir) throws Exception {
        DownloadPolicy policy = wiredPolicy(dir, "serverId: survival\n");

        assertSame(DownloadPolicy.github(), policy);
    }
}
