package com.heimdall.platform.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.api.HeimdallTunnel;
import com.heimdall.api.HeimdallTunnelProvider;
import com.heimdall.core.config.BootstrapStore;
import com.heimdall.core.config.ServerRole;
import com.heimdall.core.json.Envelope;
import com.heimdall.core.json.Payload;
import com.heimdall.core.log.RecordingLogger;
import com.heimdall.core.platform.PlatformFacade;
import com.heimdall.core.tunnel.TunnelMessageHandler;
import com.heimdall.core.util.Registration;
import com.heimdall.core.wiring.HeimdallRuntime;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The public SPI's dispatch and reply rules.
 *
 * <p>Everything here runs against an unconfigured runtime — no bootstrap.yml, therefore no tunnel —
 * which is not a limitation but the case worth pinning: the SPI is installed on every server
 * whether or not it has been set up, and a consumer must see one consistent set of behaviours
 * rather than a second code path for "Heimdall exists but is not connected".
 */
class TunnelSpiServiceTest {

    private final RecordingLogger logger = new RecordingLogger();

    private HeimdallRuntime runtime;
    private TunnelSpiService service;

    private TunnelSpiService install(Path dataDir) {
        PlatformFacade platform = new StubPlatform(dataDir);
        runtime = HeimdallRuntime.builder(logger, platform)
                .bootstrapStore(new BootstrapStore(logger, dataDir.resolve("bootstrap.yml")))
                .build();
        service = TunnelSpiService.install(logger, runtime);
        return service;
    }

    @AfterEach
    void tearDown() {
        TunnelSpiService.uninstall(service);
        if (runtime != null) {
            runtime.close();
        }
    }

    @Test
    @DisplayName("install publishes the service so any plugin can find it")
    void installPublishes(@TempDir Path dataDir) {
        TunnelSpiService installed = install(dataDir);
        assertSame(installed, HeimdallTunnelProvider.get());

        TunnelSpiService.uninstall(installed);
        assertNull(HeimdallTunnelProvider.get(), "disable must not leave a dead tunnel published");
    }

    @Test
    @DisplayName("uninstalling a service that is not the installed one leaves the holder alone")
    void uninstallIsCompareAndClear(@TempDir Path dataDir) {
        TunnelSpiService installed = install(dataDir);
        HeimdallTunnel other = new HeimdallTunnel() {
            @Override
            public String version() {
                return "other";
            }

            @Override
            public boolean isConnected() {
                return false;
            }

            @Override
            public void publish(String type, Payload payload) {
            }

            @Override
            public CompletableFuture<Payload> request(String type, Payload payload, long timeoutMs) {
                return new CompletableFuture<Payload>();
            }

            @Override
            public Registration on(String type, InboundHandler handler) {
                return Registration.NONE;
            }
        };
        HeimdallTunnelProvider.install(other);

        TunnelSpiService.uninstall(installed);
        assertSame(other, HeimdallTunnelProvider.get(),
                "an old instance's teardown must not wipe a newer registration");
        HeimdallTunnelProvider.uninstall(other);
    }

    @Test
    @DisplayName("a handler is given the payload and answers on <type>.result")
    void dispatchAndReply(@TempDir Path dataDir) {
        TunnelSpiService spi = install(dataDir);
        final List<Payload> seen = new ArrayList<Payload>();

        spi.on("trace.probe", new HeimdallTunnel.InboundHandler() {
            @Override
            public void handle(Payload payload, HeimdallTunnel.Responder responder) {
                seen.add(payload);
                responder.respond(Payload.builder().put("ok", true).build());
            }
        });

        Envelope request = Envelope.of("req-1", "trace.probe",
                Payload.builder().put("uuid", "abc").build());
        spi.inbound().onMessage(request);

        assertEquals(1, seen.size());
        assertEquals("abc", seen.get(0).string("uuid", ""));
        // There is no bus on an unconfigured server, so the reply is dropped rather than thrown —
        // the assertion that matters is that responding did not blow up the dispatch.
    }

    @Test
    @DisplayName("an unclaimed type is a debug line, not an error")
    void unclaimedTypeIsQuiet(@TempDir Path dataDir) {
        TunnelSpiService spi = install(dataDir);
        spi.inbound().onMessage(Envelope.of("id", "nobody.wants.this", Payload.empty()));
        assertTrue(logger.records().isEmpty() || logger.at(com.heimdall.core.log.LogLevel.SEVERE)
                .isEmpty(), "an unhandled type is not a failure: " + logger.records());
    }

