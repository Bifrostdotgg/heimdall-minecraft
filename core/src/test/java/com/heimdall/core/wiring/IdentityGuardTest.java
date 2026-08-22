package com.heimdall.core.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.config.BootstrapConfig;
import com.heimdall.core.config.BootstrapStore;
import com.heimdall.core.config.IdentityCheckPolicy;
import com.heimdall.core.config.ServerRole;
import com.heimdall.core.identity.InstanceFingerprint;
import com.heimdall.core.json.Payload;
import com.heimdall.core.log.LogLevel;
import com.heimdall.core.log.RecordingLogger;
import com.heimdall.core.testing.FakePlatform;
import com.heimdall.core.tunnel.ServerIdentity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The state table, and what the runtime does about each row.
 *
 * <p>The incident behind it: two Purpur servers with a byte-identical {@code bootstrap.yml}, both
 * dialling the tunnel as the same {@code serverId}, evicting each other every few seconds for
 * hours. Everything below is a test that one of them would have stayed off the tunnel.
 */
class IdentityGuardTest {

    private static final InstanceFingerprint THIS_BOX =
            InstanceFingerprint.host("box-a", "/srv/a/plugins/heimdall");
    private static final InstanceFingerprint THIS_PANEL_SERVER =
            InstanceFingerprint.panel("9f0b1a2c-dead-beef");

    private final RecordingLogger logger = new RecordingLogger();

    /** The guild a restart runs on while the bot is unreachable. See BootstrapConfig#guildId. */
    private static final String CACHED_GUILD = "123456789012345678";

    private static BootstrapConfig.Builder credentials() {
        return BootstrapConfig.builder()
                .endpoint("https://api.example.invalid")
                .tokenId("token-id")
                .token("secret")
                .serverId("srv_survival");
    }

    /**
     * Whether {@link HeimdallRuntime#start()} got as far as dialling.
     *
     * <p>The line {@code dial()} logs before it does anything else, on the calling thread. Asserting
     * on the log rather than on {@code isDiscoveringGuild()} is deliberate: the discovery object is
     * built for every configured install whether or not anything ever starts it, so it is true in
     * both of the states this class has to tell apart.
     */
    private boolean dialled() {
        return logger.logged(LogLevel.INFO, "discovering which guild");
    }

    /**
     * Whether the tunnel itself was dialled.
     *
     * <p>{@link #dialled()} only covers the no-cached-guild case, where the tunnel waits for
     * discovery to answer. With a cached guild there is nothing to wait for and the connect happens
     * on the calling thread, which is the case the unblocking paths have to prove.
     */
    private boolean connecting() {
        return logger.logged(LogLevel.INFO, "tunnel connecting to");
    }

    private BootstrapStore store(Path dataDir) {
        return new BootstrapStore(logger, dataDir.resolve("bootstrap.yml"));
    }

    private HeimdallRuntime boot(Path dataDir, BootstrapStore store, InstanceFingerprint machine) {
        HeimdallRuntime runtime =
                HeimdallRuntime.builder(logger, new FakePlatform(ServerRole.STANDALONE, dataDir))
                        .bootstrapStore(store)
                        .instanceFingerprint(machine)
                        .build();
        runtime.start();
        return runtime;
    }

    @Nested
    @DisplayName("the decision")
    class Decisions {

        @Test
        @DisplayName("an install with no credentials has nothing to compare")
        void notSetUp() {
            IdentityGuard.Decision decision =
                    IdentityGuard.evaluate(BootstrapConfig.defaults(), THIS_BOX);

            assertEquals(IdentityGuard.State.NOT_SET_UP, decision.state());
            assertFalse(decision.blocksTunnel());
        }

        @Test
        @DisplayName("off skips the comparison even when the values differ")
        void disabled() {
            IdentityGuard.Decision decision = IdentityGuard.evaluate(credentials()
                    .identityCheck(IdentityCheckPolicy.OFF)
                    .instanceFingerprint("panel:somewhere-else")
                    .build(), THIS_PANEL_SERVER);

            assertEquals(IdentityGuard.State.DISABLED, decision.state());
            assertFalse(decision.blocksTunnel());
        }

