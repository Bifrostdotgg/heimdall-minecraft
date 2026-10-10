package com.heimdall.platform.bukkit;

import com.heimdall.shell.contract.HeimdallCore;
import com.heimdall.shell.contract.ShellContext;
import com.heimdall.shell.contract.ShellContract;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The Bukkit family's core entry point, which the shell reaches through
 * {@code com.heimdall.platform.common.CoreEntry} (departure D87).
 *
 * <p>The successor of the old {@code HeimdallBukkitPlugin}'s two lifecycle methods. The plugin
 * object is the shell's, handed over through the context, so every registration below is still made
 * against the plugin the server knows; only the code making it lives in a swappable classloader.
 *
 * <p>Public with a public constructor because it is constructed reflectively, by name.
 */
public final class BukkitCore implements HeimdallCore {

    private BukkitBootstrap bootstrap;

    public BukkitCore() {
    }

    @Override
    public int contractVersion() {
        return ShellContract.VERSION;
    }

    @Override
    public void start(ShellContext context) throws Exception {
        JavaPlugin plugin = (JavaPlugin) context.platformPlugin();
        bootstrap = new BukkitBootstrap(plugin, context.shellJar(), context);
        bootstrap.enable();
    }

    @Override
    public void stop() {
        BukkitBootstrap running = bootstrap;
        bootstrap = null;
        if (running != null) {
            running.disable();
        }
    }
}
