package com.heimdall.platform.bukkit;

import com.heimdall.core.http.BedrockIdentityProvider;
import com.heimdall.core.json.Payload;
import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.platform.Integrations;
import com.heimdall.core.platform.LuckPermsBridge;
import com.heimdall.platform.common.FloodgateIdentityProvider;
import com.heimdall.platform.common.LuckPermsSupport;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * The three optional plugins, on the Bukkit side.
 *
 * <p>LuckPerms and Floodgate are handled by {@code :platform-common} — neither needs a Bukkit type
 * and writing them twice is what let v2's two copies drift. Trace is genuinely Bukkit-only: it
 * probes a player's <em>client</em>, and a proxy has no client connection to inspect.
 */
final class BukkitIntegrations implements Integrations {

    /** The plugin name Trace registers under, and the method it exposes for remote probing. */
    private static final String TRACE_PLUGIN = "Trace";
    private static final String TRACE_METHOD = "forceProbeForWs";

    private final HeimdallLogger logger;
    private final Executor ioExecutor;
    private final BedrockIdentityProvider floodgate;

    BukkitIntegrations(HeimdallLogger logger, Executor ioExecutor) {
        this.logger = logger;
        this.ioExecutor = ioExecutor;
        // Fixed at construction: this answers a classpath question, which cannot change while the
        // JVM is running. LuckPerms is the opposite case — a plugin that registers a service on its
        // own schedule — so it is resolved lazily and retried until it appears.
        this.floodgate = FloodgateIdentityProvider.create();
    }

    /**
     * Cached once resolved, re-resolved while {@code null}.
     *
     * <p>Both halves matter and they pull in opposite directions. Never caching a <em>negative</em>
     * is the fix for #796 / MC-10: there is no load-order guarantee between plugins, and v2's Bukkit
     * implementation resolved once at construction, so a server where LuckPerms started second had
     * role sync dead for the whole session.
     *
     * <p>Caching the <em>positive</em> is what stops a fresh bridge being built per lookup. Each new
     * one announces "LuckPerms integration enabled" the first time it resolves, and "first time" is
     * per instance — so a throwaway per call turns a one-off boot line into one INFO line per role
     * sync, which is a log nobody can read.
     */
    private volatile LuckPermsBridge luckPerms;

    @Override
    public Optional<LuckPermsBridge> luckPerms() {
        LuckPermsBridge resolved = luckPerms;
        if (resolved != null) {
            return Optional.of(resolved);
        }
        Optional<LuckPermsBridge> fresh = LuckPermsSupport.resolve(logger, ioExecutor);
        if (fresh.isPresent()) {
            // A benign race: two callers can both build one and one wins. The loser is discarded and
            // holds nothing — a bridge is a stateless view onto LuckPerms' own singleton.
            luckPerms = fresh.get();
        }
        return fresh;
    }

    @Override
    public BedrockIdentityProvider floodgate() {
        return floodgate;
    }

    @Override
    public CompletableFuture<Payload> traceProbe(UUID playerUuid) {
        final CompletableFuture<Payload> result = new CompletableFuture<Payload>();
        if (playerUuid == null) {
            return completeWithError(result, "no player uuid given");
        }
        Player target = Bukkit.getPlayer(playerUuid);
        if (target == null || !target.isOnline()) {
            // Always answered, never left hanging: the bot has a request outstanding either way,
            // and a reply it can render beats a timeout it cannot explain (#797 / MC-12).
            return completeWithError(result, "player is not online");
        }
        Plugin trace = Bukkit.getPluginManager().getPlugin(TRACE_PLUGIN);
        if (trace == null || !trace.isEnabled()) {
            return completeWithError(result, "Trace is not installed on this server");
        }
        try {
            Method probe = trace.getClass().getMethod(TRACE_METHOD, Player.class);
            Object future = probe.invoke(trace, target);
            if (!(future instanceof CompletableFuture)) {
                return completeWithError(result, "Trace does not support remote probing");
            }
            attach((CompletableFuture<?>) future, result);
            return result;
        } catch (NoSuchMethodException tooOld) {
            return completeWithError(result, "Trace does not support remote probing");
        } catch (Throwable failed) {
            logger.warn("could not invoke the Trace probe: " + failed);
            return completeWithError(result, "probe invocation failed");
        }
    }

    /**
     * Bridges Trace's future onto ours, without ever naming its payload type.
     *
     * <p>Trace returns a {@code CompletableFuture<JsonObject>} using its own, <strong>unrelocated
     * Gson</strong>. Heimdall's Gson is relocated into {@code com.heimdall.libs.gson}, so the two
     * {@code JsonObject} classes are unrelated types and a cast would fail at runtime — which is
     * exactly the sort of failure that only shows up on a server that has both plugins installed.
     * Going via {@code toString()} and re-parsing costs one serialisation round trip on a
     * rarely-used diagnostic path and cannot be broken by either side's shading.
     */
    private void attach(CompletableFuture<?> probe, final CompletableFuture<Payload> result) {
        probe.whenCompleteAsync(new java.util.function.BiConsumer<Object, Throwable>() {
            @Override
            public void accept(Object value, Throwable error) {
                if (error != null) {
                    completeWithError(result, "probe failed: " + error.getMessage());
                    return;
                }
                if (value == null) {
                    result.complete(Payload.empty());
                    return;
                }
                try {
                    result.complete(Payload.parse(value.toString()));
                } catch (RuntimeException unparseable) {
                    completeWithError(result, "probe returned something that is not JSON");
                }
            }
        }, ioExecutor);
    }

    private CompletableFuture<Payload> completeWithError(
            CompletableFuture<Payload> result, String message) {
        result.complete(Payload.builder().put("error", message).build());
        return result;
    }
}