    @Test
    @DisplayName("a handler that throws is contained")
    void throwingHandlerIsContained(@TempDir Path dataDir) {
        TunnelSpiService spi = install(dataDir);
        spi.on("boom", new HeimdallTunnel.InboundHandler() {
            @Override
            public void handle(Payload payload, HeimdallTunnel.Responder responder) {
                throw new IllegalStateException("consumer is broken");
            }
        });
        TunnelMessageHandler inbound = spi.inbound();
        inbound.onMessage(Envelope.of("id", "boom", Payload.empty()));

        assertTrue(logger.logged(com.heimdall.core.log.LogLevel.SEVERE, "boom"),
                "the failure must be attributed to the type: " + logger.records());
        // And the dispatcher still works afterwards.
        inbound.onMessage(Envelope.of("id2", "boom", Payload.empty()));
    }

    @Test
    @DisplayName("closing a registration unsubscribes, but only if it is still ours")
    void unsubscribeIsIdentityChecked(@TempDir Path dataDir) {
        TunnelSpiService spi = install(dataDir);
        final List<String> calls = new ArrayList<String>();

        Registration first = spi.on("shared", new HeimdallTunnel.InboundHandler() {
            @Override
            public void handle(Payload payload, HeimdallTunnel.Responder responder) {
                calls.add("first");
            }
        });
        spi.on("shared", new HeimdallTunnel.InboundHandler() {
            @Override
            public void handle(Payload payload, HeimdallTunnel.Responder responder) {
                calls.add("second");
            }
        });

        // The first plugin unregisters after the second has taken the type over.
        first.close();
        spi.inbound().onMessage(Envelope.of("id", "shared", Payload.empty()));
        assertEquals(1, calls.size());
        assertEquals("second", calls.get(0), "the later subscriber must survive the earlier's close");
    }

    @Test
    @DisplayName("without a tunnel the SPI is inert rather than broken")
    void inertWithoutATunnel(@TempDir Path dataDir) {
        TunnelSpiService spi = install(dataDir);

        assertNotNull(spi.version());
        assertFalse(spi.isConnected());
        spi.publish("anything", null);

        CompletableFuture<Payload> pending = spi.request("anything", null, 100L);
        assertTrue(pending.isCompletedExceptionally(), "a request with no socket must fail fast");
        try {
            pending.get();
        } catch (InterruptedException | ExecutionException expected) {
            assertTrue(expected.getCause() instanceof IllegalStateException
                    || expected instanceof InterruptedException);
        }
    }

    /** The smallest platform that satisfies the runtime: a data directory and nothing else. */
    private static final class StubPlatform implements PlatformFacade {

        private final Path dataDirectory;

        StubPlatform(Path dataDirectory) {
            this.dataDirectory = dataDirectory;
        }

        @Override
        public ServerRole role() {
            return ServerRole.STANDALONE;
        }

        @Override
        public Path dataDirectory() {
            return dataDirectory;
        }

        @Override
        public java.util.concurrent.Executor mainThread() {
            return new java.util.concurrent.Executor() {
                @Override
                public void execute(Runnable command) {
                    command.run();
                }
            };
        }

        @Override
        public com.heimdall.core.platform.PlayerDirectory players() {
            throw new UnsupportedOperationException("not needed by these tests");
        }

        @Override
        public com.heimdall.core.platform.SchedulerBridge scheduler() {
            throw new UnsupportedOperationException("not needed by these tests");
        }

        @Override
        public com.heimdall.core.platform.ConsoleBridge console() {
            throw new UnsupportedOperationException("not needed by these tests");
        }

        @Override
        public com.heimdall.core.command.CommandRegistrar commands() {
            // NONE rather than a throw: the runtime this stub is handed to enables modules, and a
            // module registering a command must not blow up a test about the SPI.
            return com.heimdall.core.command.CommandRegistrar.NONE;
        }

        @Override
        public com.heimdall.core.platform.Integrations integrations() {
            throw new UnsupportedOperationException("not needed by these tests");
        }
    }
}
