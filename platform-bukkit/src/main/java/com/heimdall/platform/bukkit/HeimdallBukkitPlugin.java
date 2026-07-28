package com.heimdall.platform.bukkit;

import java.util.logging.Level;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The Bukkit-family entry point — the {@code main} class named by {@code plugin.yml}.
 *
 * <p>Deliberately a shell. Everything it would otherwise do lives in {@link BukkitBootstrap}, which
 * is not a {@code JavaPlugin} and can therefore be reasoned about — and, where the logic is not
 * Bukkit-specific, tested — without a running server. v2's equivalent was 1,086 lines and could be
 * exercised only by starting Minecraft.
 *
 * <p>Both lifecycle methods swallow. Bukkit disables a plugin whose {@code onEnable} throws and
 * prints a stack trace for one whose {@code onDisable} does; neither outcome helps an operator
 * whose real problem is a missing {@code bootstrap.yml}. Every reduced state is handled below this
 * shell, so anything reaching here is a bug — logged as one, with the server left alone.
 *
 * <p>It compiles against the Spigot 1.8.8 API only, so anything it can call is available on every
 * supported server from 1.8.8 upwards.
 */
public final class HeimdallBukkitPlugin extends JavaPlugin {

    private BukkitBootstrap bootstrap;

    @Override
    public void onEnable() {
        // getFile() is protected on JavaPlugin, so this shell is the only thing that can read it —
        // and the self-updater needs it, because Bukkit applies a staged update by matching file
        // names and a wrong name leaves two Heimdall jars in plugins/ after the restart.
        bootstrap = new BukkitBootstrap(this, getFile());
        try {
            bootstrap.enable();
        } catch (Throwable failed) {
            getLogger().log(Level.SEVERE, "Heimdall could not start; the server is unaffected",
                    failed);
        }
    }

    @Override
    public void onDisable() {
        if (bootstrap == null) {
            return;
        }
        try {
            bootstrap.disable();
        } catch (Throwable failed) {
            getLogger().log(Level.SEVERE, "Heimdall did not shut down cleanly", failed);
        } finally {
            bootstrap = null;
        }
    }
}