        @Test
        @DisplayName("a blank recorded fingerprint is the upgrade path, not a mismatch")
        void adopted() {
            IdentityGuard.Decision decision =
                    IdentityGuard.evaluate(credentials().build(), THIS_BOX);

            assertEquals(IdentityGuard.State.ADOPTED, decision.state());
            assertFalse(decision.blocksTunnel());
        }

        @Test
        void bound() {
            IdentityGuard.Decision decision = IdentityGuard.evaluate(
                    credentials().instanceFingerprint(THIS_BOX.value()).build(), THIS_BOX);

            assertEquals(IdentityGuard.State.BOUND, decision.state());
            assertFalse(decision.isMismatch());
        }

        @Test
        @DisplayName("auto blocks a panel-tier mismatch, because a panel uuid does not drift")
        void autoBlocksPanelTier() {
            IdentityGuard.Decision decision = IdentityGuard.evaluate(
                    credentials().instanceFingerprint("panel:the-original").build(),
                    THIS_PANEL_SERVER);

            assertEquals(IdentityGuard.State.MISMATCH_BLOCKED, decision.state());
            assertTrue(decision.blocksTunnel());
        }

        @Test
        @DisplayName("auto only warns on a host-tier mismatch, because container host names change")
        void autoWarnsOnHostTier() {
            IdentityGuard.Decision decision = IdentityGuard.evaluate(
                    credentials().instanceFingerprint("host:box-b|/srv/b").build(), THIS_BOX);

            assertEquals(IdentityGuard.State.MISMATCH_ADVISORY, decision.state());
            assertFalse(decision.blocksTunnel());
            assertTrue(decision.isMismatch());
        }

        @Test
        @DisplayName("strict blocks a host-tier mismatch too")
        void strictBlocksEverything() {
            IdentityGuard.Decision decision = IdentityGuard.evaluate(credentials()
                    .identityCheck(IdentityCheckPolicy.STRICT)
                    .instanceFingerprint("host:box-b|/srv/b")
                    .build(), THIS_BOX);

            assertEquals(IdentityGuard.State.MISMATCH_BLOCKED, decision.state());
            assertTrue(decision.blocksTunnel());
        }

        @Test
        @DisplayName("the warning names both values and both remedies")
        void warningIsActionable() {
            String warning = IdentityGuard.evaluate(
                    credentials().instanceFingerprint("panel:the-original").build(),
                    THIS_PANEL_SERVER).warning("hd");

            assertTrue(warning.contains("panel:the-original"), warning);
            assertTrue(warning.contains("panel:9f0b1a2c-dead-beef"), warning);
            assertTrue(warning.contains("identity adopt"), warning);
            assertTrue(warning.contains("identity reset confirm"), warning);
            assertTrue(warning.contains("Tunnel not started"), warning);
        }

        @Test
        @DisplayName("the advisory warning says why it connected anyway")
        void advisoryWarningExplainsItself() {
            String warning = IdentityGuard.evaluate(
                    credentials().instanceFingerprint("host:box-b|/srv/b").build(), THIS_BOX)
                    .warning("hdp");

            assertTrue(warning.contains("/hdp identity adopt"), warning);
            assertTrue(warning.contains("Tunnel started anyway"), warning);
            assertTrue(warning.contains("strict"), warning);
        }
    }

    @Nested
    @DisplayName("at boot")
    class AtBoot {

        @Test
        @DisplayName("an install that predates the check binds itself, silently enough, and dials")
        void adoptsOnFirstBoot(@TempDir Path dataDir) throws IOException {
            BootstrapStore store = store(dataDir);
            store.save(credentials().build());

            HeimdallRuntime runtime = boot(dataDir, store, THIS_BOX);

            assertEquals(THIS_BOX.value(), store.load().instanceFingerprint());
            assertEquals(IdentityGuard.State.BOUND, runtime.identity().state(),
                    "adopting is a moment, not a state: what is reported afterwards is the binding");
            assertEquals(THIS_BOX.value(), runtime.identity().recorded());
            assertTrue(logger.logged(LogLevel.INFO, "bound server identity"), logger.records().toString());
            assertTrue(dialled(), "adopting must not stop the server connecting");
            runtime.close();
        }

