package com.heimdall.shell.bungee;

import com.heimdall.shell.ShellBuildConstants;
import com.heimdall.shell.contract.ShellContract;
import com.heimdall.shell.hotswap.JulShellLog;
import com.heimdall.shell.hotswap.ShellHost;
import com.heimdall.shell.hotswap.ShellLog;
import com.heimdall.shell.hotswap.ShellPlatform;
import java.io.File;
import java.nio.file.Path;
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
    }
}
