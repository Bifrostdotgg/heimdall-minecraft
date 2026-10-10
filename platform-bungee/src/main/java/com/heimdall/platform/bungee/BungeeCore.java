package com.heimdall.platform.bungee;

import com.heimdall.core.log.JulLogger;
import com.heimdall.shell.contract.HeimdallCore;
import com.heimdall.shell.contract.ShellContext;
import com.heimdall.shell.contract.ShellContract;
import net.md_5.bungee.api.plugin.Plugin;

/**
 * BungeeCord's core entry point, reached through {@code com.heimdall.platform.common.CoreEntry}
 * (departure D87).
 *
 * <p>The logger is {@link JulLogger} over the shell plugin's own logger, which BungeeCord already
 * prefixes with {@code [Heimdall]}, exactly as before the split.
 */
public final class BungeeCore implements HeimdallCore {

    private BungeeBootstrap bootstrap;

    public BungeeCore() {
    }

    @Override
    public int contractVersion() {
        return ShellContract.VERSION;
    }

    @Override
    public void start(ShellContext context) throws Exception {
        Plugin plugin = (Plugin) context.platformPlugin();
        bootstrap = new BungeeBootstrap(
                plugin, plugin.getProxy(), new JulLogger(plugin.getLogger()),
                context.dataDirectory());
        bootstrap.enable();
    }

    @Override
    public void stop() {
        BungeeBootstrap running = bootstrap;
        bootstrap = null;
        if (running != null) {
            running.disable();
        }
    }
}