        @Test
        @DisplayName("a copied directory on a panel does not dial at all")
        void blockedCopyStaysOffTheTunnel(@TempDir Path dataDir) throws IOException {
            BootstrapStore store = store(dataDir);
            store.save(credentials().instanceFingerprint("panel:the-original").build());

            HeimdallRuntime runtime = boot(dataDir, store, THIS_PANEL_SERVER);

            assertEquals(IdentityGuard.State.MISMATCH_BLOCKED, runtime.identity().state());
            assertFalse(dialled(),
                    "guild discovery is the other half of connecting, and must not run either");
            assertFalse(runtime.tunnel().isConnected());
            assertTrue(logger.logged(LogLevel.WARN, "identity mismatch"), logger.records().toString());
            assertTrue(runtime.connectionStatus().contains("identity mismatch"),
                    runtime.connectionStatus());
            assertEquals("panel:the-original", store.load().instanceFingerprint(),
                    "a blocked boot must not quietly rebind the file to itself");
            runtime.close();
        }

        @Test
        @DisplayName("a host-tier mismatch warns and connects anyway, and does not self-heal")
        void advisoryConnectsButDoesNotAdopt(@TempDir Path dataDir) throws IOException {
            BootstrapStore store = store(dataDir);
            store.save(credentials().instanceFingerprint("host:box-b|/srv/b").build());

            HeimdallRuntime runtime = boot(dataDir, store, THIS_BOX);

            assertEquals(IdentityGuard.State.MISMATCH_ADVISORY, runtime.identity().state());
            assertTrue(dialled(), "auto is a warning, not a refusal");
            assertTrue(logger.logged(LogLevel.WARN, "identity mismatch"), logger.records().toString());
            assertEquals("host:box-b|/srv/b", store.load().instanceFingerprint(),
                    "auto-adopting here would let a copy quietly make itself the original");
            runtime.close();
        }

        @Test
        @DisplayName("off writes nothing and says nothing")
        void disabledIsInert(@TempDir Path dataDir) throws IOException {
            BootstrapStore store = store(dataDir);
            store.save(credentials().identityCheck(IdentityCheckPolicy.OFF).build());

            HeimdallRuntime runtime = boot(dataDir, store, THIS_BOX);

            assertEquals(IdentityGuard.State.DISABLED, runtime.identity().state());
            assertEquals("", store.load().instanceFingerprint());
            assertTrue(dialled());
            assertFalse(logger.logged(LogLevel.WARN, "identity mismatch"), logger.records().toString());
            runtime.close();
        }
    }

    @Nested
    @DisplayName("the handshake")
    class Handshake {

        @Test
        @DisplayName("carries the digest, never the readable value")
        void identityCarriesTheDigest(@TempDir Path dataDir) throws IOException {
            BootstrapStore store = store(dataDir);
            store.save(credentials().instanceFingerprint(THIS_BOX.value()).build());
            HeimdallRuntime runtime =
                    HeimdallRuntime.builder(logger, new FakePlatform(ServerRole.STANDALONE, dataDir))
                            .bootstrapStore(store)
                            .instanceFingerprint(THIS_BOX)
                            .identitySource(() -> ServerIdentity.builder()
                                    .serverName("survival")
                                    .platform("bukkit")
                                    .extra(Payload.builder().put("bedrock", true).build())
                                    .build())
                            .build();

            ServerIdentity identity = runtime.identitySource().identity();

            assertEquals(THIS_BOX.digest(), identity.extra().string("instanceFingerprint", ""));
            assertFalse(identity.extra().toString().contains("box-a"),
                    "the host name and the path stay on this server: " + identity.extra());
            assertEquals("survival", identity.serverName(), "the platform's own fields survive");
            assertTrue(identity.extra().string("bedrock", "").length() > 0,
                    "and so does whatever else it declared");
            runtime.close();
        }

        @Test
        @DisplayName("a platform that supplies no identity still supplies none")
        void noIdentitySourceStaysNull(@TempDir Path dataDir) {
            HeimdallRuntime runtime =
                    HeimdallRuntime.builder(logger, new FakePlatform(ServerRole.STANDALONE, dataDir))
                            .bootstrapStore(store(dataDir))
                            .instanceFingerprint(THIS_BOX)
                            .build();

            assertNull(runtime.identitySource(),
                    "'unknown server software' is a state the status command reports");
            runtime.close();
        }
    }

