package com.heimdall.core.update;

import com.heimdall.core.http.model.PluginRelease;
import java.io.IOException;

/**
 * Where a downloaded release jar has to go for this platform to pick it up on the next restart.
 *
 * <h2>Why this is an interface and not a method with a switch</h2>
 *
 * <p>The answer is genuinely different per platform, and neither answer is expressible in
 * platform-free code:
 *
 * <ul>
 *   <li><strong>Paper and Spigot</strong> have a first-class mechanism — a jar in
 *       {@code plugins/update/} whose file name matches the running plugin's is installed on the
 *       next start. Finding that file name needs {@code JavaPlugin.getFile()}.
 *   <li><strong>Velocity</strong> has no update folder. What it does have is the running jar's path
 *       (via {@code PluginContainer.getDescription().getSource()}), so the strategy is to replace
 *       that jar in place: on Linux the JVM holds the old inode open until the process exits, so
 *       overwriting it is safe and the operator only has to restart. On Windows the file is locked
 *       and the rename fails, so the fallback writes the jar into the plugin's data directory and
 *       the returned message tells the operator to move it. That fallback is a success, not an
 *       error — see {@link InstallOutcome}.
 * </ul>
 *
 * <p>v2 had both of those inline in its two plugin classes, sharing nothing, which is why the Paper
 * path never learned the fallback the Velocity path needed and the Velocity path never learned
 * Paper's "applied on restart" wording. Here they are two implementations of one contract, and the
 * whole state machine above them is written once.
 *
 * <p><strong>Neither strategy applies until a restart.</strong> An implementation that could
 * hot-swap the running plugin would be lying about what it did; none of them can.
 *
 * <p><strong>Threading.</strong> {@link #install} <em>blocks</em> — it performs the download
 * synchronously through the {@link UpdateDownloader} it is handed. It must never be called on a
 * server or proxy thread, and an implementation must not schedule the work elsewhere and return
 * early: its caller, {@link UpdateService#updateNow()}, is documented as blocking precisely so the
 * result is the real one.
 *
 * <p><strong>Ownership.</strong> The installer does not own the downloader, and must not retain it
 * past the call — it is passed in rather than held so that the policy in force is the service's,
 * decided once at wiring, and cannot be quietly swapped for a looser one by a platform module.
 */
public interface UpdateInstaller {

    /**
     * Puts {@code release} where this platform will pick it up on restart.
     *
     * <p><strong>Blocking.</strong> See the threading note on the interface.
     *
     * @param release the release to install; its {@code downloadUrl} may be {@code null}, and an
     *     implementation should let {@link UpdateDownloader} produce the refusal rather than
     *     inventing its own
     * @param downloader the only sanctioned way to fetch the bytes; enforces the host allowlist,
     *     HTTPS, the size ceiling and the {@code .part} staging
     * @return what happened, including the sentence an operator should be shown
     * @throws IOException if the download or the install failed in a way the implementation cannot
     *     describe as an {@link InstallOutcome}. Prefer {@link InstallOutcome#failed(String)} where
     *     the failure has a useful sentence; {@link UpdateService#updateNow()} catches either.
     */
    InstallOutcome install(PluginRelease release, UpdateDownloader downloader) throws IOException;
}
