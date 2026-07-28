package com.heimdall.core.update;

import com.heimdall.core.BuildConstants;
import com.heimdall.core.log.HeimdallLogger;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;

/**
 * Fetches a release jar to a path an {@link UpdateInstaller} chose, under a {@link DownloadPolicy}.
 *
 * <p>This is v2's {@code UpdateChecker.downloadUpdate} with the policy lifted out and the
 * destination made the caller's decision. The split matters because the two shipped install
 * strategies want different destinations for the same bytes — Paper wants
 * {@code plugins/update/<own-jar-name>.jar}, Velocity wants the running jar's own path — and v2
 * expressed that by having each platform class construct a {@link File} and hand it in. Keeping
 * that shape, rather than letting the downloader decide, is what lets a third strategy exist
 * without touching this class.
 *
 * <h2>The hardening is deliberately verbatim</h2>
 *
 * <p>Every property below is v2's, kept because each one is load-bearing rather than because it was
 * there:
 *
 * <ul>
 *   <li><strong>Host allowlist and HTTPS, checked before the socket opens.</strong> The URL comes
 *       from the bot, over the network. See {@link DownloadPolicy} for why that makes it untrusted
 *       input. Both refusals name what was refused, because "update failed" with no host in it is
 *       unactionable for an operator whose bot is misconfigured.
 *   <li><strong>Non-200 is a failure naming the status.</strong> Including the redirects the JDK
 *       followed for us: {@link HttpURLConnection#setInstanceFollowRedirects(boolean)} is on, since
 *       GitHub's asset URL always redirects to a CDN — but the policy is re-checked on nothing
 *       after that hop, which is the one place this design trusts the JDK and GitHub jointly.
 *   <li><strong>A byte ceiling enforced as the stream is read.</strong> Not from
 *       {@code Content-Length}, which the sender also controls.
 *   <li><strong>A {@code .part} file swapped into place only once the body is complete.</strong>
 *       This is the property that actually protects a server. Writing straight to the target means
 *       a transfer that dies at 70% leaves a truncated jar where the platform expects a real one —
 *       on Paper that is a {@code plugins/update/} file the server will happily install on the next
 *       start, and the server then does not come back up. Staging turns a failed download into a
 *       no-op.
 * </ul>
 *
 * <p>One deliberate departure from v2: v2's {@code finally} called
 * {@code Files.deleteIfExists(tmp)} directly, so an {@code IOException} from the cleanup would
 * replace whatever real failure was already propagating — the operator would be told the temp file
 * could not be deleted instead of being told the host was refused. Here the cleanup cannot mask the
 * primary exception.
 *
 * <h2>Threading and ownership</h2>
 *
 * <p><strong>{@link #download} blocks and must never be called on a server or proxy thread.</strong>
 * It is a multi-megabyte transfer with a 60-second read timeout; run on the main thread it is a
 * minute-long freeze and a watchdog crash. Both platform installers run it on an async task, and
 * {@link UpdateService#updateNow()} — the only core caller — is itself documented as blocking and
 * is only ever reached from {@code heimdall-sched} or a command's async handler.
 *
 * <p>Owns no resources between calls: the connection and both streams are opened and closed inside
 * {@link #download}. Safe for concurrent use, though two concurrent downloads to the <em>same</em>
 * target would race over one {@code .part} path — nothing in v3 does that, and serialising it here
 * would be pretending the class knows about a coordination problem that belongs to the caller.
 */
public final class UpdateDownloader {

    /**
     * Announced so a fleet's plugin versions are visible in the bot's and GitHub's logs.
     *
     * <p>Same shape as {@code RequestExecutor.USER_AGENT} and built the same way, rather than
     * shared with it: that constant is package-private in {@code com.heimdall.core.http}, and
     * widening it so this class can read it would make an internal detail of the API client public
     * for a string.
     */
    static final String USER_AGENT = "Heimdall/" + BuildConstants.VERSION;