    @Nested
    @DisplayName("on reload")
    class OnReload {

        @Test
        @DisplayName("a file that now says strict stops a tunnel that was running")
        void reloadCanBlock(@TempDir Path dataDir) throws IOException {
            BootstrapStore store = store(dataDir);
            store.save(credentials().instanceFingerprint(THIS_BOX.value()).build());
            HeimdallRuntime runtime = boot(dataDir, store, THIS_BOX);
            assertEquals(IdentityGuard.State.BOUND, runtime.identity().state());

            store.save(credentials()
                    .identityCheck(IdentityCheckPolicy.STRICT)
                    .instanceFingerprint("host:box-b|/srv/b")
                    .build());
            String reply = runtime.reload();

            assertTrue(reply.contains("identity mismatch"), reply);
            assertEquals(IdentityGuard.State.MISMATCH_BLOCKED, runtime.identity().state());
            assertFalse(runtime.tunnel().isConnected());
            runtime.close();
        }

        @Test
        @DisplayName("a corrected file brings the tunnel back without a restart")
        void reloadCanUnblock(@TempDir Path dataDir) throws IOException {
            BootstrapStore store = store(dataDir);
            // With a cached guild there is nothing left to resolve, so an unblocked reload has
            // exactly one thing to do and either does it or does not. This is the shape the bug
            // hid in: every branch below the guard decided there was nothing to do, and the server
            // stayed disconnected until it was restarted.
            store.save(credentials()
                    .guildId(CACHED_GUILD)
                    .instanceFingerprint("panel:the-original")
                    .build());
            HeimdallRuntime runtime = boot(dataDir, store, THIS_PANEL_SERVER);
            assertTrue(runtime.identity().blocksTunnel());
            assertFalse(connecting(), "the blocked boot must not have dialled in the first place");

            store.save(credentials()
                    .guildId(CACHED_GUILD)
                    .instanceFingerprint(THIS_PANEL_SERVER.value())
                    .build());
            String reply = runtime.reload();

            assertFalse(reply.contains("identity mismatch"), reply);
            assertEquals(IdentityGuard.State.BOUND, runtime.identity().state());
            assertTrue(connecting(),
                    "accepting the identity and not connecting is the same outage: " + reply);
            runtime.close();
        }

        @Test
        @DisplayName("a blocked reload still applies the file it read, so a later adopt is not stale")
        void blockedReloadStillAppliesTheFile(@TempDir Path dataDir) throws IOException {
            BootstrapStore store = store(dataDir);
            store.save(credentials()
                    .guildId(CACHED_GUILD)
                    .instanceFingerprint(THIS_PANEL_SERVER.value())
                    .build());
            HeimdallRuntime runtime = boot(dataDir, store, THIS_PANEL_SERVER);
            assertEquals(IdentityGuard.State.BOUND, runtime.identity().state());

            // One edit, two changes: a rotated token, and a fingerprint that now blocks.
            store.save(credentials()
                    .token("rotated")
                    .guildId(CACHED_GUILD)
                    .instanceFingerprint("panel:the-original")
                    .build());
            runtime.reload();
            assertTrue(runtime.identity().blocksTunnel());

            runtime.adoptInstanceIdentity();

            assertEquals("rotated", runtime.tunnel().settings().apiKey(),
                    "the blocked reload consumed the edit, so adopting must not dial the old token");
            assertEquals("rotated", runtime.bootstrap().token());
            runtime.close();
        }

        @Test
        @DisplayName("a guild resolved while the guard is blocking is recorded, not acted on")
        void discoveryCannotDialThroughABlock(@TempDir Path dataDir) throws IOException {
            BootstrapStore store = store(dataDir);
            store.save(credentials()
                    .guildId(CACHED_GUILD)
                    .instanceFingerprint("panel:the-original")
                    .build());
            HeimdallRuntime runtime = boot(dataDir, store, THIS_PANEL_SERVER);
            assertTrue(runtime.identity().blocksTunnel());

            // What GuildDiscovery's callback does when an answer lands. It runs on heimdall-io, so
            // it can arrive at any moment, including after a reload has just blocked the tunnel.
            runtime.adoptGuild("222222222222222222");

            assertEquals("222222222222222222", runtime.guildId(), "the answer is still true");
            assertEquals("222222222222222222", store.load().guildId(),
                    "and still worth caching, so the next boot does not have to ask");
            assertFalse(connecting(), "but nothing may dial around the block");
            runtime.close();
        }

