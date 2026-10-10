package com.heimdall.platform.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.BuildConstants;
import com.heimdall.core.config.BootstrapStore;
import com.heimdall.core.config.ServerRole;
import com.heimdall.core.json.Envelope;
import com.heimdall.core.json.Payload;
import com.heimdall.core.log.LogLevel;
import com.heimdall.core.log.RecordingLogger;
import com.heimdall.core.platform.PlatformFacade;
import com.heimdall.core.testing.FakeShellContext;
import com.heimdall.core.util.Registration;
import com.heimdall.core.wiring.HeimdallRuntime;
import com.heimdall.shell.contract.TunnelBackend;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The core half of the public SPI: what this generation hands the shell, and what it sends on.
 *
 * <p>The consumer half (subscriptions, replacement, the correlated reply) lives in the shell since
 * departure D87 and is tested there, in {@code ShellTunnelTest}. What is pinned here is the seam:
 * install binds a backend, closing the handle unbinds it, unclaimed frames are offered to the
 * shell and nothing else, and an unconfigured runtime gives a consumer the same answers a reconnect
 * does.
 */
class TunnelSpiServiceTest {

    private final RecordingLogger logger = new RecordingLogger();

    private HeimdallRuntime runtime;
    private Registration installed = Registration.NONE;

    private HeimdallRuntime runtime(Path dataDir) {
        PlatformFacade platform = new StubPlatform(dataDir);
        runtime = HeimdallRuntime.builder(logger, platform)
                .bootstrapStore(new BootstrapStore(logger, dataDir.resolve("bootstrap.yml")))
                .build();
        return runtime;
    }

    @AfterEach
    void tearDown() {
        installed.close();
        if (runtime != null) {
            runtime.close();
        }
    }

    @Test
    @DisplayName("install binds this generation as the shell's tunnel backend, and close unbinds it")
    void installBindsAndCloseUnbinds(@TempDir Path dataDir) {
        FakeShellContext shell = new FakeShellContext("bukkit", dataDir);
        installed = TunnelSpiService.install(logger, runtime(dataDir), shell);

        TunnelBackend backend = shell.backend();
        assertNotNull(backend, "the shell's HeimdallTunnel must have something to forward to");
        assertEquals(BuildConstants.VERSION, backend.version());

        installed.close();
        assertNull(shell.backend(), "a stopped generation must not stay the tunnel's backend");
    }

    @Test
    @DisplayName("an unclaimed frame is offered to the shell, id and type intact")
    void unclaimedFramesGoToTheShell(@TempDir Path dataDir) {
        FakeShellContext shell = new FakeShellContext("bukkit", dataDir).deliverAccepts(true);
        TunnelSpiService service = new TunnelSpiService(logger, runtime(dataDir).tunnel());

        service.inbound(shell).onMessage(Envelope.of("req-1", "trace.probe",
                Payload.builder().put("uuid", "abc").build()));

        assertEquals(1, shell.delivered().size());
        assertEquals("req-1/trace.probe", shell.delivered().get(0));
    }

    @Test
    @DisplayName("a frame nobody subscribed to is a debug line, not an error")
    void unclaimedTypeIsQuiet(@TempDir Path dataDir) {
        FakeShellContext shell = new FakeShellContext("bukkit", dataDir).deliverAccepts(false);
        TunnelSpiService service = new TunnelSpiService(logger, runtime(dataDir).tunnel());

        service.inbound(shell).onMessage(Envelope.of("id", "nobody.wants.this", Payload.empty()));

        assertTrue(logger.at(LogLevel.SEVERE).isEmpty(),
                "an unhandled type is not a failure: " + logger.records());
    }

    @Test
    @DisplayName("without a connected tunnel the backend is inert rather than broken")
    void inertWithoutATunnel(@TempDir Path dataDir) {
        FakeShellContext shell = new FakeShellContext("bukkit", dataDir);
        installed = TunnelSpiService.install(logger, runtime(dataDir), shell);
        TunnelBackend backend = shell.backend();

        assertFalse(backend.isConnected());
        backend.publish("anything", null);
        backend.reply("id", "anything.result", null);

        CompletableFuture<Payload> pending = backend.request("anything", null, 100L);
        assertTrue(pending.isCompletedExceptionally(), "a request with no socket must fail fast");
    }

    @Test
    @DisplayName("closing twice is harmless and leaves another generation's backend alone")
    void closeIsIdempotentAndIdentityChecked(@TempDir Path dataDir) {
        FakeShellContext shell = new FakeShellContext("bukkit", dataDir);
        Registration first = TunnelSpiService.install(logger, runtime(dataDir), shell);
        TunnelBackend firstBackend = shell.backend();
        installed = TunnelSpiService.install(logger, runtime, shell);
        TunnelBackend secondBackend = shell.backend();

        first.close();
        first.close();
        assertSame(secondBackend, shell.backend(),
                "an old generation's teardown must not unbind its successor");
        assertTrue(firstBackend != secondBackend);
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
