package com.heimdall.platform.bukkit;

import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.platform.ConsoleBridge;
import com.heimdall.core.platform.LogLine;
import com.heimdall.core.platform.UnknownCommandException;
import com.heimdall.core.util.Registration;
import com.heimdall.platform.common.Log4jConsoleTap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import org.bukkit.Bukkit;

/**
 * Running a command as the console, and watching what the console prints.
 *
 * <p>The watching half is {@link Log4jConsoleTap}, which is shared with Velocity because both
 * platforms run log4j2. Only the dispatch half is Bukkit-specific, and it is Bukkit-specific for one
 * reason: {@code Bukkit.dispatchCommand} must run on the main thread, and the request arrives on
 * {@code heimdall-io}.
 *
 * <h2>The acknowledgement is not the output</h2>
 *
 * <p>The future completes with "dispatched: …", never with what the command printed. There is no
 * way to attribute console output back to the command that caused it — the server writes to one
 * log, asynchronously, interleaved with everything else — and v2 returned exactly the same
 * acknowledgement while implying otherwise. The dashboard's console view gets the real output from
 * the tap, which is the honest shape of the feature: run it, then watch.
 *
 * <p>A command that does not exist fails the future with {@link UnknownCommandException}. That is a
 * deliberate change from this class's first version, which treated it as a successful dispatch: the
 * caller that matters is {@code /offend} handing the server a punishment the bot chose, and if the
 * punishment plugin is absent then the infraction is recorded and nothing is applied. Somebody has
 * to be told, and only the moderator standing there can act on it.
 */
final class BukkitConsoleBridge implements ConsoleBridge {

    /**
     * How a command line actually reaches the server.
     *
     * <p>A seam of exactly one method, and it exists for one reason: {@code Bukkit.dispatchCommand}
     * is static, so the branch on its return value — the whole of B2, and the difference between a
     * moderator being told their punishment did not land and being told nothing — is otherwise
     * reachable only through a running server or a static-mocking library. Adding a test dependency
     * to reach two lines is a worse trade than naming the call.
     */
    interface CommandSink {

        /** @return whether the server had a command by that name */
        boolean dispatch(String commandLine);
    }

    private final HeimdallLogger logger;
    private final Executor mainThread;
    private final Log4jConsoleTap tap;
    private final CommandSink sink;

    BukkitConsoleBridge(HeimdallLogger logger, Executor mainThread, Log4jConsoleTap tap) {
        this.logger = logger;
        this.mainThread = mainThread;
        this.tap = tap;
        this.sink = new CommandSink() {
            @Override
            public boolean dispatch(String commandLine) {
                return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), commandLine);
            }
        };
    }

    /** For the tests that need to drive the return value without a server. */
    BukkitConsoleBridge(
            HeimdallLogger logger, Executor mainThread, Log4jConsoleTap tap, CommandSink sink) {
        this.logger = logger;
        this.mainThread = mainThread;
        this.tap = tap;
        this.sink = sink;
    }

    @Override
    public CompletableFuture<String> dispatchCommand(final String command) {
        final CompletableFuture<String> result = new CompletableFuture<String>();
        if (command == null || command.trim().isEmpty()) {
            result.completeExceptionally(new IllegalArgumentException("no command given"));
            return result;
        }
        final String trimmed = command.trim();
        mainThread.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    // The boolean is the whole point of this line and was previously discarded.
                    // Bukkit returns false when no command by that name exists, which for the
                    // caller that matters — /offend dispatching a punishment the bot chose — means
                    // the infraction was recorded and nothing was applied.
                    if (sink.dispatch(trimmed)) {
                        result.complete("dispatched: " + trimmed);
                    } else {
                        result.completeExceptionally(new UnknownCommandException(trimmed));
                    }
                } catch (Throwable refused) {
                    // The command itself throwing is the command's problem and the server has
                    // already logged it — but the dashboard is waiting on this future, so it is
                    // told rather than left to time out.
                    logger.warn("console command '" + trimmed + "' failed: " + refused);
                    result.completeExceptionally(refused);
                }
            }
        });
        return result;
    }

    @Override
    public Registration attachLogTap(Consumer<LogLine> consumer) {
        return tap.addTap(consumer);
    }

    /**
     * How many tap consumers the appender has dropped for throwing.
     *
     * <p>Reported by {@code /hd status} and nowhere else, because nowhere else is outside the
     * capture path: the drop itself cannot log, since a line written from inside the appender would
     * be captured and fed back into the loop the re-entrancy guard exists to break. So the tap
     * counts silently and something out of band asks.
     */
    @Override
    public int droppedTapConsumers() {
        return tap.droppedConsumers();
    }
}
