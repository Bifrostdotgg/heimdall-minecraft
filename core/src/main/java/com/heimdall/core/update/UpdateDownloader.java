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
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
 * {@link #download}. Safe for concurrent use, and two downloads to the <em>same</em> target are
 * guarded rather than left to race: the second is refused with a clear {@link IOException}, because
 * interleaving two transfers into one {@code .part} produces a half-of-each jar. Different targets
 * proceed in parallel.
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

    /**
     * Redirect hops followed manually, so each one's host and scheme can be re-checked.
     *
     * <p>Generous — GitHub's asset URL bounces through a release redirect to a CDN — but bounded, so
     * a redirect loop cannot spin forever.
     */
    private static final int MAX_REDIRECTS = 10;

    /**
     * Targets a download is in flight to, so two {@code updateNow()} calls cannot race one
     * {@code .part} path.
     *
     * <p>Static because the guard is about a filesystem path, and two {@link UpdateDownloader}
     * instances writing the same target is the same corruption as one instance doing it twice. A path
     * already present means a download is running; the second caller is refused rather than allowed to
     * interleave writes into the same staging file.
     */
    private static final Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();

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

        checkAllowed(new URL(downloadUrl.trim()));

        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create update directory: " + parent);
        }

        File tmp = new File(target.getAbsolutePath() + ".part");
        String key = target.getAbsolutePath();
        if (!IN_FLIGHT.add(key)) {
            // Another update is already writing this exact jar. Interleaving two downloads into one
            // .part is how a half-of-each corrupt jar reaches plugins/update/. Refuse the second.
            throw new IOException("An update to " + target + " is already in progress.");
        }
        try {
            HttpURLConnection connection = open(downloadUrl.trim());
            try {
                long total = transfer(connection, tmp);
                swap(tmp, target);
                logger.info("downloaded " + total + " bytes to " + target);
                return total;
            } finally {
                connection.disconnect();
                deleteQuietly(tmp);
            }
        } finally {
            IN_FLIGHT.remove(key);
        }
    }

    /**
     * Opens the connection, following redirects <strong>manually</strong> so the policy is enforced
     * on <em>every</em> hop.
     *
     * <p>The JDK's automatic redirect following is off here on purpose. It will refuse an
     * {@code https → http} downgrade, but it will happily follow {@code https://github.com → https://<attacker>}
     * — the host is not re-checked, only the scheme. So an allowlisted URL that redirects to an
     * arbitrary host would sail past the single up-front {@link DownloadPolicy#allowsHost} check. Here
     * each {@code Location} is re-validated before it is followed, which closes that: an attacker who
     * can influence a redirect target still cannot leave the allowlist.
     */
    private HttpURLConnection open(String startUrl) throws IOException {
        String current = startUrl;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            URL url = new URL(current);
            checkAllowed(url);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Accept", "application/octet-stream");
            connection.setConnectTimeout(policy.connectTimeoutMs());
            connection.setReadTimeout(policy.readTimeoutMs());

            int code = connection.getResponseCode();
            if (code == HttpURLConnection.HTTP_OK) {
                return connection;
            }
            if (isRedirect(code)) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null || location.trim().isEmpty()) {
                    throw new IOException("Download redirected (" + code + ") with no Location header.");
                }
                // Resolve relative to the current URL, so a relative Location is handled the same as
                // an absolute one — and re-checked on the next loop before it is opened.
                current = new URL(url, location.trim()).toString();
                continue;
            }
            connection.disconnect();
            throw new IOException("Download failed: HTTP " + code);
        }
        throw new IOException("Download followed too many redirects (over " + MAX_REDIRECTS + ").");
    }

    private static boolean isRedirect(int code) {
        return code == HttpURLConnection.HTTP_MOVED_PERM
                || code == HttpURLConnection.HTTP_MOVED_TEMP
                || code == HttpURLConnection.HTTP_SEE_OTHER
                || code == 307
                || code == 308;
    }

    /** Enforces the host allowlist and the scheme rule, naming whatever was refused. */
    private void checkAllowed(URL url) throws IOException {
        if (!policy.allowsHost(url.getHost())) {
            throw new IOException("Refusing to download update from untrusted host: " + url.getHost());
        }
        if (!policy.allowsScheme(url.getProtocol())) {
            throw new IOException(
                    "Refusing to download update over insecure protocol: " + url.getProtocol());
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
     * Moves the completed {@code .part} onto the target, <strong>never destroying the target until a
     * complete replacement exists</strong>.
     *
     * <p>v2 — and this class's own first version — did {@code deleteIfExists(target)} and then
     * {@code copy(tmp, target)}. On a full disk the delete succeeds and the copy throws, and the
     * result is the running jar <em>gone</em> with nothing to replace it: the server does not come
     * back up. So the order is inverted. An atomic move is tried first (one syscall, no window at
     * all); if the filesystem cannot do it — a bind mount, a network share, a cross-device
     * {@code .part} — the {@code .part} is copied to a fresh sibling and only then atomically moved
     * onto the target, so the target is overwritten in one step by a file that already exists in
     * full. At no point is the old jar deleted before the new one is complete.
     */
    private static void swap(File tmp, File target) throws IOException {
        Path tmpPath = tmp.toPath();
        Path targetPath = target.toPath();
        try {
            Files.move(tmpPath, targetPath,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return;
        } catch (AtomicMoveNotSupportedException crossDevice) {
            // Expected on a bind mount / network share. Fall through to copy-then-atomic-move.
        }
        // Copy the finished bytes to a sibling of the TARGET (same filesystem, so the move below can
        // be atomic), then swap it in. The staged sibling — not the target — is what a failed copy
        // leaves behind, and the target is untouched until a whole file is ready to replace it.
        Path staged = targetPath.resolveSibling(target.getName() + ".swap");
        try {
            Files.copy(tmpPath, staged, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(staged, targetPath,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException stillCrossDevice) {
                // The sibling really should be on the target's own filesystem, but if even this move
                // cannot be atomic, a plain replace is the last resort — and it still copies from a
                // COMPLETE staged file rather than from a stream that might fail mid-write.
                Files.move(staged, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(staged);
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
