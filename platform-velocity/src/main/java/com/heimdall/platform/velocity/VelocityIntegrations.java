package com.heimdall.platform.velocity;

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
 * <p>LuckPerms and Floodgate are exactly the Bukkit implementations — {@code net.luckperms:api} is
 * the same artifact on both families and Floodgate is pure reflection, so both live in
 * {@code :platform-common}. v2 wrote LuckPerms twice and the copies drifted; this is what stops that
 * happening again.
 *
 * <p>Trace is the one real difference. It probes a player's <em>client</em> from the server side,
 * and a proxy has no such connection to inspect — so {@link #traceProbe} answers with an error
 * payload rather than failing or hanging. The bot has a request outstanding either way, and a reply
 * it can render beats a timeout it cannot explain (#797 / MC-12). v2 did the same and said so in
 * the same words.
 */
final class VelocityIntegrations implements Integrations {

    private final HeimdallLogger logger;
    private final Executor ioExecutor;
    private final BedrockIdentityProvider floodgate;

    VelocityIntegrations(HeimdallLogger logger, Executor ioExecutor) {
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
        return CompletableFuture.completedFuture(Payload.builder()
                .put("error", "client probing is not available on a proxy")
                .build());
    }
}