    private static final int BUFFER_BYTES = 8192;

    private final HeimdallLogger logger;
    private final DownloadPolicy policy;

    public UpdateDownloader(HeimdallLogger logger, DownloadPolicy policy) {
        if (logger == null) {
            throw new IllegalArgumentException("logger is required");
        }
        this.logger = logger;
        this.policy = policy == null ? DownloadPolicy.github() : policy;
    }

    /** The rules this downloader enforces. */
    public DownloadPolicy policy() {
        return policy;
    }

    /**
     * Downloads {@code downloadUrl} to {@code target}, via a {@code .part} file swapped into place.
     *
     * <p><strong>Blocking.</strong> See the threading note on the class.
     *
     * @return the number of bytes written
     * @throws IOException if the URL is missing or malformed, its host or scheme is refused by the
     *     {@link DownloadPolicy}, the parent directory cannot be created, the server answered with
     *     anything other than 200, the body exceeded {@link DownloadPolicy#maxBytes()}, or the
     *     transfer failed. In every one of those cases the target is left exactly as it was.
     */
    public long download(String downloadUrl, File target) throws IOException {
        if (downloadUrl == null || downloadUrl.trim().isEmpty()) {
            throw new IOException("No download URL available — run an update check first.");
        }
        if (target == null) {
            throw new IOException("No download target was chosen.");
        }

        URL url = new URL(downloadUrl.trim());
        if (!policy.allowsHost(url.getHost())) {
            throw new IOException("Refusing to download update from untrusted host: " + url.getHost());
        }
        if (!policy.allowsScheme(url.getProtocol())) {
            throw new IOException(
                    "Refusing to download update over insecure protocol: " + url.getProtocol());
        }

        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create update directory: " + parent);
        }

        File tmp = new File(target.getAbsolutePath() + ".part");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Accept", "application/octet-stream");
        connection.setConnectTimeout(policy.connectTimeoutMs());
        connection.setReadTimeout(policy.readTimeoutMs());

        try {
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("Download failed: HTTP " + code);
            }

            long total = transfer(connection, tmp);
            swap(tmp, target);
            final long written = total;
            logger.info("downloaded " + written + " bytes to " + target);
            return written;
        } finally {
            connection.disconnect();
            deleteQuietly(tmp);
        }
    }

    /** Streams the body into {@code tmp}, aborting past the ceiling. */
    private long transfer(HttpURLConnection connection, File tmp) throws IOException {
        long total = 0;
        InputStream in = new BufferedInputStream(connection.getInputStream());
        try {
            OutputStream out = new FileOutputStream(tmp);
            try {
                byte[] buffer = new byte[BUFFER_BYTES];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    total += read;
                    if (total > policy.maxBytes()) {
                        throw new IOException("Update exceeds maximum allowed size ("
                                + policy.maxBytes() + " bytes).");
                    }
                    out.write(buffer, 0, read);
                }
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
        return total;
    }

    /**
     * Moves the completed {@code .part} onto the target.
     *
     * <p>{@code renameTo} first, with a copy as the fallback: the two can end up on different
     * filesystems when a server's {@code plugins} directory is a bind mount or a network share, and
     * {@code File.renameTo} answers {@code false} there rather than throwing. v2 hit this.
     */
    private static void swap(File tmp, File target) throws IOException {
        Files.deleteIfExists(target.toPath());
        if (!tmp.renameTo(target)) {
            Files.copy(tmp.toPath(), target.toPath());
            Files.deleteIfExists(tmp.toPath());
        }
    }

    /**
     * Removes the staging file without letting its own failure replace the real one.
     *
     * <p>Called from a {@code finally} that may already be unwinding the exception the operator
     * actually needs to read.
     */
    private void deleteQuietly(File tmp) {
        try {
            Files.deleteIfExists(tmp.toPath());
        } catch (IOException e) {
            logger.debug("could not remove the staging file " + tmp + ": " + e.getMessage());
        }
    }
}
