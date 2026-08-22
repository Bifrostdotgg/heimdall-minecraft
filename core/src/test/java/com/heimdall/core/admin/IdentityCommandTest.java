package com.heimdall.core.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.BuildConstants;
import com.heimdall.core.config.BootstrapConfig;
import com.heimdall.core.config.BootstrapStore;
import com.heimdall.core.config.IdentityCheckPolicy;
import com.heimdall.core.config.ServerRole;
import com.heimdall.core.identity.InstanceFingerprint;
import com.heimdall.core.log.RecordingLogger;
import com.heimdall.core.testing.Await;
import com.heimdall.core.testing.FakeCommandSource;
import com.heimdall.core.testing.FakePlatform;
import com.heimdall.core.testing.RecordingCommands;
import com.heimdall.core.util.Registration;
import com.heimdall.core.wiring.HeimdallRuntime;
import com.heimdall.core.wiring.IdentityGuard;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code /hd identity}, driven the way a console drives it.
 *
 * <p>The fingerprint is injected rather than detected. A test that fingerprinted the machine it runs
 * on would assert on a CI runner's host name, and the interesting cases are precisely the ones where
 * the recorded value and the current one disagree, which cannot be arranged by hand on a real box.
 */
class IdentityCommandTest {

    private static final InstanceFingerprint THIS_BOX =
            InstanceFingerprint.host("box-a", "/srv/a/plugins/heimdall");
    private static final InstanceFingerprint PANEL =
            InstanceFingerprint.panel("9f0b1a2c-dead-beef");

    private final RecordingLogger logger = new RecordingLogger(true);

    @TempDir
    Path dataDir;

    private FakePlatform platform;
    private BootstrapStore store;
    private HeimdallRuntime runtime;
    private Registration installed = Registration.NONE;
    private FakeCommandSource admin;

    @BeforeEach
    void setUp() {
        platform = new FakePlatform(ServerRole.STANDALONE, dataDir);
        store = new BootstrapStore(logger, dataDir.resolve("bootstrap.yml"));
        admin = FakeCommandSource.player("Adam").grant(AdminCommand.PERMISSION);
    }

    @AfterEach
    void tearDown() {
        installed.close();
        if (runtime != null) {
            runtime.close();
        }
    }

    private static BootstrapConfig.Builder credentials() {
        return BootstrapConfig.builder()
                .endpoint("https://bot.example")
                .tokenId("tok_abc")
                .token("shhh")
                .serverId("srv_survival")
                .role(ServerRole.GATEKEEPER)
                .guildId("123456789");
    }

    /** Writes a config, then builds and installs a runtime that reads it as this machine. */
    private void boot(BootstrapConfig config, InstanceFingerprint asThisMachine) throws IOException {
        if (config != null) {
            store.save(config);
        }
        runtime = HeimdallRuntime.builder(logger, platform)
                .bootstrapStore(store)
                .instanceFingerprint(asThisMachine)
                .build();
        installed = AdminCommand.install(
                platform.commandRegistry(),
                AdminContext.builder(runtime)
                        .role(ServerRole.STANDALONE)
                        .pluginVersion(BuildConstants.VERSION)
                        .build(),
                "hd",
                Collections.singletonList("heimdall"));
    }

    private List<String> say(String... args) {
        admin.clearMessages();
        assertTrue(platform.commandRegistry().run(admin, "hd", args),
                "the handler must claim the invocation");
        return new ArrayList<String>(admin.messageText());
    }

