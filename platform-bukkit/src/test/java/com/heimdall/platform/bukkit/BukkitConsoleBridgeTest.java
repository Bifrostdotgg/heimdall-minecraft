package com.heimdall.platform.bukkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.log.RecordingLogger;
import com.heimdall.core.platform.UnknownCommandException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What {@code Bukkit.dispatchCommand}'s boolean means to a caller.
 *
 * <p>Two lines of branch, and the reason they are worth a test file: the caller is {@code /offend}
 * handing the server a punishment the bot has <em>already recorded an infraction for</em>. Getting
 * this wrong does not lose a command — it tells a moderator they banned somebody when nothing
 * happened, and the record exists to disagree with them later.
 */
class BukkitConsoleBridgeTest {

    private final RecordingLogger logger = new RecordingLogger(true);

    private static final Executor INLINE = new Executor() {
        @Override
        public void execute(Runnable command) {
            command.run();
        }
    };

    private BukkitConsoleBridge bridge(BukkitConsoleBridge.CommandSink sink) {
        // The tap is null on purpose: nothing here attaches one, and constructing a real
        // Log4jConsoleTap would put log4j-core on this module's test classpath for the sake of a
        // field these tests never read. Its own behaviour is covered in :platform-common.
        return new BukkitConsoleBridge(logger, INLINE, null, sink);
    }

    @Test
    @DisplayName("a command the server has completes with an acknowledgement")
    void aKnownCommandSucceeds() throws Exception {
        BukkitConsoleBridge bridge = bridge(commandLine -> true);

        String acknowledgement = bridge.dispatchCommand("tempban Steve 1d cheating")
                .get(5, TimeUnit.SECONDS);

        assertTrue(acknowledgement.contains("tempban Steve 1d cheating"), acknowledgement);
    }

    @Test
    @DisplayName("a command the server does NOT have fails, and names itself")
    void anUnknownCommandFails() {
        // false is what Bukkit returns for a command that does not exist. It was previously
        // discarded and the future completed "dispatched: …" regardless.
        BukkitConsoleBridge bridge = bridge(commandLine -> false);

        ExecutionException thrown = assertThrows(ExecutionException.class,
                () -> bridge.dispatchCommand("warn Steve").get(5, TimeUnit.SECONDS));

        assertTrue(thrown.getCause() instanceof UnknownCommandException,
                "a typed failure, so a caller does not have to match on message text to tell this "
                        + "from a platform refusing outright: " + thrown.getCause());
        assertEquals("warn Steve", ((UnknownCommandException) thrown.getCause()).command());
    }

    @Test
    @DisplayName("the command line is trimmed before it is dispatched")
    void theLineIsTrimmed() throws Exception {
        final String[] seen = new String[1];
        BukkitConsoleBridge bridge = bridge(commandLine -> {
            seen[0] = commandLine;
            return true;
        });

        bridge.dispatchCommand("  say hi  ").get(5, TimeUnit.SECONDS);

        assertEquals("say hi", seen[0]);
    }

    @Test
    @DisplayName("an empty command is refused without reaching the server")
    void anEmptyCommandIsRefused() {
        BukkitConsoleBridge bridge = bridge(commandLine -> {
            throw new AssertionError("nothing should be dispatched");
        });

        assertThrows(ExecutionException.class,
                () -> bridge.dispatchCommand("   ").get(5, TimeUnit.SECONDS));
    }
}
