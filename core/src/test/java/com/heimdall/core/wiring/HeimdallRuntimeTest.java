package com.heimdall.core.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.config.BootstrapConfig;
import com.heimdall.core.config.BootstrapStore;
import com.heimdall.core.config.ServerRole;
import com.heimdall.core.log.LogLevel;
import com.heimdall.core.log.RecordingLogger;
import com.heimdall.core.module.HeimdallModule;
import com.heimdall.core.module.ModuleContext;
import com.heimdall.core.module.ModuleState;
import com.heimdall.core.testing.FakePlatform;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The composition root, and in particular the state a fresh install is in.
 *
 * <p>The not-configured path is the one that matters here. It is the state every server is in for
 * the minute between dropping the jar in and running the setup command, it is the state the boot
 * smoke matrix runs in on all six rows, and it is the one where a mistake looks like "the plugin
 * crashes on install".
 */
class HeimdallRuntimeTest {

    private final RecordingLogger logger = new RecordingLogger();

    private HeimdallRuntime.Builder runtime(Path dataDir, BootstrapStore store) {
        return HeimdallRuntime.builder(logger, new FakePlatform(ServerRole.STANDALONE, dataDir))
                .bootstrapStore(store);
    }

    /** A module that records whether it was started, so "modules still load" is checkable. */
    private static final class Marker implements HeimdallModule {

        boolean enabled;

        @Override
        public String id() {
            return "marker";
        }

        @Override
        public Set<String> capabilities() {
            return Collections.emptySet();
        }

        @Override
        public Set<ServerRole> roles() {
            return Collections.emptySet();
        }

        @Override
        public void enable(ModuleContext context) {
            enabled = true;
        }

        @Override
        public void disable() {
            enabled = false;
        }
    }

    @Nested
    @DisplayName("with no bootstrap.yml")
    class NotConfigured {

        @Test
        @DisplayName("builds, starts and closes without dialling anything")
        void idleButAlive(@TempDir Path dataDir) {
            BootstrapStore store = new BootstrapStore(logger, dataDir.resolve("bootstrap.yml"));
            HeimdallRuntime runtime = runtime(dataDir, store).build();

            assertFalse(runtime.isConfigured(), "an absent bootstrap.yml cannot be configured");
            assertNull(runtime.tunnel(), "nothing to dial, so there should be no tunnel");
            assertNull(runtime.api(), "nothing to sign for, so there should be no api client");
            assertNotNull(runtime.executors(), "the pools exist regardless — modules use them");

            runtime.start();
            runtime.close();
        }

        @Test
        @DisplayName("says how to fix it, once, and names the file")
        void explainsItself(@TempDir Path dataDir) {
            BootstrapStore store = new BootstrapStore(logger, dataDir.resolve("bootstrap.yml"));
            HeimdallRuntime runtime = runtime(dataDir, store).build();
            runtime.start();
            runtime.close();

            assertTrue(
                    logger.logged(LogLevel.INFO, "/hd setup"),
                    "an unconfigured server must say what to run: " + logger.records());
            assertTrue(
                    logger.logged(LogLevel.INFO, "bootstrap.yml"),
                    "it must name the file it is looking for: " + logger.records());
            assertTrue(
                    logger.at(LogLevel.SEVERE).isEmpty(),
                    "a fresh install is not an error: " + logger.at(LogLevel.SEVERE));
        }

        @Test
        @DisplayName("modules still load, on their defaults")
        void modulesStillLoad(@TempDir Path dataDir) {
            BootstrapStore store = new BootstrapStore(logger, dataDir.resolve("bootstrap.yml"));
            HeimdallRuntime runtime = runtime(dataDir, store).build();
            Marker marker = new Marker();
            runtime.modules().register(marker);

            // Nothing has pushed config, so nothing is desired and nothing runs — but the manager
            // is live and the module is registered rather than rejected.
            runtime.start();
            assertEquals(ModuleState.STOPPED, runtime.modules().state("marker"));
            assertFalse(marker.enabled);
            runtime.close();
        }
    }

    @Nested
    @DisplayName("with credentials but no guild id")
    class ConfiguredWithoutGuild {

        private BootstrapStore configured(Path dataDir) throws IOException {
            BootstrapStore store = new BootstrapStore(logger, dataDir.resolve("bootstrap.yml"));
            store.save(BootstrapConfig.builder()
                    .endpoint("https://api.example.invalid")
                    .tokenId("token-id")
                    .token("secret")
                    .serverId("survival")
                    .build());
            return store;
        }