    private static boolean anyContains(List<String> lines, String needle) {
        for (String line : lines) {
            if (line.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    @Nested
    @DisplayName("/hd identity")
    class Show {

        @Test
        @DisplayName("a server that was never set up has nothing bound")
        void notSetUp() throws IOException {
            boot(null, THIS_BOX);

            List<String> lines = say("identity");

            assertTrue(anyContains(lines, "not set up"), lines.toString());
            assertTrue(anyContains(lines, "host:box-a|/srv/a/plugins/heimdall"),
                    "the current fingerprint is still worth showing: " + lines);
        }

        @Test
        @DisplayName("a matching fingerprint reads as bound, with no remedy to offer")
        void bound() throws IOException {
            boot(credentials().instanceFingerprint(THIS_BOX.value()).build(), THIS_BOX);

            List<String> lines = say("identity");

            assertTrue(anyContains(lines, "bound to this instance"), lines.toString());
            assertFalse(anyContains(lines, "identity adopt"),
                    "there is nothing wrong, so nothing to fix: " + lines);
        }

        @Test
        @DisplayName("a mismatch names both fingerprints and both ways out")
        void mismatch() throws IOException {
            boot(credentials().instanceFingerprint("panel:the-original").build(), PANEL);

            List<String> lines = say("identity");

            assertTrue(anyContains(lines, "MISMATCH"), lines.toString());
            assertTrue(anyContains(lines, "panel:the-original"), "the recorded value: " + lines);
            assertTrue(anyContains(lines, "panel:9f0b1a2c-dead-beef"), "this machine: " + lines);
            assertTrue(anyContains(lines, "identity adopt"), lines.toString());
            assertTrue(anyContains(lines, "identity reset confirm"), lines.toString());
        }

        @Test
        @DisplayName("the policy is shown, because it decides whether a mismatch is fatal")
        void showsPolicy() throws IOException {
            boot(credentials()
                    .identityCheck(IdentityCheckPolicy.STRICT)
                    .instanceFingerprint(THIS_BOX.value())
                    .build(), THIS_BOX);

            assertTrue(anyContains(say("identity"), "strict"), "the policy line");
        }

        @Test
        @DisplayName("the status screen carries a one-line version")
        void statusMentionsIdentity() throws IOException {
            boot(credentials().instanceFingerprint("panel:the-original").build(), PANEL);

            List<String> lines = say("status");

            assertTrue(anyContains(lines, "identity: "), lines.toString());
            assertTrue(anyContains(lines, "MISMATCH"), lines.toString());
        }
    }

    @Nested
    @DisplayName("/hd identity adopt")
    class Adopt {

        @Test
        @DisplayName("rebinds the file to this machine")
        void rebinds() throws IOException {
            boot(credentials().instanceFingerprint("host:box-b|/srv/b").build(), THIS_BOX);

            say("identity", "adopt");

            Await.until("the new fingerprint to be written",
                    () -> THIS_BOX.value().equals(store.load().instanceFingerprint()));
            Await.until("the runtime to agree it is bound",
                    () -> runtime.identity().state() == IdentityGuard.State.BOUND);
            assertEquals("shhh", store.load().token(), "adopting must not touch the credentials");
        }

        @Test
        @DisplayName("warns first that a still-running original is the case for reset instead")
        void cautionsOnAMismatch() throws IOException {
            boot(credentials().instanceFingerprint("host:box-b|/srv/b").build(), THIS_BOX);

            List<String> lines = say("identity", "adopt");

            assertTrue(anyContains(lines, "Caution"), lines.toString());
            assertTrue(anyContains(lines, "identity reset confirm"), lines.toString());
        }

        @Test
        @DisplayName("says so and writes nothing when it is already bound here")
        void alreadyBound() throws IOException {
            boot(credentials().instanceFingerprint(THIS_BOX.value()).build(), THIS_BOX);

            List<String> lines = say("identity", "adopt");

            assertTrue(anyContains(lines, "Already bound"), lines.toString());
        }

        @Test
        @DisplayName("a server with no credentials is told to run setup instead")
        void nothingToAdopt() throws IOException {
            boot(null, THIS_BOX);

            assertTrue(anyContains(say("identity", "adopt"), "/hd setup"));
        }
    }

    @Nested
    @DisplayName("/hd identity reset")
    class Reset {

        @Test
        @DisplayName("without confirm it explains and changes nothing")
        void requiresConfirmation() throws IOException {
            boot(credentials().instanceFingerprint(THIS_BOX.value()).build(), THIS_BOX);

            List<String> lines = say("identity", "reset");

            assertTrue(anyContains(lines, "confirm"), lines.toString());
            assertTrue(anyContains(lines, "Nothing has been changed"), lines.toString());
            assertEquals("shhh", store.load().token());
        }

        @Test
        @DisplayName("confirmed, it clears the identity and keeps the settings")
        void clearsTheIdentityOnly() throws IOException {
            boot(credentials().instanceFingerprint(THIS_BOX.value()).build(), THIS_BOX);

            say("identity", "reset", "confirm");

            Await.until("the credentials to be cleared", () -> store.load().token().isEmpty());
            BootstrapConfig onDisk = store.load();
            assertEquals("", onDisk.tokenId());
            assertEquals("", onDisk.serverId());
            assertEquals("", onDisk.guildId());
            assertEquals("", onDisk.instanceFingerprint());
            assertEquals("https://bot.example", onDisk.endpoint(),
                    "the endpoint is a setting, not an identity: retyping it is not a remedy");
            assertEquals(ServerRole.GATEKEEPER, onDisk.role());
            Await.until("the runtime to be back in the not-set-up state", () -> !runtime.isConfigured());
        }

        @Test
        @DisplayName("a server that was never set up is told there is nothing to reset")
        void nothingToReset() throws IOException {
            boot(null, THIS_BOX);

            List<String> lines = say("identity", "reset", "confirm");

            assertTrue(anyContains(lines, "Nothing to reset"), lines.toString());
            assertTrue(anyContains(lines, "/hd setup"), lines.toString());
            assertFalse(java.nio.file.Files.exists(dataDir.resolve("bootstrap.yml")),
                    "clearing nothing must not write a file");
        }

        @Test
        @DisplayName("and points at the command that sets the server up again")
        void tellsTheOperatorWhatIsNext() throws IOException {
            boot(credentials().instanceFingerprint(THIS_BOX.value()).build(), THIS_BOX);

            say("identity", "reset", "confirm");

            Await.until("the follow-up line",
                    () -> anyContains(new ArrayList<String>(admin.messageText()), "/hd setup"));
        }
    }

    @Nested
    @DisplayName("tab completion")
    class Completion {

        @Test
        @DisplayName("offers the two verbs, then confirm")
        void completesVerbs() throws IOException {
            boot(credentials().instanceFingerprint(THIS_BOX.value()).build(), THIS_BOX);
            RecordingCommands commands = platform.commandRegistry();

            assertTrue(commands.complete(admin, "hd", "identity", "").contains("adopt"));
            assertTrue(commands.complete(admin, "hd", "identity", "").contains("reset"));
            assertEquals(Collections.singletonList("confirm"),
                    commands.complete(admin, "hd", "identity", "reset", ""));
        }

        @Test
        @DisplayName("identity itself is offered under /hd")
        void completesTheVerbItself() throws IOException {
            boot(credentials().instanceFingerprint(THIS_BOX.value()).build(), THIS_BOX);

            assertTrue(platform.commandRegistry().complete(admin, "hd", "ident").contains("identity"));
        }
    }
}
