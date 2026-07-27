package com.heimdall.platform.bukkit;

import com.heimdall.core.BuildConstants;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The Bukkit-family entry point — the {@code main} class named by {@code plugin.yml}.
 *
 * <p>Phase 0 scaffold: it loads, logs and unloads. It compiles against the Spigot 1.8.8 API only,
 * so anything it can call is available on every supported server from 1.8.8 upwards. Paper-specific
 * behaviour lives in {@code :platform-bukkit-paper} and is reached reflectively once feature code
 * lands.
 */
public final class HeimdallBukkitPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        getLogger().info(
                "Heimdall v" + BuildConstants.VERSION + " (phase 0 scaffold) on "
                        + Bukkit.getVersion());
    }

    @Override
    public void onDisable() {
        getLogger().info("Heimdall v" + BuildConstants.VERSION + " shutting down");
    }
}
