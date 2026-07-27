package com.heimdall.platform.velocity;

import com.heimdall.core.platform.ConsoleBridge;
import com.heimdall.core.platform.LogLine;
import com.heimdall.core.util.Registration;
import com.heimdall.platform.common.Log4jConsoleTap;
import com.velocitypowered.api.proxy.ProxyServer;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Running a command as the proxy console, and watching what it prints.
 *
 * <p>The watching half is the same {@link Log4jConsoleTap} the Bukkit side uses — Velocity runs
 * log4j2 too, so there is one implementation rather than two.
 *
 * <p>Dispatch is genuinely simpler here than on Bukkit: Velocity's command manager is asynchronous
 * by design and hands back a future, so there is no thread to hop to and nothing to schedule. The
 * acknowledgement is still an acknowledgement, not the command's output — see {@link ConsoleBridge}
 * for why no platform can offer the latter.
 */
final class VelocityConsoleBridge implements ConsoleBridge {

    private final ProxyServer proxy;
    private final Log4jConsoleTap tap;

    VelocityConsoleBridge(ProxyServer proxy, Log4jConsoleTap tap) {
        this.proxy = proxy;
        this.tap = tap;
    }

    @Override
    public CompletableFuture<String> dispatchCommand(String command) {
        if (command == null || command.trim().isEmpty()) {
            CompletableFuture<String> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IllegalArgumentException("no command given"));
            return failed;
        }
        String trimmed = command.trim();
        return proxy.getCommandManager()
                .executeAsync(proxy.getConsoleCommandSource(), trimmed)
                // thenApply rather than thenApplyAsync: no executor to name, and the mapping is a
                // string concatenation that does not deserve a thread hop. The conformance rule
                // bans the executor-less *Async overloads, not the synchronous stages.
                .thenApply(executed -> Boolean.TRUE.equals(executed)
                        ? "dispatched: " + trimmed
                        : "no such command: " + trimmed);
    }

    @Override
    public Registration attachLogTap(Consumer<LogLine> consumer) {
        return tap.addTap(consumer);
    }
}
