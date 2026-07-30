package com.heimdall.platform.bungee;

import com.heimdall.core.http.model.PluginRelease;
import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.update.InstallOutcome;
import com.heimdall.core.update.UpdateDownloader;
import com.heimdall.core.update.UpdateInstaller;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import net.md_5.bungee.api.plugin.Plugin;

/**
 * Installs an update on a proxy, which has no {@code plugins/update/} folder to put it in.
 *
 * <p>BungeeCord applies whatever is in {@code plugins/} at startup and has no staging convention at
 * all — but {@link Plugin#getFile()} hands over the running plugin's own jar, which the Bukkit family
 * does not offer portably. So the strategy is the inverse of the Bukkit one and identical to the
 * Velocity one: replace the live jar in place, and let the restart pick it up.
 *
 * <p>That is safe on Linux and on macOS, where the JVM holds an open file descriptor rather than a
 * lock on the name: the old inode stays alive for the running process while the directory entry
 * points at the new bytes. It is <strong>not</strong> safe on Windows, where the open jar cannot be
 * replaced at all — which is why the fallback exists rather than being a nicety.
 *
 * <p>The fallback writes into the plugin's data directory and tells the operator to move it. That is
 * a worse experience and a much better outcome than either alternative: writing a second jar into
 * {@code plugins/} would leave two copies of Heimdall for the proxy to load — and on BungeeCord that
 * is worse than on Velocity, which refuses a duplicate plugin id, whereas BungeeCord loads both jars
 * and lets the second registration quietly win.
 *
 * <h2>Threading</h2>
 *
 * <p>{@link #install} blocks for the length of a download of up to 50 MB, twice in the fallback case.
 * It is called from {@code heimdall-io}, never from a proxy event thread.
 */
final class BungeeUpdateInstaller implements UpdateInstaller {

    private final HeimdallLogger logger;
    private final Plugin plugin;
    private final Path dataDirectory;

    BungeeUpdateInstaller(HeimdallLogger logger, Plugin plugin, Path dataDirectory) {
        this.logger = logger;
        this.plugin = plugin;
        this.dataDirectory = dataDirectory;
    }

    @Override
    public InstallOutcome install(PluginRelease release, UpdateDownloader downloader)
            throws IOException {
        File ownJar = resolveOwnJar();
        if (ownJar != null) {
            try {
                long bytes = downloader.download(release.downloadUrl(), ownJar);
                logger.info("replaced " + ownJar + " with " + bytes
                        + " bytes; the proxy will load it on its next start");
                return InstallOutcome.installed(ownJar.toPath(),
                        "Installed " + release.version() + " over the running jar — restart the "
                                + "proxy to apply it.");
            } catch (IOException lockedOrUnwritable) {
                // The Windows case, and any read-only plugins directory. Not fatal, and worth a
                // warning rather than silence: the fallback below leaves the operator with a manual
                // step, and they need to know why.
                logger.warn("could not replace the running jar (" + lockedOrUnwritable.getMessage()
                        + "); falling back to the data directory");
            }
        }

        File fallback = dataDirectory.resolve("heimdall-" + release.version() + ".jar").toFile();
        downloader.download(release.downloadUrl(), fallback);
        return InstallOutcome.installed(fallback.toPath(),
                "Downloaded " + release.version() + " to " + fallback.getAbsolutePath()
                        + " — the running jar could not be replaced automatically, so move it into "
                        + "plugins/ (replacing the old one) and restart.");
    }

    /**
     * This plugin's own jar, as the proxy loaded it.
     *
     * <p>{@code Plugin.getFile()} reads the {@code PluginDescription}'s file, which BungeeCord sets
     * when it detects the jar — but a description built by anything other than the ordinary loader
     * has a null there, so this answers {@code null} rather than throwing and the caller takes the
     * fallback.
     */
    private File resolveOwnJar() {
        try {
            File file = plugin.getFile();
            return file != null && file.isFile() ? file : null;
        } catch (RuntimeException unavailable) {
            logger.debug(() -> "could not resolve Heimdall's own jar: " + unavailable);
            return null;
        }
    }
}
