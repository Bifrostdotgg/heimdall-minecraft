package com.heimdall.platform.velocity;

import com.heimdall.shell.contract.HeimdallCore;
import com.heimdall.shell.contract.ShellContext;
import com.heimdall.shell.contract.ShellContract;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

/**
 * Velocity's core entry point, reached through {@code com.heimdall.platform.common.CoreEntry}
 * (departure D87).
 *
 * <p>The proxy, the injected logger and the data directory all come from the shell's
 * {@code @Plugin} instance through the context, and the plugin object registrations are made against
 * is that instance too: Velocity resolves a plugin argument through its plugin manager, and only the
 * shell is a plugin there.
 */
public final class VelocityCore implements HeimdallCore {

    private VelocityBootstrap bootstrap;

    public VelocityCore() {
    }

    @Override
    public int contractVersion() {
        return ShellContract.VERSION;
    }

    @Override
    public void start(ShellContext context) throws Exception {
        bootstrap = new VelocityBootstrap(
                context.platformPlugin(),
                (ProxyServer) context.platformServer(),
                new Slf4jLogger((Logger) context.platformLogger()),
                context.dataDirectory(),
                context);
        bootstrap.enable();
    }

    @Override
    public void stop() {
        VelocityBootstrap running = bootstrap;
        bootstrap = null;
        if (running != null) {
            running.disable();
        }
    }
}
