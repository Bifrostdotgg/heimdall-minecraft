package com.heimdall.core.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.config.ServerRole;
import com.heimdall.core.log.LogLevel;
import com.heimdall.core.log.RecordingLogger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The role-detection matrix, exercised without a server anywhere near it.
 *
 * <p>That is the reason the policy lives in core rather than being written twice in the platform
 * modules: the interesting cases are combinations of two booleans and a config value, and none of
 * them needs Bukkit to be true.
 */
class InstanceRoleDetectorTest {

    private final RecordingLogger logger = new RecordingLogger();

    /** A detector that answers exactly what it is told to. */
    private static InstanceRoleDetector signals(final boolean proxy, final boolean behindProxy) {
        return new InstanceRoleDetector() {
            @Override
            public boolean isProxy() {
                return proxy;
            }

            @Override
            public boolean isBehindProxy() {
                return behindProxy;
            }
        };
    }

    @Test
    @DisplayName("a proxy is a gatekeeper")
    void proxyIsGatekeeper() {
        assertEquals(
                ServerRole.GATEKEEPER,
                InstanceRoleDetector.resolve(ServerRole.AUTO, signals(true, false), logger));
    }

    @Test
    @DisplayName("a server configured to trust forwarded identities is an enforcer")
    void behindProxyIsEnforcer() {
        assertEquals(
                ServerRole.ENFORCER,
                InstanceRoleDetector.resolve(ServerRole.AUTO, signals(false, true), logger));
    }

    @Test
    @DisplayName("a server with no proxy forwarding is standalone")
    void noProxyIsStandalone() {
        assertEquals(
                ServerRole.STANDALONE,
                InstanceRoleDetector.resolve(ServerRole.AUTO, signals(false, false), logger));
    }

    @Test
    @DisplayName("being the proxy wins over the forwarding flag, which is meaningless there")
    void proxyBeatsForwarding() {
        assertEquals(
                ServerRole.GATEKEEPER,
                InstanceRoleDetector.resolve(ServerRole.AUTO, signals(true, true), logger));
    }

    @Test
    @DisplayName("an explicitly configured role is never overruled by detection")
    void configuredRoleWins() {
        assertEquals(
                ServerRole.STANDALONE,
                InstanceRoleDetector.resolve(ServerRole.STANDALONE, signals(true, true), logger));
        assertEquals(
                ServerRole.ENFORCER,
                InstanceRoleDetector.resolve(ServerRole.ENFORCER, signals(false, false), logger));
        assertEquals(
                ServerRole.GATEKEEPER,
                InstanceRoleDetector.resolve(ServerRole.GATEKEEPER, signals(false, false), logger));
    }

    @Test
    @DisplayName("a null config and a null detector still resolve to something real")
    void nullsResolve() {
        assertEquals(ServerRole.STANDALONE, InstanceRoleDetector.resolve(null, null, logger));
        assertEquals(
                ServerRole.GATEKEEPER,
                InstanceRoleDetector.resolve(null, signals(true, false), logger));
    }

    @Test
    @DisplayName("the resolved role is stated once, with the reason it was chosen")
    void logsTheDecision() {
        InstanceRoleDetector.resolve(ServerRole.AUTO, signals(false, true), logger);
        assertEquals(1, logger.records().size());
        String message = logger.records().get(0).message;
        assertEquals(LogLevel.INFO, logger.records().get(0).level);
        assertTrue(message.contains("enforcer"), "the role is not in the line: " + message);
        assertTrue(
                message.contains("forwarding"),
                "the line does not say why the role was chosen: " + message);
    }

    @Test
    @DisplayName("a null logger is silence, not a crash")
    void nullLoggerIsTolerated() {
        assertEquals(
                ServerRole.STANDALONE,
                InstanceRoleDetector.resolve(ServerRole.AUTO, signals(false, false), null));
    }
}
