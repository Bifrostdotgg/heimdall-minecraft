package com.heimdall.platform.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.heimdall.core.log.RecordingLogger;
import com.heimdall.core.platform.UnknownCommandException;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.ProxyServer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What {@code CommandManager.executeAsync}'s boolean means to a caller.
 *
 * <p>The proxy's half of the same correction the Bukkit bridge gets. Velocity was arguably worse: it
 * already computed the right words — {@code "no such command: …"} — and then returned them as a
 * <em>successful</em> acknowledgement, so the only way for a caller to notice was to match on
 * message text, and {@code /offend} did not.
 */
class VelocityConsoleBridgeTest {

    private final RecordingLogger logger = new RecordingLogger(true);

    private static final Executor INLINE = new Executor() {
        @Override
        public void execute(Runnable command) {
            command.run();
        }
    };

    private VelocityConsoleBridge bridge(boolean executed) {
        ProxyServer proxy = mock(ProxyServer.class);
        CommandManager commands = mock(CommandManager.class);
        when(proxy.getCommandManager()).thenReturn(commands);
        when(proxy.getConsoleCommandSource()).thenReturn(mock(ConsoleCommandSource.class));
        when(commands.executeAsync(any(CommandSource.class), anyString()))
                .thenReturn(CompletableFuture.completedFuture(Boolean.valueOf(executed)));
        // Null tap: see the Bukkit bridge's test. Nothing here attaches one, and a real tap
        // would drag log4j-core onto this module's test classpath for a field never read.
        return new VelocityConsoleBridge(proxy, null);
    }

    @Test
    @DisplayName("a command the proxy has completes with an acknowledgement")
    void aKnownCommandSucceeds() throws Exception {
        String acknowledgement = bridge(true).dispatchCommand("glist").get(5, TimeUnit.SECONDS);

        assertTrue(acknowledgement.contains("glist"), acknowledgement);
    }

    @Test
    @DisplayName("a command the proxy does NOT have fails, and names itself")
    void anUnknownCommandFails() {
        ExecutionException thrown = assertThrows(ExecutionException.class,
                () -> bridge(false).dispatchCommand("warn Steve").get(5, TimeUnit.SECONDS));

        assertTrue(thrown.getCause() instanceof UnknownCommandException,
                "returning this as a cheerful string meant /offend told a moderator the punishment "
                        + "had been dispatched when the proxy had no such command: "
                        + thrown.getCause());
        assertEquals("warn Steve", ((UnknownCommandException) thrown.getCause()).command());
    }

    @Test
    @DisplayName("an empty command is refused without reaching the proxy")
    void anEmptyCommandIsRefused() {
        assertThrows(ExecutionException.class,
                () -> bridge(true).dispatchCommand("  ").get(5, TimeUnit.SECONDS));
    }
}
