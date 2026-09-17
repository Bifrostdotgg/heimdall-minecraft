package com.heimdall.core.module;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.heimdall.core.concurrent.HeimdallExecutors;
import com.heimdall.core.log.RecordingLogger;
import com.heimdall.core.tunnel.Capabilities;
import com.heimdall.core.tunnel.TunnelClient;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Health is core's own module, and {@code status@1} rides on it rather than being a second toggle.
 */
class HealthModuleTest {

    private final RecordingLogger logger = new RecordingLogger(true);
    private HeimdallExecutors executors;

    @AfterEach
    void tearDown() {
        if (executors != null) {
            executors.shutdown(1000);
        }
    }

    @Test
    @DisplayName("the module advertises health@1 and status@1, and is not a status toggle")
    void advertisesHealthAndStatus() {
        executors = new HeimdallExecutors(logger, 1);
        TunnelClient tunnel = TunnelClient.builder(logger, executors).build();
        try {
            HealthModule module = new HealthModule(tunnel);

            assertEquals(HealthModule.ID, module.id());
            assertEquals("health", module.id());
            Set<String> expected = new LinkedHashSet<String>();
            expected.add(Capabilities.HEALTH);
            expected.add(Capabilities.STATUS);
            assertEquals(expected, module.capabilities());
            assertEquals(Collections.emptySet(), module.roles(),
                    "health (and therefore status) runs under every role");
        } finally {
            tunnel.shutdown();
        }
    }

    @Test
    @DisplayName("vanish@1 is declared only by a platform that can see vanish state")
    void vanishIsDeclaredOnlyWhenTheFlagIsSet() {
        executors = new HeimdallExecutors(logger, 1);
        TunnelClient tunnel = TunnelClient.builder(logger, executors).build();
        try {
            assertFalse(new HealthModule(tunnel, false).capabilities().contains(Capabilities.VANISH),
                    "a proxy sends neither vanish key, so declaring the capability would tell the "
                            + "bot that a row with no 'vanished' flag is a player everyone can see");

            Set<String> expected = new LinkedHashSet<String>();
            expected.add(Capabilities.HEALTH);
            expected.add(Capabilities.STATUS);
            expected.add(Capabilities.VANISH);
            assertEquals(expected, new HealthModule(tunnel, true).capabilities());
            assertEquals("vanish@1", Capabilities.VANISH,
                    "the wire string is a contract with the bot, not an internal name");
        } finally {
            tunnel.shutdown();
        }
    }
}
