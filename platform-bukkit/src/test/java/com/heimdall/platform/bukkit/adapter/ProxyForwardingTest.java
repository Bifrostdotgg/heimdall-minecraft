package com.heimdall.platform.bukkit.adapter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.config.ServerRole;
import com.heimdall.core.log.RecordingLogger;
import com.heimdall.core.platform.InstanceRoleDetector;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Proxy detection, against real config files rather than a mocked static.
 *
 * <p>Reading the server's own YAML instead of calling an API is what makes this testable at all,
 * and the fixtures below are the shapes that actually ship: {@code spigot.yml} unchanged since
 * 2013, {@code paper.yml} up to 1.18, and {@code config/paper-global.yml} after the 1.19 split.
 * Every one of them is a file the harness writes and the production code path reads.
 */
class ProxyForwardingTest {

    private final RecordingLogger logger = new RecordingLogger();

    private static void write(Path directory, String relativePath, String content)
            throws IOException {
        Path file = directory.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }

    @Nested
    @DisplayName("reading the server's own configuration")
    class Detection {

        @Test
        @DisplayName("a server with no config files is not behind a proxy")
        void bareDirectory(@TempDir Path dir) {
            assertFalse(ProxyForwarding.isEnabled(dir.toFile(), logger));
        }

        @Test
        @DisplayName("spigot.yml settings.bungeecord: true")
        void bungeecord(@TempDir Path dir) throws IOException {
            write(dir, "spigot.yml", "settings:\n  bungeecord: true\n");
            assertTrue(ProxyForwarding.isEnabled(dir.toFile(), logger));
        }

        @Test
        @DisplayName("spigot.yml settings.bungeecord: false is the default, not a proxy")
        void bungeecordOff(@TempDir Path dir) throws IOException {
            write(dir, "spigot.yml", "settings:\n  bungeecord: false\n  sample-count: 12\n");
            assertFalse(ProxyForwarding.isEnabled(dir.toFile(), logger));
        }

        @Test
        @DisplayName("paper.yml velocity-support, as Paper 1.16 to 1.18 spells it")
        void legacyPaperVelocity(@TempDir Path dir) throws IOException {
            write(dir, "paper.yml",
                    "settings:\n  velocity-support:\n    enabled: true\n    online-mode: true\n");
            assertTrue(ProxyForwarding.isEnabled(dir.toFile(), logger));
        }

        @Test
        @DisplayName("config/paper-global.yml, as Paper 1.19 onwards spells it")
        void modernPaperVelocity(@TempDir Path dir) throws IOException {
            write(dir, "config/paper-global.yml",
                    "proxies:\n  velocity:\n    enabled: true\n    online-mode: true\n");
            assertTrue(ProxyForwarding.isEnabled(dir.toFile(), logger));
        }

        @Test
        @DisplayName("a modern Paper with forwarding off is not a proxy, even with the file present")
        void modernPaperVelocityOff(@TempDir Path dir) throws IOException {
            write(dir, "config/paper-global.yml", "proxies:\n  velocity:\n    enabled: false\n");
            write(dir, "spigot.yml", "settings:\n  bungeecord: false\n");
            assertFalse(ProxyForwarding.isEnabled(dir.toFile(), logger));
        }

        @Test
        @DisplayName("any one file saying yes is enough")
        void anyFileWins(@TempDir Path dir) throws IOException {
            write(dir, "spigot.yml", "settings:\n  bungeecord: false\n");
            write(dir, "paper.yml", "settings:\n  velocity-support:\n    enabled: true\n");
            assertTrue(ProxyForwarding.isEnabled(dir.toFile(), logger));
        }

        @Test
        @DisplayName("a malformed file is not-configured, not a crash")
        void malformedIsTolerated(@TempDir Path dir) throws IOException {
            write(dir, "spigot.yml", "settings:\n  bungeecord: [this is not a boolean\n");
            assertFalse(ProxyForwarding.isEnabled(dir.toFile(), logger));
        }

        @Test
        @DisplayName("a null directory answers rather than throwing")
        void nullDirectory() {
            assertFalse(ProxyForwarding.isEnabled(null, logger));
        }
    }

    @Nested
    @DisplayName("the whole role matrix, end to end")
    class RoleMatrix {

        private ServerRole resolve(ServerRole configured, File directory) {
            return InstanceRoleDetector.resolve(
                    configured,
                    new StubDetector(ProxyForwarding.isEnabled(directory, logger)),
                    logger);
        }

        @Test
        @DisplayName("a bare server is standalone")
        void bareIsStandalone(@TempDir Path dir) {
            org.junit.jupiter.api.Assertions.assertEquals(
                    ServerRole.STANDALONE, resolve(ServerRole.AUTO, dir.toFile()));
        }

        @Test
        @DisplayName("a server behind BungeeCord is an enforcer")
        void behindBungeeIsEnforcer(@TempDir Path dir) throws IOException {
            write(dir, "spigot.yml", "settings:\n  bungeecord: true\n");
            org.junit.jupiter.api.Assertions.assertEquals(
                    ServerRole.ENFORCER, resolve(ServerRole.AUTO, dir.toFile()));
        }

        @Test
        @DisplayName("an explicit role in bootstrap.yml beats what the files say")
        void explicitRoleWins(@TempDir Path dir) throws IOException {
            write(dir, "spigot.yml", "settings:\n  bungeecord: true\n");
            org.junit.jupiter.api.Assertions.assertEquals(
                    ServerRole.STANDALONE, resolve(ServerRole.STANDALONE, dir.toFile()));
        }

        /** A Bukkit server is never the proxy; only the forwarding flag varies. */
        private final class StubDetector implements InstanceRoleDetector {

            private final boolean behindProxy;

            StubDetector(boolean behindProxy) {
                this.behindProxy = behindProxy;
            }

            @Override
            public boolean isProxy() {
                return false;
            }

            @Override
            public boolean isBehindProxy() {
                return behindProxy;
            }
        }
    }
}
