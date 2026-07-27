package com.heimdall.platform.bukkit;

import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.platform.InstanceRoleDetector;
import com.heimdall.platform.bukkit.adapter.ProxyForwarding;
import java.io.File;

/**
 * The two signals {@link InstanceRoleDetector} needs, answered for a Bukkit-family server.
 *
 * <p>{@link #isProxy()} is permanently false — a Bukkit server is never the proxy — and
 * {@link #isBehindProxy()} is read from the server's own configuration by
 * {@link ProxyForwarding}. Together with an explicit role in {@code bootstrap.yml} winning over
 * both, that is the whole matrix.
 *
 * <p>The signals are read <strong>once</strong>, at enable, and remembered. Neither can change
 * without a restart: turning BungeeCord forwarding on requires editing {@code spigot.yml} and
 * restarting, so re-reading the file per query would only add IO to a hot path in exchange for
 * detecting a change that cannot happen.
 */
final class BukkitRoleDetector implements InstanceRoleDetector {

    private final boolean behindProxy;

    /**
     * @param serverDirectory the server's working directory, where {@code spigot.yml} lives
     */
    BukkitRoleDetector(File serverDirectory, HeimdallLogger logger) {
        this.behindProxy = ProxyForwarding.isEnabled(serverDirectory, logger);
    }

    @Override
    public boolean isProxy() {
        return false;
    }

    @Override
    public boolean isBehindProxy() {
        return behindProxy;
    }
}
