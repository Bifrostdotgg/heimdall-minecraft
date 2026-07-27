package com.heimdall.module.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.concurrent.HeimdallExecutors;
import com.heimdall.core.config.ServerRole;
import com.heimdall.core.json.Payload;
import com.heimdall.core.log.RecordingLogger;
import com.heimdall.core.module.ModuleEnvironment;
import com.heimdall.core.module.ModuleManager;
import com.heimdall.core.pipeline.ChatPipeline;
import com.heimdall.core.pipeline.LoginPipeline;
import com.heimdall.core.platform.LogLine;
import com.heimdall.core.remoteconfig.ConfigDocument;
import com.heimdall.core.remoteconfig.RemoteConfig;
import com.heimdall.core.testing.FakePlatform;
import com.heimdall.core.testing.RecordingTunnelBus;
import com.heimdall.core.tunnel.Capabilities;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Driven through the real {@link ModuleManager} rather than by calling {@link HeimdallConsoleModule}
 * directly, for the same reason {@code ModuleManagerTest} does: the behaviours that matter most here
 * — one tap on enable, none after disable, no doubling on re-enable — are as much about how the
 * manager wires this module up as about the module's own code, and a hand-built {@code ModuleContext}
 * would not exercise that wiring.
 *
 * <p>{@link HeimdallConsoleModule#flush} is called directly rather than waiting on the real
 * one-second scheduler tick — it is package-private for exactly this reason (see its javadoc). That
 * keeps every test here deterministic and fast instead of paced by wall-clock time.
 */
class HeimdallConsoleModuleTest {

    private final RecordingLogger logger = new RecordingLogger(true);

    @TempDir
    Path dataDir;

    private HeimdallExecutors executors;
    private FakePlatform platform;
    private RecordingTunnelBus tunnel;
    private ModuleManager manager;
    private HeimdallConsoleModule module;

    @BeforeEach
    void setUp() {
        executors = new HeimdallExecutors(logger, 1);
        platform = new FakePlatform(ServerRole.STANDALONE, dataDir);
        tunnel = new RecordingTunnelBus();
        RemoteConfig remoteConfig = new RemoteConfig(
                logger, dataDir.resolve("remote-config.json"), ConfigDocument.empty());
        manager = new ModuleManager(ModuleEnvironment.builder()
                .logger(logger)
                .executors(executors)
                .tunnel(tunnel)
                .remoteConfig(remoteConfig)
                .loginPipeline(new LoginPipeline(logger))
                .chatPipeline(new ChatPipeline(logger))
                .platform(platform)
                .build());
        module = new HeimdallConsoleModule();
        manager.register(module);
    }

    @AfterEach
    void tearDown() {
        executors.shutdown(1000);
    }

    private void enable() {
        manager.reconcile(Collections.singleton(HeimdallConsoleModule.ID));
    }

    private void disable() {
        manager.reconcile(Collections.<String>emptySet());
    }

    private static LogLine line(long ts, String level, String msg) {
        return new LogLine(ts, level, msg);
    }

    // ── Identity ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("id, capabilities and roles")
    void identity() {
        assertEquals("console", module.id());
        assertEquals(Collections.singleton(Capabilities.CONSOLE), module.capabilities());
        assertEquals(Collections.<ServerRole>emptySet(), module.roles(),
                "a proxy console is as worth streaming as a backend's");
    }

    // ── Batching ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("lines are batched into one console_line frame with ts/level/msg intact")
    void batchesIntoOneFrame() {
        tunnel.connected(true);
        enable();

        platform.emitConsoleLine(line(1000L, "INFO", "hello"));
        platform.emitConsoleLine(line(1001L, "WARN", "careful"));
        module.flush();

        List<RecordingTunnelBus.Sent> sent = tunnel.sent();
        assertEquals(1, sent.size());
        assertEquals("console_line", sent.get(0).type());

        List<Payload> lines = sent.get(0).payload().children("lines");
        assertEquals(2, lines.size());
        assertEquals(1000L, lines.get(0).longValue("ts", -1));
        assertEquals("INFO", lines.get(0).string("level", ""));
        assertEquals("hello", lines.get(0).string("msg", ""));
        assertEquals(1001L, lines.get(1).longValue("ts", -1));
        assertEquals("WARN", lines.get(1).string("level", ""));
        assertEquals("careful", lines.get(1).string("msg", ""));
    }

    @Test
    @DisplayName("a batch over 200 is capped per flush, and the remainder waits for the next one")
    void capsAt200PerFlush() {
        tunnel.connected(true);
        enable();

        for (int i = 0; i < 250; i++) {
            platform.emitConsoleLine(line(i, "INFO", "line " + i));
        }

        module.flush();
        List<RecordingTunnelBus.Sent> afterFirst = tunnel.sent();
        assertEquals(1, afterFirst.size());
        assertEquals(200, afterFirst.get(0).payload().children("lines").size(),
                "v2 parity: MAX_BATCH_SIZE is 200");

        module.flush();
        List<RecordingTunnelBus.Sent> afterSecond = tunnel.sent();
        assertEquals(2, afterSecond.size());
        assertEquals(50, afterSecond.get(1).payload().children("lines").size(),
                "the 50 lines left over from the first flush must not be dropped");
    }

    @Test
    @DisplayName("flush with an empty queue sends nothing")
    void emptyQueueSendsNothing() {
        tunnel.connected(true);
        enable();

        module.flush();

        assertTrue(tunnel.sent().isEmpty());
    }

    // ── Drain-and-discard ────────────────────────────────────────────────────

    @Test
    @DisplayName("while disconnected the queue is drained and discarded, never grown")
    void drainsAndDiscardsWhileDisconnected() {
        tunnel.connected(false);
        enable();

        for (int round = 0; round < 10; round++) {
            for (int i = 0; i < 300; i++) {
                platform.emitConsoleLine(line(i, "INFO", "line " + i));
            }
            module.flush();
        }

        assertTrue(tunnel.sent().isEmpty(), "nothing may be sent while disconnected");
        assertTrue(module.queuedCount() <= HeimdallConsoleModule.MAX_QUEUE_SIZE,
                "the module's own queue must stay bounded even across many disconnected flushes, "
                        + "which only holds if each flush actually drained rather than left the "
                        + "batch queued for a bot that is not there");
    }

    @Test
    @DisplayName("reconnecting sends only lines produced after the reconnect")
    void reconnectSendsOnlyNewLines() {
        tunnel.connected(false);
        enable();

        platform.emitConsoleLine(line(1L, "INFO", "lost forever"));
        module.flush();
        assertTrue(tunnel.sent().isEmpty());

        tunnel.connected(true);
        platform.emitConsoleLine(line(2L, "INFO", "survives"));
        module.flush();

        List<RecordingTunnelBus.Sent> sent = tunnel.sent();
        assertEquals(1, sent.size());
        List<Payload> lines = sent.get(0).payload().children("lines");
        assertEquals(1, lines.size(), "the disconnected line must not be replayed alongside this one");
        assertEquals("survives", lines.get(0).string("msg", ""));
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("disable detaches the tap")
    void disableDetachesTap() {
        enable();
        assertEquals(1, platform.consoleTapCount());

        disable();
        assertEquals(0, platform.consoleTapCount(),
                "a detached-but-still-attached appender keeps calling a consumer for a module the "
                        + "dashboard says is off");
    }

    @Test
    @DisplayName("enable, disable, enable leaves exactly one tap and no doubled frames")
    void reEnablingDoesNotDouble() {
        enable();
        disable();
        enable();

        assertEquals(1, platform.consoleTapCount(),
                "a second enable that forgot to clean up the first would leave two taps attached");

        tunnel.connected(true);
        platform.emitConsoleLine(line(1L, "INFO", "once"));
        module.flush();

        List<RecordingTunnelBus.Sent> sent = tunnel.sent();
        assertEquals(1, sent.size());
        assertEquals(1, sent.get(0).payload().children("lines").size(),
                "a doubled subscription would have delivered this one line to two live consumers");
    }

    @Test
    @DisplayName("after disable, a stray flush call sends nothing and the buffer is empty")
    void disabledModuleHasNothingLeftToFlush() {
        tunnel.connected(true);
        enable();
        disable();

        module.flush();

        assertTrue(tunnel.sent().isEmpty());
        assertEquals(0, module.queuedCount());
    }
}
