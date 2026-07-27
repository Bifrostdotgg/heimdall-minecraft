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
 * <p>The timing knobs are left at their defaults. They are not bootstrap concerns; the dashboard
 * owns them.
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
                .serverId(bootstrap.serverId())
                .guildId(guildId);
    }
}
