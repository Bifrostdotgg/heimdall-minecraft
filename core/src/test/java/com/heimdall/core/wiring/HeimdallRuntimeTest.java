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

    /**
     * A module that counts its own lifecycle calls.
     *
     * <p>A counter rather than a boolean, because the property that actually matters for the
     * idempotence tests is "enabled exactly once" — a second enable is what a repeated start would
     * produce, and a boolean cannot tell that apart from the first one.
     */
    private static final class Marker implements HeimdallModule {

        int enableCount;
        int disableCount;

        boolean enabled() {
            return enableCount > disableCount;
        }

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
            enableCount++;
        }

        @Override
        public void disable() {
            disableCount++;
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
            assertFalse(marker.enabled());
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
        @DisplayName("builds the tunnel and the api client, and goes discovering rather than idle")
        void discoversTheGuild(@TempDir Path dataDir) throws IOException {
            HeimdallRuntime runtime = runtime(dataDir, configured(dataDir)).build();

            assertTrue(runtime.isConfigured());
            assertNotNull(runtime.api(), "credentials are enough to sign HTTP requests");
            assertNotNull(runtime.tunnel());
            assertFalse(
                    runtime.tunnel().settings().isConfigured(),
                    "the tunnel URL is keyed by guild, and there is no guild yet");
            assertEquals("", runtime.guildId());

            runtime.start();
            assertFalse(runtime.tunnel().isConnected());
            assertTrue(runtime.isDiscoveringGuild(),
                    "a token with no cached guild is the discovering state, not a dead end");
            assertTrue(
                    logger.logged(LogLevel.INFO, "discovering which guild"),
                    "the state must be stated once: " + logger.records());
            runtime.close();
            assertFalse(runtime.isDiscoveringGuild(), "close must stop discovery retrying");
        }

        @Test
        @DisplayName("a cached guild is used without asking, so a restart mid-outage still dials")
        void cachedGuildSkipsDiscovery(@TempDir Path dataDir) throws IOException {
            BootstrapStore store = new BootstrapStore(logger, dataDir.resolve("bootstrap.yml"));
            store.save(BootstrapConfig.builder()
                    .endpoint("https://api.example.invalid")
                    .tokenId("token-id")
                    .token("secret")
                    .serverId("survival")
                    .guildId("123456789012345678")
                    .build());

            HeimdallRuntime runtime = runtime(dataDir, store).build();
            assertEquals("123456789012345678", runtime.guildId());
            assertTrue(runtime.tunnel().settings().isConfigured());

            runtime.start();
            assertFalse(runtime.isDiscoveringGuild(),
                    "the cached guild is the answer; there is nothing to discover");
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
                "a second start must do nothing at all: " + logger.records());
        assertEquals(0, marker.enableCount,
                "nothing is desired without config, so the module must not have started at all");
        runtime.close();
        assertEquals(0, marker.disableCount);
    }

    @Test
    @DisplayName("a second start is inert even on a configured server, tunnel included")
    void startIsIdempotentWhenConfigured(@TempDir Path dataDir) throws IOException {
        BootstrapStore store = new BootstrapStore(logger, dataDir.resolve("bootstrap.yml"));
        store.save(BootstrapConfig.builder()
                .endpoint("http://127.0.0.1:1")
                .tokenId("token-id")
                .token("secret")
                .serverId("survival")
                // Cached, so there is no discovery in the way and start() really does reach the
                // dial. This is the /hd reload risk: reload calls start() again.
                .guildId("123456789012345678")
                .build());
        HeimdallRuntime runtime = runtime(dataDir, store).build();

        runtime.start();

        // Registered BETWEEN the two starts, which is what makes this deterministic rather than a
        // race against an asynchronous connect: if the second start ran ANY of start()'s body —
        // the config load, the reconcile, the dial — this module would be enabled by the reconcile
        // that precedes the dial. It is not, so nothing after the latch ran either.
        Marker marker = new Marker();
        runtime.modules().register(marker);
        runtime.remoteConfig().onConfigPush(com.heimdall.core.json.Payload.builder()
                .put("version", 1)
                .put("modules", com.heimdall.core.json.Payload.builder()
                        .put("marker", com.heimdall.core.json.Payload.builder()
                                .put("enabled", true)
                                .build())
                        .build())
                .build());
        int enabledByThePush = marker.enableCount;

        runtime.start();

        assertEquals(enabledByThePush, marker.enableCount,
                "the second start must not re-run the reconcile — and by the same latch, must not "
                        + "dial a second socket beside the one already connecting");
        runtime.close();
    }

    @Test
    @DisplayName("an Error out of a module's disable still stops the pools")
    void anErrorOnTheWayOutDoesNotSkipTheRest(@TempDir Path dataDir) {
        BootstrapStore store = new BootstrapStore(logger, dataDir.resolve("bootstrap.yml"));
        HeimdallRuntime runtime = runtime(dataDir, store).build();
        runtime.modules().register(new HeimdallModule() {
            @Override
            public String id() {
                return "explodes-on-the-way-out";
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
            }

            @Override
            public void disable() {
                // Not a RuntimeException, deliberately. The failure class that actually escapes on
                // the way out is a NoSuchMethodError from an API that moved between server versions
                // — departures D43/D44/D45 — and a guard that only caught RuntimeException would
                // let it skip the pool shutdown below and, in the platform bootstraps, the console
                // tap detach.
                throw new NoSuchMethodError("org.bukkit.Something.gone()");
            }
        });
        runtime.start();
        runtime.remoteConfig().onConfigPush(com.heimdall.core.json.Payload.builder()
                .put("version", 1)
                .put("modules", com.heimdall.core.json.Payload.builder()
                        .put("explodes-on-the-way-out", com.heimdall.core.json.Payload.builder()
                                .put("enabled", true)
                                .build())
                        .build())
                .build());

        runtime.close();

        assertTrue(runtime.executors().isShutdown(),
                "the pools are the LAST teardown step, so an uncontained Error on the way out is "
                        + "exactly what strands them");
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
