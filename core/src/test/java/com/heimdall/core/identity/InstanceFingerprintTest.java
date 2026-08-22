package com.heimdall.core.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("InstanceFingerprint")
class InstanceFingerprintTest {

    @Nested
    @DisplayName("panel tier")
    class PanelTier {

        @Test
        @DisplayName("a panel uuid wins over anything the host could say")
        void panelWins(@TempDir Path dir) throws IOException {
            Path hostnameFile = write(dir.resolve("etc-hostname"), "from-file\n");
            Map<String, String> env = new HashMap<>();
            env.put(InstanceFingerprint.PANEL_UUID_ENV, "9f0b1a2c-dead-beef");
            env.put("HOSTNAME", "from-env");

            InstanceFingerprint fingerprint =
                    InstanceFingerprint.detect(env, dir, hostnameFile);

            assertEquals(InstanceFingerprint.Tier.PANEL, fingerprint.tier());
            assertEquals("panel:9f0b1a2c-dead-beef", fingerprint.value());
        }

        @Test
        @DisplayName("a blank panel uuid is ignored and the host tier is used")
        void blankPanelUuidIsIgnored(@TempDir Path dir) {
            Map<String, String> env = new HashMap<>();
            env.put(InstanceFingerprint.PANEL_UUID_ENV, "   ");
            env.put("HOSTNAME", "box-a");

            InstanceFingerprint fingerprint =
                    InstanceFingerprint.detect(env, dir, dir.resolve("absent"));

            assertEquals(InstanceFingerprint.Tier.HOST, fingerprint.tier());
            assertTrue(fingerprint.value().startsWith("host:box-a|"), fingerprint.value());
        }

        @Test
        @DisplayName("the uuid is trimmed, so a stray newline does not rebind the identity")
        void panelUuidIsTrimmed(@TempDir Path dir) {
            Map<String, String> env =
                    Collections.singletonMap(InstanceFingerprint.PANEL_UUID_ENV, " abc \n");

            InstanceFingerprint fingerprint =
                    InstanceFingerprint.detect(env, dir, dir.resolve("absent"));

            assertEquals("panel:abc", fingerprint.value());
        }
    }

    @Nested
    @DisplayName("host tier")
    class HostTier {

        @Test
        @DisplayName("/etc/hostname is preferred, and only its first line is used")
        void hostnameFileWins(@TempDir Path dir) throws IOException {
            Path hostnameFile = write(dir.resolve("etc-hostname"), "box-a\nignored\n");
            Map<String, String> env = new HashMap<>();
            env.put("HOSTNAME", "box-b");
            env.put("COMPUTERNAME", "box-c");

            InstanceFingerprint fingerprint =
                    InstanceFingerprint.detect(env, dir, hostnameFile);

            assertTrue(fingerprint.value().startsWith("host:box-a|"), fingerprint.value());
        }

        @Test
        @DisplayName("a missing /etc/hostname falls through to HOSTNAME without throwing")
        void missingHostnameFileFallsThroughToEnv(@TempDir Path dir) {
            Map<String, String> env = new HashMap<>();
            env.put("HOSTNAME", "box-b");
            env.put("COMPUTERNAME", "box-c");

            InstanceFingerprint fingerprint =
                    InstanceFingerprint.detect(env, dir, dir.resolve("nothing-here"));

            assertTrue(fingerprint.value().startsWith("host:box-b|"), fingerprint.value());
        }

        @Test
        @DisplayName("COMPUTERNAME is used when there is no file and no HOSTNAME")
        void windowsFallback(@TempDir Path dir) {
            Map<String, String> env = Collections.singletonMap("COMPUTERNAME", "box-c");

            InstanceFingerprint fingerprint =
                    InstanceFingerprint.detect(env, dir, dir.resolve("nothing-here"));

            assertTrue(fingerprint.value().startsWith("host:box-c|"), fingerprint.value());
        }

        @Test
        @DisplayName("with no file and no environment variable the name is given up on, not looked up")
        void unknownHost(@TempDir Path dir) {
            InstanceFingerprint fingerprint =
                    InstanceFingerprint.detect(
                            Collections.emptyMap(), dir, dir.resolve("nothing-here"));

            assertTrue(
                    fingerprint.value().startsWith("host:" + InstanceFingerprint.UNKNOWN_HOST + "|"),
                    fingerprint.value());
        }

