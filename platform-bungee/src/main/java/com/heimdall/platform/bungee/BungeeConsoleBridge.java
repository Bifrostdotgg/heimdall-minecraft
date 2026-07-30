package com.heimdall.platform.bungee;

import com.heimdall.core.platform.ConsoleBridge;
import com.heimdall.core.platform.LogLine;
import com.heimdall.core.platform.UnknownCommandException;
import com.heimdall.core.util.Registration;
import com.heimdall.platform.common.JulConsoleTap;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.md_5.bungee.api.ProxyServer;

/**
 * Running a command as the proxy console, and watching what it prints.
 *
 * <p>The watching half is {@link JulConsoleTap}: BungeeCord runs {@code java.util.logging} and no
 * log4j at any version, so the appender the other two platforms share attaches to nothing here.
 *
 * <p>Dispatch is synchronous and returns a boolean. {@code PluginManager.dispatchCommand} runs the
 * command on the calling thread and answers whether the proxy had one by that name — so unlike
 * Bukkit there is no thread to hop to, and unlike Velocity there is no future to compose. The
 * acknowledgement is still an acknowledgement, not the command's output; see {@link ConsoleBridge}
 * for why no platform can offer the latter.
 *
 * <p>The call is made on whatever thread asked, which is {@code heimdall-io} — every caller comes
 * through {@code RemoteRequestWiring}, which subscribes there. That is deliberate on both ends: a
 * console command on a proxy is a plugin's command handler, and running one on the netty event loop
 * is how a proxy stops forwarding packets.
 */
final class BungeeConsoleBridge implements ConsoleBridge {

    private final ProxyServer proxy;
    private final JulConsoleTap tap;

    BungeeConsoleBridge(ProxyServer proxy, JulConsoleTap tap) {
        this.proxy = proxy;
        this.tap = tap;
    }

    @Override
    public CompletableFuture<String> dispatchCommand(String command) {
        CompletableFuture<String> result = new CompletableFuture<String>();
        if (command == null || command.trim().isEmpty()) {
            result.completeExceptionally(new IllegalArgumentException("no command given"));
            return result;
        }
        String trimmed = command.trim();
        try {
            // The boolean is the whole point of reading it. BungeeCord returns false when no command
            // by that name exists, which for the caller that matters — /offend dispatching a
            // punishment the bot has already recorded an infraction for — means the record exists and
            // nothing happened. Departure D72.
            if (proxy.getPluginManager().dispatchCommand(proxy.getConsole(), trimmed)) {
                result.complete("dispatched: " + trimmed);
            } else {
                result.completeExceptionally(new UnknownCommandException(trimmed));
            }
        } catch (Throwable refused) {
            // The command itself throwing is the command's problem and the proxy has already logged
            // it — but the dashboard is waiting on this future, so it is told rather than left to
            // time out.
            result.completeExceptionally(refused);
        }
        return result;
    }

    @Override
    public Registration attachLogTap(Consumer<LogLine> consumer) {
        return tap.addTap(consumer);
    }

    /**
     * How many tap consumers the handler has dropped for throwing.
     *
     * <p>Reported by {@code /hdp status} and nowhere else, because nowhere else is outside the
     * capture path: the drop itself cannot log, since a line written from inside the tap would be
     * captured and fed back into the loop the re-entrancy guard exists to break. So the tap counts
     * silently and something out of band asks.
     */
    @Override
    public int droppedTapConsumers() {
        return tap.droppedConsumers();
    }
}
