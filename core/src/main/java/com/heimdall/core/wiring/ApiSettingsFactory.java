package com.heimdall.core.wiring;

import com.heimdall.core.config.BootstrapConfig;
import com.heimdall.core.http.ApiSettings;

/**
 * Turns what is on disk into what the HTTP client needs.
 *
 * <p>Deliberately not a method on {@link ApiSettings}: {@code http} has no business knowing the
 * bootstrap file format, and phase 1b introduces a second source for the same settings — remote
 * config pushed down the tunnel — which will get its own factory here rather than a second static
 * method on the settings type.
 *
 * <p>The timing knobs <em>are</em> read from the bootstrap now, and that is a correction to this
 * class's original claim that "the dashboard owns them". It cannot: the login timeout and retry
 * count shape the very request that would fetch the dashboard's configuration, so a server that got
 * them wrong could never load the settings that would fix them, and there is no {@code http}
 * capability for the bot to narrow a push to anyway. They live in {@code bootstrap.yml} (departures
 * D17's exceptions list, D62), default to v3's values for a claimed server, and carry a migrated v2
 * server's own tuning so its login budget does not balloon from ~1.5s to ~18s on upgrade.
 */
public final class ApiSettingsFactory {

    private ApiSettingsFactory() {
    }

    /**
     * Settings derived from {@code bootstrap.yml}, with the timing defaults.
     *
     * <p>The guild id is a separate argument because the plugin can be configured with a token
     * alone and resolve its guild from the bot; only once that has happened is there one to supply.
     *
     * @return a builder, so a caller can still override the timing before building
     */
    public static ApiSettings.Builder fromBootstrap(BootstrapConfig bootstrap, String guildId) {
        if (bootstrap == null) {
            throw new IllegalArgumentException("bootstrap config is required");
        }
        return ApiSettings.builder()
                .baseUrl(bootstrap.endpoint())
                .apiKey(bootstrap.token())
                // Carried through only so `identify` can send X-Token-Id. Every other endpoint is
                // guild-scoped and already names the guild in its path.
                .tokenId(bootstrap.tokenId())
                .serverId(bootstrap.serverId())
                .guildId(guildId)
                // ApiSettings clamps each of these on build (a min timeout, a floor of 1 retry), so
                // a nonsense hand-edited value cannot make the client unusable — it becomes the
                // clamped value, not an exception.
                .timeoutMs(bootstrap.timeoutMs())
                .retries(bootstrap.retries())
                .retryDelayMs(bootstrap.retryDelayMs());
    }
}