        @Test
        @DisplayName("resetting drops the old guild's cached configuration too")
        void resetClearsTheConfigCache(@TempDir Path dataDir) throws IOException {
            BootstrapStore store = store(dataDir);
            store.save(credentials().instanceFingerprint(THIS_BOX.value()).build());
            HeimdallRuntime runtime = boot(dataDir, store, THIS_BOX);
            runtime.remoteConfig().onConfigPush(Payload.builder()
                    .put("version", 7)
                    .put("modules", Payload.builder()
                            .put("bridge", Payload.builder().put("enabled", true).build())
                            .build())
                    .build());
            Path cache = dataDir.resolve("config-cache.json");
            assertTrue(Files.exists(cache), "the push should have been cached");
            assertTrue(runtime.remoteConfig().moduleEnabled("bridge"));

            runtime.resetIdentity();

            assertFalse(Files.exists(cache),
                    "the next guild must not inherit the previous guild's configuration");
            assertFalse(runtime.remoteConfig().moduleEnabled("bridge"),
                    "and not go on running it until the file is deleted by hand either");
            runtime.close();
        }

        @Test
        @DisplayName("resetting a server that was never set up changes nothing at all")
        void resetOnAFreshInstallIsANoOp(@TempDir Path dataDir) throws IOException {
            BootstrapStore store = store(dataDir);
            HeimdallRuntime runtime = boot(dataDir, store, THIS_BOX);

            runtime.resetIdentity();

            assertFalse(Files.exists(dataDir.resolve("bootstrap.yml")),
                    "a reset must not create the file it exists to clear");
            assertFalse(logger.logged(LogLevel.WARN, "credentials have been cleared"),
                    "and must not announce something that did not happen: " + logger.records());
            runtime.close();
        }

        @Test
        @DisplayName("adopting from the command unblocks and dials")
        void adoptUnblocks(@TempDir Path dataDir) throws IOException {
            BootstrapStore store = store(dataDir);
            store.save(credentials().instanceFingerprint("panel:the-original").build());
            HeimdallRuntime runtime = boot(dataDir, store, THIS_PANEL_SERVER);
            assertTrue(runtime.identity().blocksTunnel());

            IdentityGuard.Decision after = runtime.adoptInstanceIdentity();

            assertEquals(IdentityGuard.State.BOUND, after.state());
            assertEquals(THIS_PANEL_SERVER.value(), store.load().instanceFingerprint());
            assertTrue(dialled(), "adopt has to start what the block prevented");
            runtime.close();
        }

        @Test
        @DisplayName("resetting returns the server to the not-set-up state")
        void resetClearsEverythingIdentifying(@TempDir Path dataDir) throws IOException {
            BootstrapStore store = store(dataDir);
            store.save(credentials()
                    .role(ServerRole.GATEKEEPER)
                    .guildId("123456789012345678")
                    .instanceFingerprint(THIS_BOX.value())
                    .build());
            HeimdallRuntime runtime = boot(dataDir, store, THIS_BOX);

            runtime.resetIdentity();

            BootstrapConfig onDisk = store.load();
            assertEquals("", onDisk.token());
            assertEquals("", onDisk.tokenId());
            assertEquals("", onDisk.serverId());
            assertEquals("", onDisk.guildId());
            assertEquals("", onDisk.instanceFingerprint());
            assertEquals("https://api.example.invalid", onDisk.endpoint());
            assertEquals(ServerRole.GATEKEEPER, onDisk.role());
            assertFalse(runtime.isConfigured());
            assertFalse(runtime.isDiscoveringGuild(), "nothing may keep using the old token");
            assertEquals("", runtime.guildId());
            assertEquals(IdentityGuard.State.NOT_SET_UP, runtime.identity().state());
            runtime.close();
        }
    }
}
