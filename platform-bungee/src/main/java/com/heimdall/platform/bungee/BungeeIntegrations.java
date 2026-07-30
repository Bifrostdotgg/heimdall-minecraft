package com.heimdall.platform.bungee;

import com.heimdall.core.http.BedrockIdentityProvider;
import com.heimdall.core.json.Payload;
import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.platform.Integrations;
import com.heimdall.core.platform.LuckPermsBridge;
import com.heimdall.platform.common.FloodgateIdentityProvider;
import com.heimdall.platform.common.LuckPermsSupport;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * The optional plugins, on the proxy.
 *
 * <p>Byte-for-byte the Velocity implementation's logic, and deliberately so: {@code net.luckperms:api}
 * is the same artifact on all three platforms — LuckPerms ships a BungeeCord build against it — and
 * Floodgate is reached purely reflectively, so both live in {@code :platform-common}. v2 wrote
 * LuckPerms twice and the copies drifted (departure D46); writing it a third time would be the same
 * mistake with more of it.
 *
 * <p>Trace is the one real difference from a backend server, and it is the same difference Velocity
 * has. It probes a player's <em>client</em> from the server side, and a proxy has no such connection
 * to inspect — so {@link #traceProbe} answers with an error payload rather than failing or hanging.
 * The bot has a request outstanding either way, and a reply it can render beats a timeout it cannot
 * explain (#797 / MC-12).
 *
 * <p>That this facade answers at all is what makes {@code RemoteRequestWiring}'s {@code probe_player}
 * handler correct on a proxy without knowing it is on one: the handler's own job is a malformed uuid,
 * a failed future and a future that never completes, and "there is no client here" is this layer's.
 */
final class BungeeIntegrations implements Integrations {

    private final HeimdallLogger logger;
    private final Executor ioExecutor;
    private final BedrockIdentityProvider floodgate;

    BungeeIntegrations(HeimdallLogger logger, Executor ioExecutor) {
        this.logger = logger;
        this.ioExecutor = ioExecutor;
        this.floodgate = FloodgateIdentityProvider.create();
    }

    /**
     * Cached once resolved, re-resolved while {@code null}.
     *
     * <p>Both halves matter and they pull in opposite directions. Never caching a <em>negative</em>
     * is the fix for #796 / MC-10: there is no load-order guarantee between plugins, and v2's Bukkit
     * implementation resolved once at construction, so a server where LuckPerms started second had
     * role sync dead for the whole session. BungeeCord makes that likelier rather than less: it
     * enables plugins in a dependency order derived from {@code depends}/{@code softDepends}, and
     * Heimdall declares neither.
     *
     * <p>Caching the <em>positive</em> is what stops a fresh bridge being built per lookup. Each new
     * one announces "LuckPerms integration enabled" the first time it resolves, so a throwaway per
     * call turns a one-off boot line into one INFO line per role sync.
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
        return CompletableFuture.completedFuture(Payload.builder()
                .put("error", "client probing is not available on a proxy")
                .build());
    }
}
