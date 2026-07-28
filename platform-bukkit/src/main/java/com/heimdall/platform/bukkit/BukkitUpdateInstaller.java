package com.heimdall.platform.bukkit;

import com.heimdall.core.http.model.PluginRelease;
import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.update.InstallOutcome;
import com.heimdall.core.update.UpdateDownloader;
import com.heimdall.core.update.UpdateInstaller;
import java.io.File;
import java.io.IOException;

/**
 * Installs an update the way the Bukkit family already knows how: {@code plugins/update/}.
 *
 * <p>Every server in this family — CraftBukkit, Spigot, Paper and their forks, from 1.8.8 to current
 * — looks in that folder at startup and, for each jar whose <em>file name</em> matches one already
 * in {@code plugins/}, replaces the old one before loading anything. So the whole install is "put
 * the download next door under exactly the same name", and the server does the rest at a moment when
 * nothing has the file open.
 *
 * <p>The name match is the part worth being careful about, and it is why this takes the running jar
 * rather than deriving a name. A downloaded release is called something like
 * {@code heimdall-whitelist-3.1.0.jar}; the file on disk might be {@code Heimdall.jar} because an
 * operator renamed it, or might carry a different version. Writing the release's own name would
 * leave <em>both</em> jars in {@code plugins/} after the restart — two copies of Heimdall, one of
 * which loses the command registrations to the other, which is a considerably worse outcome than not
 * updating.
 *
 * <p>Nothing is applied to the running server, and the message says so. v2 said the same thing and
 * for the same reason: a plugin cannot replace its own classes in a live JVM, and pretending
 * otherwise produces a support report that says the update did nothing.
 *
 * <h2>Threading</h2>
 *
 * <p>{@link #install} blocks for the length of a download of up to 50 MB. It is called from
 * {@code heimdall-io} by the admin command, never from a server thread.
 */
final class BukkitUpdateInstaller implements UpdateInstaller {

    /** The folder Bukkit applies on startup, relative to {@code plugins/}. */
    private static final String UPDATE_FOLDER = "update";

    private final HeimdallLogger logger;

    /** The plugin's own jar, as the server loaded it. */
    private final File runningJar;

    /** The plugin directory — {@code plugins/} — which is where the update folder goes. */
    private final File pluginsDirectory;

    /**
     * @param runningJar this plugin's jar file, from {@code JavaPlugin.getFile()}. The only thing
     *     that knows the name the server will match against.
     * @param dataFolder the plugin's data directory, whose parent is {@code plugins/}
     */
    BukkitUpdateInstaller(HeimdallLogger logger, File runningJar, File dataFolder) {
        this.logger = logger;
        this.runningJar = runningJar;
        this.pluginsDirectory = dataFolder == null ? null : dataFolder.getParentFile();
    }

    /** Whether this platform gave us enough to install anything. */
    boolean isUsable() {
        return runningJar != null && pluginsDirectory != null;
    }

    @Override
    public InstallOutcome install(PluginRelease release, UpdateDownloader downloader)
            throws IOException {
        if (!isUsable()) {
            // Reachable in principle on an exotic loader that hides the plugin's own file. Refusing
            // is the right answer: the alternative is inventing a filename, and a wrong filename
            // leaves two Heimdall jars in plugins/ after the restart.
            return InstallOutcome.failed("this server did not say where Heimdall's own jar is, so "
                    + "an update cannot be staged; download it manually.");
        }
        File updateFolder = new File(pluginsDirectory, UPDATE_FOLDER);
        File target = new File(updateFolder, runningJar.getName());
        long bytes = downloader.download(release.downloadUrl(), target);
        logger.info("staged " + bytes + " bytes at " + target
                + "; the server will apply it on its next start");
        return InstallOutcome.installed(target.toPath(),
                "Downloaded " + release.version() + " to plugins/" + UPDATE_FOLDER + "/"
                        + runningJar.getName() + " — restart the server to apply it.");
    }
}
