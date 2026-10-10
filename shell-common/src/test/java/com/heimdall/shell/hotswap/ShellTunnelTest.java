package com.heimdall.shell.hotswap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.api.HeimdallTunnel;
import com.heimdall.core.json.Payload;
import com.heimdall.core.util.Registration;
import com.heimdall.shell.contract.TunnelBackend;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The public SPI's consumer side, which lives in the shell since the hot-swap split (D87): a
 * plugin's reference and subscriptions must survive a swap, and a reply must go through whichever
 * core is running when the plugin answers.
 */
class ShellTunnelTest {

    private final RecordingShellLog log = new RecordingShellLog();
    private final ShellTunnel tunnel = new ShellTunnel(log, "shell-1");

    @Test
    @DisplayName("with no core bound the tunnel is inert: disconnected, drops, fails fast")
    void inertWithoutABackend() {
        assertFalse(tunnel.isConnected());
        assertEquals("shell-1", tunnel.version());
        tunnel.publish("anything", null);
        CompletableFuture<Payload> pending = tunnel.request("anything", null, 100L);
        assertTrue(pending.isCompletedExceptionally(), "a request with no core must fail fast");
    }

    @Test
    @DisplayName("a handler answers on <type>.result through the backend bound at reply time")
    void replyGoesThroughTheCurrentBackend() {
        final List<Payload> seen = new ArrayList<Payload>();
        final List<HeimdallTunnel.Responder> responders = new ArrayList<HeimdallTunnel.Responder>();
        tunnel.on("trace.probe", new HeimdallTunnel.InboundHandler() {
            @Override
            public void handle(Payload payload, HeimdallTunnel.Responder responder) {
                seen.add(payload);
                responders.add(responder);
            }
        });
        FakeBackend first = new FakeBackend("core-1");
        Registration bound = tunnel.bind(first);

        assertTrue(tunnel.deliver("req-1", "trace.probe",
                Payload.builder().put("uuid", "abc").build()));
        assertEquals("abc", seen.get(0).string("uuid", ""));

        // A swap happens before the plugin answers.
        bound.close();
        FakeBackend second = new FakeBackend("core-2");
        tunnel.bind(second);
        responders.get(0).respond(Payload.builder().put("ok", true).build());

        assertTrue(first.replies.isEmpty(), "a stopped core must never be called");
        assertEquals("req-1/trace.probe.result", second.replies.get(0));
    }

    @Test
    @DisplayName("subscriptions outlive the core that was running when they were made")
    void subscriptionsSurviveASwap() {
        final List<String> calls = new ArrayList<String>();
        tunnel.on("custom.type", new HeimdallTunnel.InboundHandler() {
            @Override
            public void handle(Payload payload, HeimdallTunnel.Responder responder) {
                calls.add("handled");
            }
        });
        Registration first = tunnel.bind(new FakeBackend("core-1"));
        first.close();
        tunnel.bind(new FakeBackend("core-2"));

        assertTrue(tunnel.deliver("id", "custom.type", Payload.empty()));
        assertEquals(1, calls.size());
        assertEquals("core-2", tunnel.version());
    }

    @Test
    @DisplayName("an unsubscribed type is not taken, and a throwing handler is contained")
    void unclaimedAndThrowing() {
        assertFalse(tunnel.deliver("id", "nobody.wants.this", Payload.empty()));

        tunnel.on("boom", new HeimdallTunnel.InboundHandler() {
            @Override
            public void handle(Payload payload, HeimdallTunnel.Responder responder) {
                throw new IllegalStateException("consumer is broken");
            }
        });
        assertTrue(tunnel.deliver("id", "boom", Payload.empty()));
        assertTrue(log.errors().get(0).contains("boom"), "the failure names the type: " + log.lines());
    }

    @Test
    @DisplayName("closing a subscription removes it only if it is still the current one")
    void unsubscribeIsIdentityChecked() {
        final List<String> calls = new ArrayList<String>();
        Registration first = tunnel.on("shared", new HeimdallTunnel.InboundHandler() {
            @Override
            public void handle(Payload payload, HeimdallTunnel.Responder responder) {
                calls.add("first");
            }
        });
        tunnel.on("shared", new HeimdallTunnel.InboundHandler() {
            @Override
            public void handle(Payload payload, HeimdallTunnel.Responder responder) {
                calls.add("second");
            }
        });
        first.close();
        tunnel.deliver("id", "shared", Payload.empty());
        assertEquals(1, calls.size());
        assertEquals("second", calls.get(0));
    }

    @Test
    @DisplayName("unbinding an old backend after a new one bound leaves the new one in place")
    void unbindIsCompareAndClear() {
        Registration first = tunnel.bind(new FakeBackend("core-1"));
        tunnel.bind(new FakeBackend("core-2"));
        first.close();
        assertEquals("core-2", tunnel.version());
    }

    /** A backend that records replies as {@code id/type}. */
    static final class FakeBackend implements TunnelBackend {

        final String version;
        final List<String> replies = new ArrayList<String>();

        FakeBackend(String version) {
            this.version = version;
        }

        @Override
        public String version() {
            return version;
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public void publish(String type, Payload payload) {
        }

        @Override
        public CompletableFuture<Payload> request(String type, Payload payload, long timeoutMs) {
            return CompletableFuture.completedFuture(Payload.empty());
        }

        @Override
        public void reply(String requestId, String type, Payload payload) {
            replies.add(requestId + "/" + type);
        }
    }
}
