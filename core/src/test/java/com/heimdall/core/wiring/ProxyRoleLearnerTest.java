package com.heimdall.core.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.config.ServerRole;
import com.heimdall.core.platform.InstanceRoleDetector;
import com.heimdall.core.tunnel.ProtocolMode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The learner's policy, as a table, and its one rule about threads.
 *
 * <p>Every row is a combination the plugin meets on a real network: a backend behind a plain
 * Velocity, the same backend after Heimdall goes onto the proxy, an operator who wrote a role down,
 * and the proxy itself.
 */
class ProxyRoleLearnerTest {

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

    private static final InstanceRoleDetector BACKEND = signals(false, true);

    @Test
    @DisplayName("a backend told there is no Heimdall proxy learns standalone")
    void noProxyLearnsStandalone() {
        assertEquals(ServerRole.STANDALONE,
                ProxyRoleLearner.decide(ServerRole.AUTO, ServerRole.AUTO, BACKEND, false));
    }

    @Test
    @DisplayName("a backend told there is one again forgets it")
    void aProxyClearsIt() {
        assertEquals(ServerRole.AUTO,
                ProxyRoleLearner.decide(ServerRole.AUTO, ServerRole.STANDALONE, BACKEND, true));
    }

    @Test
    @DisplayName("an answer that matches what is on disk writes nothing")
    void noChangeWritesNothing() {
        assertNull(ProxyRoleLearner.decide(ServerRole.AUTO, ServerRole.STANDALONE, BACKEND, false));
        assertNull(ProxyRoleLearner.decide(ServerRole.AUTO, ServerRole.AUTO, BACKEND, true));
    }

    @Test
    @DisplayName("an operator's role: is never second-guessed")
    void configuredRoleIsLeftAlone() {
        for (ServerRole configured : new ServerRole[] {
                ServerRole.ENFORCER, ServerRole.STANDALONE, ServerRole.GATEKEEPER}) {
            assertNull(ProxyRoleLearner.decide(configured, ServerRole.AUTO, BACKEND, false),
                    configured.wireName());
        }
    }

    @Test
    @DisplayName("the proxy, a server without forwarding, and a platform with no detector learn nothing")
    void nothingToLearn() {
        assertNull(ProxyRoleLearner.decide(
                ServerRole.AUTO, ServerRole.AUTO, signals(true, false), false));
        assertNull(ProxyRoleLearner.decide(
                ServerRole.AUTO, ServerRole.AUTO, signals(false, false), false));
        assertNull(ProxyRoleLearner.decide(ServerRole.AUTO, ServerRole.AUTO, null, false));
    }

    /** Records what the learner hands the runtime, and runs nothing until told to. */
    private static final class RecordingSink implements ProxyRoleLearner.Sink {

        Boolean answer;
        final List<Runnable> submitted = new ArrayList<Runnable>();
        final List<Boolean> learned = new ArrayList<Boolean>();

        @Override
        public Boolean heimdallProxy() {
            return answer;
        }

        @Override
        public void submit(Runnable task) {
            submitted.add(task);
        }

        @Override
        public void learn(boolean heimdallProxy) {
            learned.add(heimdallProxy);
        }
    }

    @Test
    @DisplayName("on V3 the answer is read straight away and the save is handed off the socket thread")
    void v3HandsTheSaveOff() {
        RecordingSink sink = new RecordingSink();
        sink.answer = Boolean.FALSE;
        ProxyRoleLearner learner = new ProxyRoleLearner(sink);

        learner.onModeChanged(ProtocolMode.UNKNOWN, ProtocolMode.V3);
        sink.answer = Boolean.TRUE;

        assertTrue(sink.learned.isEmpty(),
                "learning inline would take the reconfigure lock inside the negotiator's monitor");
        assertEquals(1, sink.submitted.size());
        sink.submitted.get(0).run();
        assertEquals(1, sink.learned.size());
        assertEquals(Boolean.FALSE, sink.learned.get(0),
                "the answer is the one this handshake carried, not whatever is there later");
    }

    @Test
    @DisplayName("no answer, and any mode but V3, does nothing")
    void silenceAndOtherModesAreInert() {
        RecordingSink sink = new RecordingSink();
        ProxyRoleLearner learner = new ProxyRoleLearner(sink);

        learner.onModeChanged(ProtocolMode.UNKNOWN, ProtocolMode.V3);
        sink.answer = Boolean.FALSE;
        learner.onModeChanged(ProtocolMode.UNKNOWN, ProtocolMode.V2_COMPAT);
        learner.onModeChanged(ProtocolMode.V3, ProtocolMode.UNKNOWN);

        assertTrue(sink.submitted.isEmpty());
    }
}
