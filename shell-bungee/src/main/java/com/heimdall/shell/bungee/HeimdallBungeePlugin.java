package com.heimdall.shell.bungee;

import com.heimdall.api.HeimdallTunnel;
import com.heimdall.api.HeimdallTunnelProvider;
import com.heimdall.core.util.Registration;
import com.heimdall.shell.ShellBuildConstants;
import com.heimdall.shell.contract.ShellContract;
import com.heimdall.shell.hotswap.CommandPlatform;
import com.heimdall.shell.hotswap.JulShellLog;
import com.heimdall.shell.hotswap.LoadedCore;
import com.heimdall.shell.hotswap.ShellAudience;
import com.heimdall.shell.hotswap.ShellHost;
import com.heimdall.shell.hotswap.ShellLog;
import com.heimdall.shell.hotswap.ShellPlatform;
import java.io.File;
import java.nio.file.Path;
import java.util.Collections;
import java.util.logging.Level;
import net.md_5.bungee.api.plugin.Plugin;

/**
 * The BungeeCord entry point, and the hot-swap shell on this platform (departure D87).
 *
 * <p>Only this class may extend {@code Plugin}: BungeeCord's constructor insists on being called by
 * its own {@code PluginClassloader}, which a core loaded by the shell never is. Core listeners and
 * commands are ordinary objects registered against this instance.
 *
 * <p>The plugin's identity still comes from {@code bungee.yml}, whose {@code name} is what puts the
 * data directory at {@code plugins/Heimdall/}.
 */
public final class HeimdallBungeePlugin extends Plugin {

    private ShellHost host;

    @Override
    public void onEnable() {
        try {
            host = new ShellHost(new Platform(), ShellBuildConstants.VERSION);
            // The admin verbs before any core runs, so /hdp swap works with no core. Every other
            // proxy command is registered when a core binds it.
            host.relays().installPermanent("hdp", Collections.singletonList("heimdallproxy"),
                    "heimdall.admin", "Heimdall administration", "/hdp", true);
            host.relays().installPermanent("hwl", Collections.singletonList("heimdallwhitelist"),
                    "heimdall.admin", "Deprecated alias for /hdp", "/hwl", true);
            host.boot();
        } catch (Throwable failed) {
            getLogger().log(Level.SEVERE, "Heimdall's shell could not start; the proxy is "
                    + "unaffected", failed);
        }
    }

    /**
     * Stops the core while the proxy is still up enough to log about it. BungeeCord calls this
     * before it closes its IO threads, and it is the only chance the core gets to stop cleanly.
     */
    @Override
    public void onDisable() {
        ShellHost stopping = host;
        host = null;
        if (stopping == null) {
            return;
        }
        try {
            stopping.shutdown();
        } catch (Throwable failed) {
            getLogger().log(Level.SEVERE, "Heimdall did not shut down cleanly", failed);
        }
    }

    /** What {@link ShellHost} needs to know about this proxy. */
    private final class Platform implements ShellPlatform {

        private final ShellLog log = new JulShellLog(getLogger());
        private final BungeeCommands commands =
                new BungeeCommands(HeimdallBungeePlugin.this, getProxy(), log);
        private final BungeeAudience audience = new BungeeAudience(getProxy());

        @Override
        public String name() {
            return ShellContract.PLATFORM_BUNGEE;
        }

        @Override
        public Object plugin() {
            return HeimdallBungeePlugin.this;
        }

        @Override
        public Object server() {
            return getProxy();
        }

        @Override
        public Object logger() {
            return getLogger();
        }

        @Override
        public Path dataDirectory() {
            File folder = getDataFolder();
            if (!folder.isDirectory() && !folder.mkdirs()) {
                log.warn("could not create " + folder);
            }
            return folder.toPath();
        }

        @Override
        public File shellJar() {
            return getFile();
        }

        @Override
        public ShellLog log() {
            return log;
        }

        @Override
        public ClassLoader shellLoader() {
            return HeimdallBungeePlugin.class.getClassLoader();
        }

        @Override
        public CommandPlatform commands() {
            return commands;
        }

        @Override
        public ShellAudience audience() {
            return audience;
        }

        @Override
        public String adminLabel() {
            return "hdp";
        }

        @Override
        public Registration publishTunnel(final HeimdallTunnel tunnel) {
            HeimdallTunnelProvider.install(tunnel);
            return Registration.once(new Runnable() {
                @Override
                public void run() {
                    HeimdallTunnelProvider.uninstall(tunnel);
                }
            });
        }

        @Override
        public int sweep(LoadedCore retired) {
            return BungeeSweep.sweep(getProxy(), HeimdallBungeePlugin.this, retired, log);
        }
    }
}
