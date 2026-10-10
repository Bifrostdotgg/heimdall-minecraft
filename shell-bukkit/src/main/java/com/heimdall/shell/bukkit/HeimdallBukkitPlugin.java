package com.heimdall.shell.bukkit;

import com.heimdall.shell.ShellBuildConstants;
import com.heimdall.shell.hotswap.JulShellLog;
import com.heimdall.shell.hotswap.ShellHost;
import com.heimdall.shell.hotswap.ShellLog;
import com.heimdall.shell.hotswap.ShellPlatform;
import java.io.File;
import java.nio.file.Path;
import java.util.logging.Level;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The Bukkit-family entry point: the {@code main} class {@code plugin.yml} names.
 *
 * <p>This is the hot-swap shell (departure D87). It is loaded once by the server and never replaced
 * while it runs; everything that can change between releases lives in the core, which
 * {@link ShellHost} loads into a child classloader and can swap. What stays here is what has to keep
 * its identity for the life of the process: the plugin object every registration is made against,
 * the descriptor commands, and the loader itself.
 *
 * <p>Both lifecycle methods swallow, as before the split. Bukkit disables a plugin whose
 * {@code onEnable} throws and prints a trace for one whose {@code onDisable} does, and neither helps
 * an operator; every reduced state is reported below this class.
 */
public final class HeimdallBukkitPlugin extends JavaPlugin {

    private ShellHost host;

    @Override
    public void onEnable() {
        try {
            host = new ShellHost(new Platform(), ShellBuildConstants.VERSION);
            host.boot();
        } catch (Throwable failed) {
            getLogger().log(Level.SEVERE, "Heimdall's shell could not start; the server is "
                    + "unaffected", failed);
        }
    }

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

    /** What {@link ShellHost} needs to know about this server. */
    private final class Platform implements ShellPlatform {

        private final ShellLog log = new JulShellLog(getLogger());

        @Override
        public String name() {
            return com.heimdall.shell.contract.ShellContract.PLATFORM_BUKKIT;
        }

        @Override
        public Object plugin() {
            return HeimdallBukkitPlugin.this;
        }

        @Override
        public Object server() {
            return null;
        }

        @Override
        public Object logger() {
            return getLogger();
        }

        @Override
        public Path dataDirectory() {
            File folder = getDataFolder();
            if (!folder.isDirectory() && !folder.mkdirs()) {
                // Bukkit only creates a plugin's directory if it ships a config.yml, and this one
                // deliberately does not. The core reports the consequence; this only tries.
                log.warn("could not create " + folder);
            }
            return folder.toPath();
        }

        @Override
        public File shellJar() {
            // getFile() is protected on JavaPlugin, so only this class can read it, and both the
            // boot extraction and the updater's restart staging need it.
            return getFile();
        }

        @Override
        public ShellLog log() {
            return log;
        }

        @Override
        public ClassLoader shellLoader() {
            return HeimdallBukkitPlugin.class.getClassLoader();
        }
    }
}
