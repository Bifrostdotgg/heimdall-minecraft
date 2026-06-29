package com.heimdall.whitelist.core;

import com.heimdall.whitelist.BuildConstants;

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
 * Platform-agnostic update checker.
 *
 * <p>Asks the Heimdall bot API for the latest published plugin version (the bot
 * sources this from GitHub Releases), compares it against this build's
 * {@link BuildConstants#VERSION}, and exposes the result so the platform layer
 * can log a warning, notify admins on join, and offer {@code /hwl update}.
 *
 * <p>All network work is blocking — callers must run {@link #checkNow()} and
 * {@link #downloadUpdate(File)} off the main server/proxy thread.
 */
public class UpdateChecker {

  /** Download host allowlist — {@code /hwl update} only fetches from GitHub. */
  private static final String[] ALLOWED_DOWNLOAD_HOSTS = {
      "github.com", "githubusercontent.com"
  };

  private static final int DOWNLOAD_CONNECT_TIMEOUT_MS = 10_000;
  private static final int DOWNLOAD_READ_TIMEOUT_MS = 60_000;
  /** Hard cap on a downloaded JAR (50 MB) — defends against a runaway body. */
  private static final long MAX_DOWNLOAD_BYTES = 50L * 1024 * 1024;

  private final PluginLogger logger;
  private final ApiClient apiClient;
  private final String currentVersion;

  private volatile PluginReleaseInfo latestRelease;
  private volatile boolean updateAvailable;

  public UpdateChecker(PluginLogger logger, ApiClient apiClient) {
    this.logger = logger;
    this.apiClient = apiClient;
    this.currentVersion = BuildConstants.VERSION;
  }

  public String getCurrentVersion() {
    return currentVersion;
  }

  public boolean isUpdateAvailable() {
    return updateAvailable;
  }

  /** The latest release metadata, or null if no successful check has run yet. */
  public PluginReleaseInfo getLatestRelease() {
    return latestRelease;
  }

  /**
   * Query the bot for the latest release and update internal state. Blocking —
   * run off-thread. Returns true if an update is available, false otherwise
   * (including on error, which is logged and swallowed).
   */
  public boolean checkNow() {
    try {
      PluginReleaseInfo release = apiClient.getLatestRelease().join();
      if (release == null || release.getVersion() == null || release.getVersion().isBlank()) {
        logger.debug("[UpdateChecker] No release information returned by the bot.");
        return false;
      }

      this.latestRelease = release;
      boolean available = compareVersions(currentVersion, release.getVersion()) < 0;
      this.updateAvailable = available;

      if (available) {
        logger.warning("================================================");
        logger.warning("  A new HeimdallWhitelist version is available!");
        logger.warning("  Installed: " + currentVersion + "   Latest: " + normalize(release.getVersion()));
        logger.warning("  Run '/hwl update' to download it (applied on restart).");
        if (release.getHtmlUrl() != null && !release.getHtmlUrl().isBlank()) {
          logger.warning("  Release notes: " + release.getHtmlUrl());
        }
        logger.warning("================================================");
      } else {
        logger.info("[UpdateChecker] HeimdallWhitelist is up to date (" + currentVersion + ").");
      }
      return available;
    } catch (Exception e) {
      logger.debug("[UpdateChecker] Update check failed: " + rootMessage(e));
      return false;
    }
  }

  /**
   * Download the latest release JAR to {@code target}. Blocking — run off-thread.
   * Validates the download URL host against {@link #ALLOWED_DOWNLOAD_HOSTS}.
   *
   * @throws IOException if no download is available, the host is not allowed, or
   *                     the transfer fails.
   */
  public void downloadUpdate(File target) throws IOException {
    PluginReleaseInfo release = this.latestRelease;
    if (release == null || release.getDownloadUrl() == null || release.getDownloadUrl().isBlank()) {
      throw new IOException("No download URL available — run an update check first.");
    }

    URL url = new URL(release.getDownloadUrl());
    if (!isAllowedHost(url.getHost())) {
      throw new IOException("Refusing to download update from untrusted host: " + url.getHost());
    }
    if (!"https".equalsIgnoreCase(url.getProtocol())) {
      throw new IOException("Refusing to download update over insecure protocol: " + url.getProtocol());
    }

    File parent = target.getParentFile();
    if (parent != null && !parent.exists() && !parent.mkdirs()) {
      throw new IOException("Could not create update directory: " + parent);
    }

    File tmp = new File(target.getAbsolutePath() + ".part");
    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
    connection.setInstanceFollowRedirects(true);
    connection.setRequestMethod("GET");
    connection.setRequestProperty("User-Agent", "HeimdallWhitelist/" + currentVersion);
    connection.setRequestProperty("Accept", "application/octet-stream");
    connection.setConnectTimeout(DOWNLOAD_CONNECT_TIMEOUT_MS);
    connection.setReadTimeout(DOWNLOAD_READ_TIMEOUT_MS);

    try {
      int code = connection.getResponseCode();
      if (code != 200) {
        throw new IOException("Download failed: HTTP " + code);
      }

      long total = 0;
      try (InputStream in = new BufferedInputStream(connection.getInputStream());
          OutputStream out = new FileOutputStream(tmp)) {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
          total += read;
          if (total > MAX_DOWNLOAD_BYTES) {
            throw new IOException("Update exceeds maximum allowed size (" + MAX_DOWNLOAD_BYTES + " bytes).");
          }
          out.write(buffer, 0, read);
        }
      }

      // Atomic-ish swap into place once fully written.
      Files.deleteIfExists(target.toPath());
      if (!tmp.renameTo(target)) {
        // renameTo can fail across odd filesystems — fall back to a copy.
        Files.copy(tmp.toPath(), target.toPath());
        Files.deleteIfExists(tmp.toPath());
      }
      logger.info("[UpdateChecker] Downloaded " + total + " bytes to " + target);
    } finally {
      connection.disconnect();
      Files.deleteIfExists(tmp.toPath());
    }
  }

  private static boolean isAllowedHost(String host) {
    if (host == null) {
      return false;
    }
    String lower = host.toLowerCase();
    for (String allowed : ALLOWED_DOWNLOAD_HOSTS) {
      if (lower.equals(allowed) || lower.endsWith("." + allowed)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Compare two version strings. Returns a negative number if {@code a} is older
   * than {@code b}, zero if equal, positive if {@code a} is newer. Tolerates a
   * leading "v" and non-numeric suffixes (e.g. "2.1.0-rc1" is treated as 2.1.0).
   */
  public static int compareVersions(String a, String b) {
    int[] pa = parse(a);
    int[] pb = parse(b);
    int len = Math.max(pa.length, pb.length);
    for (int i = 0; i < len; i++) {
      int va = i < pa.length ? pa[i] : 0;
      int vb = i < pb.length ? pb[i] : 0;
      if (va != vb) {
        return Integer.compare(va, vb);
      }
    }
    return 0;
  }

  private static int[] parse(String version) {
    String normalized = normalize(version);
    String[] parts = normalized.split("\\.");
    int[] out = new int[parts.length];
    for (int i = 0; i < parts.length; i++) {
      out[i] = leadingInt(parts[i]);
    }
    return out;
  }

  /** Strip a leading "v" and surrounding whitespace. */
  private static String normalize(String version) {
    if (version == null) {
      return "0";
    }
    String trimmed = version.trim();
    if (trimmed.startsWith("v") || trimmed.startsWith("V")) {
      trimmed = trimmed.substring(1);
    }
    return trimmed.isEmpty() ? "0" : trimmed;
  }

  /** Parse the leading integer of a component, ignoring suffixes like "-rc1". */
  private static int leadingInt(String component) {
    int end = 0;
    while (end < component.length() && Character.isDigit(component.charAt(end))) {
      end++;
    }
    if (end == 0) {
      return 0;
    }
    try {
      return Integer.parseInt(component.substring(0, end));
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  private static String rootMessage(Throwable t) {
    Throwable current = t;
    String message = t.getMessage();
    while (current.getCause() != null) {
      current = current.getCause();
      if (current.getMessage() != null) {
        message = current.getMessage();
      }
    }
    return message != null ? message : t.getClass().getSimpleName();
  }
}
