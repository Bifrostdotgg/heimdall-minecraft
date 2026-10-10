package com.heimdall.shell.bukkit;

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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The Bukkit-family entry point: the {@code main} class {@code plugin.yml} names.
 *
 * <p>This is the hot-swap shell (departure D87). It is loaded once by the server and never replaced
 * while it runs; everything that can change between releases lives in the core, which
 * {@link ShellHost} loads into a child classloader and can swap. What stays here is what has to keep
 * its identity for the life of the process: the plugin object every registration is made against,
 * a permanent relay on every {@code plugin.yml} command, the {@code HeimdallTunnel} other plugins
 * hold, and the loader itself.
 *
 * <p>Both lifecycle methods swallow, as before the split. Bukkit disables a plugin whose
 * {@code onEnable} throws and prints a trace for one whose {@code onDisable} does, and neither helps
 * an operator; every reduced state is reported below this class.
 */
public final class HeimdallBukkitPlugin extends JavaPlugin {

    /** The admin verbs, on which the shell itself answers {@code swap}. */
    private static final List<String> ADMIN_COMMANDS = java.util.Arrays.asList("hd", "hwl");

    private ShellHost host;

    @Override
    public void onEnable() {
        try {
            Platform platform = new Platform();
            host = new ShellHost(platform, ShellBuildConstants.VERSION);
            installDescriptorRelays(host);
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

    /**
     * Puts a permanent relay on every command {@code plugin.yml} declares, before any core runs.
     *
     * <p>Bukkit fixes those commands for the life of the plugin, so whatever it holds as their
     * executor must be a shell object: a core object there would pin that core's classloader
     * forever. With the relays installed first, each command answers sensibly whatever state the
     * core is in, and {@code /hd swap} works with no core at all.
     */
    private void installDescriptorRelays(ShellHost target) {
        Map<String, Map<String, Object>> declared = getDescription().getCommands();
        if (declared == null) {
            return;
        }
        for (Map.Entry<String, Map<String, Object>> entry : declared.entrySet()) {
            Map<String, Object> fields = entry.getValue() == null
                    ? Collections.<String, Object>emptyMap() : entry.getValue();
            target.relays().installPermanent(
                    entry.getKey(),
                    aliasesOf(fields.get("aliases")),
                    stringOf(fields.get("permission")),
                    stringOf(fields.get("description")),
                    stringOf(fields.get("usage")),
                    ADMIN_COMMANDS.contains(entry.getKey().toLowerCase(java.util.Locale.ROOT)));
        }
    }

    private static List<String> aliasesOf(Object raw) {
        List<String> out = new ArrayList<String>();
        if (raw instanceof List) {
            for (Object alias : (List<?>) raw) {
                if (alias != null) {
                    out.add(alias.toString());
                }
            }
        } else if (raw != null) {
            out.add(raw.toString());
        }
        return out;
    }

    private static String stringOf(Object raw) {
        return raw == null ? "" : raw.toString();
    }

    /** What {@link ShellHost} needs to know about this server. */
    private final class Platform implements ShellPlatform {

        private final ShellLog log = new JulShellLog(getLogger());
        private final BukkitCommands commands = new BukkitCommands(HeimdallBukkitPlugin.this, log);
        private final BukkitAudience audience = new BukkitAudience();

        @Override
        public String name() {
            return ShellContract.PLATFORM_BUKKIT;
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
            return "hd";
        }

        @Override
        public Registration publishTunnel(final HeimdallTunnel tunnel) {
            HeimdallTunnelProvider.install(tunnel);
            // The ServicesManager is Bukkit's own idiom and where a Bukkit plugin author looks
            // first; HeimdallTunnelProvider is the portable route that also works on the proxies.
            Bukkit.getServicesManager().register(
                    HeimdallTunnel.class, tunnel, HeimdallBukkitPlugin.this, ServicePriority.Normal);
            return Registration.once(new Runnable() {
                @Override
                public void run() {
                    HeimdallTunnelProvider.uninstall(tunnel);
                    Bukkit.getServicesManager().unregister(HeimdallTunnel.class, tunnel);
                }
            });
        }

        @Override
        public int sweep(LoadedCore retired) {
            return BukkitSweep.sweep(HeimdallBukkitPlugin.this, retired, log);
        }
    }
}