        @Test
        @DisplayName("builds the tunnel and the api client, but stays idle")
        void idleTunnel(@TempDir Path dataDir) throws IOException {
            HeimdallRuntime runtime = runtime(dataDir, configured(dataDir)).build();

            assertTrue(runtime.isConfigured());
            assertNotNull(runtime.api(), "credentials are enough to sign HTTP requests");
            assertNotNull(runtime.tunnel());
            assertFalse(
                    runtime.tunnel().settings().isConfigured(),
                    "the tunnel URL is keyed by guild, and there is no guild yet");

            runtime.start();
            assertFalse(runtime.tunnel().isConnected());
            assertTrue(
                    logger.logged(LogLevel.INFO, "no guild id"),
                    "the idle reason must be stated: " + logger.records());
            runtime.close();
        }

        @Test
        @DisplayName("the bootstrap debug flag reaches the logger")
        void debugFlagApplied(@TempDir Path dataDir) throws IOException {
            BootstrapStore store = new BootstrapStore(logger, dataDir.resolve("bootstrap.yml"));
            store.save(BootstrapConfig.builder()
                    .endpoint("https://api.example.invalid")
                    .tokenId("token-id")
                    .token("secret")
                    .debug(true)
                    .build());

            assertFalse(logger.isDebugEnabled());
            HeimdallRuntime runtime = runtime(dataDir, store).build();
            assertTrue(logger.isDebugEnabled(), "debug: true in bootstrap.yml must take effect");
            runtime.close();
        }
    }

    @Test
    @DisplayName("start is idempotent — the reload path calls it again")
    void startIsIdempotent(@TempDir Path dataDir) {
        BootstrapStore store = new BootstrapStore(logger, dataDir.resolve("bootstrap.yml"));
        HeimdallRuntime runtime = runtime(dataDir, store).build();
        Marker marker = new Marker();
        runtime.modules().register(marker);

        runtime.start();
        int afterFirst = logger.records().size();
        runtime.start();

        assertEquals(afterFirst, logger.records().size(),
                "a second start must do nothing at all — repeating it would double the config "
                        + "listeners and the module registrations, and nothing unwinds the "
                        + "duplicates: " + logger.records());
        runtime.close();
    }

    @Test
    @DisplayName("close after a start that never happened still stops the pools")
    void closeWithoutStart(@TempDir Path dataDir) {
        BootstrapStore store = new BootstrapStore(logger, dataDir.resolve("bootstrap.yml"));
        HeimdallRuntime runtime = runtime(dataDir, store).build();

        // The half-built-enable shape: something threw between build() and start(), and the
        // platform's disable() is the only thing that will ever run again. The pools exist from
        // construction, so this is the case where they would otherwise be stranded.
        runtime.close();

        assertTrue(runtime.executors().isShutdown());
    }

    @Test
    @DisplayName("a module that throws on enable does not stop the runtime starting")
    void aFailedModuleIsContained(@TempDir Path dataDir) throws IOException {
        BootstrapStore store = new BootstrapStore(logger, dataDir.resolve("bootstrap.yml"));
        HeimdallRuntime runtime = runtime(dataDir, store).build();
        runtime.modules().register(new HeimdallModule() {
            @Override
            public String id() {
                return "explodes";
            }

            @Override
            public Set<String> capabilities() {
                return Collections.emptySet();
            }

            @Override
            public Set<ServerRole> roles() {
                return Collections.emptySet();
            }

            @Override
            public void enable(ModuleContext context) {
                throw new IllegalStateException("no");
            }

            @Override
            public void disable() {
            }
        });

        runtime.start();
        runtime.close();

        assertTrue(runtime.executors().isShutdown(),
                "one broken module must not leave the pools running");
    }

    @Test
    @DisplayName("close is idempotent and stops the pools")
    void closeIsIdempotent(@TempDir Path dataDir) {
        BootstrapStore store = new BootstrapStore(logger, dataDir.resolve("bootstrap.yml"));
        HeimdallRuntime runtime = runtime(dataDir, store).build();
        runtime.start();

        runtime.close();
        assertTrue(runtime.executors().isShutdown());
        runtime.close();
        assertTrue(runtime.executors().isShutdown());
    }

    @Test
    @DisplayName("start after close does nothing")
    void startAfterCloseIsInert(@TempDir Path dataDir) {
        BootstrapStore store = new BootstrapStore(logger, dataDir.resolve("bootstrap.yml"));
        HeimdallRuntime runtime = runtime(dataDir, store).build();
        runtime.close();
        runtime.start();
        assertTrue(runtime.executors().isShutdown());
    }
}
