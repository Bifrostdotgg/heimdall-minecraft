package com.heimdall.platform.common;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.admin.AdminContext;
import com.heimdall.core.admin.PunishmentAdmin;
import com.heimdall.core.config.BootstrapStore;
import com.heimdall.core.config.ServerRole;
import com.heimdall.core.log.RecordingLogger;
import com.heimdall.core.testing.FakePlatform;
import com.heimdall.core.wiring.HeimdallRuntime;
import com.heimdall.module.punishments.HeimdallPunishmentsModule;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * That the shipped module set actually reaches the admin command tree.
 *
 * <p>Written after every {@code /hd} punishment verb shipped broken. The tree used to find the
 * punishments module with {@code Class.forName(...).getField("INSTANCE")} against a field that was
 * package-private, so the lookup threw before any command ran and the operator was told
 * "Could not issue the punishment: INSTANCE". A reflective edge is invisible to the compiler and to
 * the conformance rules, and no test crossed it, so nothing failed until a moderator typed
 * {@code /hd ban}.
 *
 * <p>The surface is a compiled interface now, and this is the test that would have caught the
 * original bug: it resolves the module exactly the way the command does, through the context the
 * real bootstraps build.
 */
class HeimdallModulesTest {

    private final RecordingLogger logger = new RecordingLogger(true);

    @TempDir
    Path dataDir;

    private HeimdallRuntime runtime;

    @BeforeEach
    void setUp() {
        runtime = HeimdallRuntime.builder(logger, new FakePlatform(ServerRole.STANDALONE, dataDir))
                .bootstrapStore(new BootstrapStore(logger, dataDir.resolve("bootstrap.yml")))
                .build();
    }

    @AfterEach
    void tearDown() {
        runtime.close();
    }

    @Test
    @DisplayName("registerAll hands the punishments module to the admin tree")
    void punishmentSurfaceIsWired() {
        AdminContext.Builder builder = AdminContext.builder(runtime);

        HeimdallModules.registerAll(runtime, builder);

        PunishmentAdmin punishments = builder.build().punishments();
        assertNotSame(PunishmentAdmin.NONE, punishments,
                "unwired, every /hd ban answers 'the punishments module is not running' forever");
        assertTrue(punishments instanceof HeimdallPunishmentsModule,
                "the module implements the surface itself, so the two cannot disagree about "
                        + "whether it is running");
        assertFalse(punishments.isAvailable(),
                "registered is not enabled: nothing has pushed config yet, and the tree has to say "
                        + "so rather than dispatch into a module with no context");
    }

    @Test
    @DisplayName("an admin context with no modules answers through NONE rather than null")
    void noneIsTheDefault() {
        assertSame(PunishmentAdmin.NONE, AdminContext.builder(runtime).build().punishments());
    }
}
