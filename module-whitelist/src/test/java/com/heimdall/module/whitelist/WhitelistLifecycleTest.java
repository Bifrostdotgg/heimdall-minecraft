package com.heimdall.module.whitelist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.json.Payload;
import com.heimdall.core.log.LogLevel;
import com.heimdall.core.module.ModuleState;
import com.heimdall.core.tunnel.Capabilities;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Hot-toggling the module, and what must not accumulate when somebody does it repeatedly.
 *
 * <p>The failure this guards against is not dramatic: a second interceptor that runs the login check
 * twice, a second session listener that slides a cache window twice, a command bound to a closed
 * store. All of them look like the module working, until they do not.
 */
class WhitelistLifecycleTest {

    private static Payload settings() {
        return Payload.builder().put("prewarmEnabled", false).build();
    }

    @Test
    @DisplayName("a toggle leaves exactly one of everything")
    void togglingDoesNotAccumulate(@TempDir Path dir) {
        try (WhitelistHarness h = WhitelistHarness.standalone(dir)) {
            for (int cycle = 0; cycle < 3; cycle++) {
                h.enableWith(settings());
                assertEquals(1, h.loginPipeline.size(), "cycle " + cycle);
                assertEquals(1, h.sessions.joinListenerCount(), "cycle " + cycle);
                assertEquals(1, h.sessions.quitListenerCount(), "cycle " + cycle);
                assertTrue(h.platform.commandRegistry().has("linkdiscord"), "cycle " + cycle);

                h.disableModule();
                assertEquals(0, h.loginPipeline.size(), "cycle " + cycle);
                assertEquals(0, h.sessions.joinListenerCount(), "cycle " + cycle);
                assertEquals(0, h.sessions.quitListenerCount(), "cycle " + cycle);
                assertFalse(h.platform.commandRegistry().has("linkdiscord"), "cycle " + cycle);
            }
        }
    }

    @Test
    @DisplayName("the capability appears and disappears with the module")
    void capabilityFollowsTheState(@TempDir Path dir) {
        try (WhitelistHarness h = WhitelistHarness.standalone(dir)) {
            assertFalse(h.manager.capabilities().contains(Capabilities.WHITELIST));

            h.enableWith(settings());
            assertTrue(h.manager.capabilities().contains(Capabilities.WHITELIST),
                    "declaring a capability for a module that is off means receiving settings "
                            + "nothing reads");
            assertEquals(ModuleState.ENABLED, h.manager.state(HeimdallWhitelistModule.ID));

            h.disableModule();
            assertFalse(h.manager.capabilities().contains(Capabilities.WHITELIST));
            assertEquals(ModuleState.STOPPED, h.manager.state(HeimdallWhitelistModule.ID));
        }
    }

    @Test
    @DisplayName("a re-enable serves the mirror it wrote before, from disk")
    void theMirrorSurvivesAToggle(@TempDir Path dir) {
        try (WhitelistHarness h = WhitelistHarness.standalone(dir)) {
            h.enableWith(settings());
            h.login(WhitelistHarness.ALLOWED, "Steve");
            assertTrue(h.module.mirrorStats().startsWith("1 entries"));

            h.disableModule();
            h.enableWith(settings());

            // The store is closed on disable — which flushes it — and reopened from the same file.
            // A toggle that lost the mirror would mean the next bot outage refused everybody, which
            // is the one moment it matters.
            assertTrue(h.module.mirrorStats().startsWith("1 entries"), h.module.mirrorStats());
        }
    }

    @Test
    @DisplayName("changing a mirror-shaped setting says when it will take effect")
    void mirrorShapedSettingsAreCalledOut(@TempDir Path dir) {
        try (WhitelistHarness h = WhitelistHarness.standalone(dir)) {
            h.enableWith(settings());
            h.logger.clear();

            // Still enabled, so this is a settings change rather than a toggle. cacheWindow is baked
            // into the MirrorPolicy at open time and cannot be applied in place.
            h.enableWith(Payload.builder()
                    .put("prewarmEnabled", false)
                    .put("cacheWindow", 15)
                    .build());

            assertTrue(h.logger.logged(LogLevel.WARN, "switch this module off and on"),
                    "a setting that appears to save and does nothing is worse than one that says "
                            + "when it applies: " + h.logger.records());
        }
    }

    @Test
    @DisplayName("disable is safe on a module that was never enabled, and twice over")
    void disableIsSafeWhenever(@TempDir Path dir) {
        try (WhitelistHarness h = WhitelistHarness.standalone(dir)) {
            h.module.disable();
            h.module.disable();

            assertEquals("whitelist module is not enabled", h.module.mirrorStats());
            // syncNow on a module that is off must not throw either — a status command in 1e can
            // legitimately be run against a disabled module.
            h.module.syncNow();
        }
    }
}
