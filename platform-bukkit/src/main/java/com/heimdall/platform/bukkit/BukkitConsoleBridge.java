package com.heimdall.platform.bukkit;

import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.platform.ConsoleBridge;
import com.heimdall.core.platform.LogLine;
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
 * <p>A command that does not exist is a <em>successful</em> dispatch that printed an error. The
 * future only fails if the server refused to accept the task at all.
 */
final class BukkitConsoleBridge implements ConsoleBridge {

    private final HeimdallLogger logger;
    private final Executor mainThread;
    private final Log4jConsoleTap tap;

    BukkitConsoleBridge(HeimdallLogger logger, Executor mainThread, Log4jConsoleTap tap) {
        this.logger = logger;
        this.mainThread = mainThread;
        this.tap = tap;
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
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), trimmed);
                    result.complete("dispatched: " + trimmed);
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
}