        @Test
        @DisplayName("a symlinked data directory fingerprints the same as its target")
        void symlinksResolve(@TempDir Path dir) throws IOException {
            Path target = Files.createDirectories(dir.resolve("real/plugins/heimdall"));
            Path link = dir.resolve("link");
            try {
                Files.createSymbolicLink(link, target);
            } catch (IOException | UnsupportedOperationException noSymlinks) {
                Assumptions.assumeTrue(false, "symlinks unavailable");
            }
            Map<String, String> env = Collections.singletonMap("HOSTNAME", "box-a");

            InstanceFingerprint viaTarget =
                    InstanceFingerprint.detect(env, target, dir.resolve("absent"));
            InstanceFingerprint viaLink =
                    InstanceFingerprint.detect(env, link, dir.resolve("absent"));

            assertEquals(viaTarget.value(), viaLink.value());
            assertEquals(viaTarget, viaLink);
        }

        @Test
        @DisplayName("a data directory that does not exist yet still resolves through a symlink")
        void firstBootUnderASymlinkedMountIsStable(@TempDir Path dir) throws IOException {
            Path realParent = Files.createDirectories(dir.resolve("real/servers"));
            Path link = dir.resolve("mount");
            try {
                Files.createSymbolicLink(link, realParent);
            } catch (IOException | UnsupportedOperationException noSymlinks) {
                Assumptions.assumeTrue(false, "symlinks unavailable");
            }
            Path leafViaLink = link.resolve("survival/plugins/heimdall");
            Map<String, String> env = Collections.singletonMap("HOSTNAME", "box-a");

            InstanceFingerprint firstBoot =
                    InstanceFingerprint.detect(env, leafViaLink, dir.resolve("absent"));
            InstanceFingerprint secondBoot =
                    InstanceFingerprint.detect(env, leafViaLink, dir.resolve("absent"));

            assertEquals(firstBoot, secondBoot,
                    "the first boot must not record a value the second one disagrees with");
            assertEquals(
                    InstanceFingerprint.detect(
                                    env,
                                    realParent.resolve("survival/plugins/heimdall"),
                                    dir.resolve("absent"))
                            .value(),
                    firstBoot.value(),
                    "and it must be the resolved path, not the one with the symlink still in it");
        }

        @Test
        @DisplayName("two directories on the same host fingerprint differently")
        void differentDirectoriesDiffer(@TempDir Path dir) throws IOException {
            Path first = Files.createDirectories(dir.resolve("a"));
            Path second = Files.createDirectories(dir.resolve("b"));
            Map<String, String> env = Collections.singletonMap("HOSTNAME", "box-a");

            assertNotEquals(
                    InstanceFingerprint.detect(env, first, dir.resolve("absent")).value(),
                    InstanceFingerprint.detect(env, second, dir.resolve("absent")).value());
        }

        @Test
        @DisplayName("a null environment and a null directory still produce a value")
        void nullsAreSurvived() {
            InstanceFingerprint fingerprint = InstanceFingerprint.detect(null, null, null);

            assertEquals(
                    "host:" + InstanceFingerprint.UNKNOWN_HOST + "|" + InstanceFingerprint.UNKNOWN_PATH,
                    fingerprint.value());
        }
    }

    @Nested
    @DisplayName("digest")
    class Digest {

        @Test
        @DisplayName("is 64 lower-case hex characters of SHA-256")
        void isSha256Hex() {
            InstanceFingerprint fingerprint = InstanceFingerprint.panel("abc");

            assertEquals(64, fingerprint.digest().length());
            assertTrue(fingerprint.digest().matches("[0-9a-f]{64}"), fingerprint.digest());
            // The digest of the VALUE, which is what the bot receives. Pinned, because changing
            // what gets hashed would silently make every already-recorded instance look new.
            assertEquals(sha256("panel:abc"), fingerprint.digest());
            assertEquals(
                    "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                    sha256("abc"),
                    "and the helper really is SHA-256, against a published vector");
        }

        @Test
        @DisplayName("differs when the value differs")
        void differsWithValue() {
            assertNotEquals(
                    InstanceFingerprint.panel("a").digest(), InstanceFingerprint.panel("b").digest());
        }
    }

    private static Path write(Path file, String contents) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, contents.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static String sha256(String value) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hashed = md.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hashed) {
                hex.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
            }
            return hex.toString();
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
