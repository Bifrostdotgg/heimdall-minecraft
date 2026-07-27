package com.heimdall.core.platform;

import com.heimdall.core.http.BedrockIdentityProvider;
import com.heimdall.core.json.Payload;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The other plugins Heimdall talks to, behind one seam.
 *
 * <p>All three are optional, all three are reached reflectively by the platform module, and none of
 * them is a compile-time dependency of anything that ships. That is what keeps "core is
 * platform-free" a checkable claim: reflection is invisible to the conformance rules, so it is
 * confined to the platform modules by construction rather than by habit (departure D9).
 *
 * <p>Every accessor is safe to call from any thread, and every one of them answers even when the
 * integration is absent — an {@link Optional#empty()}, a no-op provider, an error payload. A
 * feature that has to branch on "is this plugin installed" before it can ask a question is a
 * feature where somebody eventually forgets to branch.
 */
public interface Integrations {

    /**
     * LuckPerms, if it is installed and has registered its service.
     *
     * <p>Resolved lazily and <strong>retried on every call</strong>. There is no load-order
     * guarantee between plugins, so a bridge resolved once at construction can cache a permanent
     * failure on a server where LuckPerms simply started second — issue #796 / MC-10.
     */
    Optional<LuckPermsBridge> luckPerms();

    /**
     * Floodgate's view of a joining player's Bedrock identity.
     *
     * <p>Never {@code null}: with Floodgate absent this is
     * {@link BedrockIdentityProvider#NONE}, which resolves nothing and lets the bot fall back to
     * inferring Bedrock from the synthetic-UUID shape.
     */
    BedrockIdentityProvider floodgate();

    /**
     * Asks the Trace plugin to probe a player's client.
     *
     * <p>Bukkit-only, because Trace is a server-side mod detector and a proxy has no client
     * connection to inspect. Platforms that cannot answer complete the future with an
     * <strong>error payload</strong> — {@code {"error": "..."}} — rather than failing it or leaving
     * it hanging: the bot has a request outstanding either way, and a reply it can render beats a
     * timeout it cannot explain (issue #797 / MC-12).
     *
     * @return the probe result, or a payload carrying an {@code error} key
     */
    CompletableFuture<Payload> traceProbe(UUID playerUuid);
}
