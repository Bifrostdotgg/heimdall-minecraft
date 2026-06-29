package com.heimdall.whitelist.core;

/**
 * Metadata about the latest published plugin release, as reported by the
 * Heimdall bot API (which sources it from GitHub Releases).
 */
public class PluginReleaseInfo {

  private final String version;
  private final String downloadUrl;
  private final String releaseNotes;
  private final String htmlUrl;
  private final String publishedAt;

  public PluginReleaseInfo(String version, String downloadUrl, String releaseNotes,
      String htmlUrl, String publishedAt) {
    this.version = version;
    this.downloadUrl = downloadUrl;
    this.releaseNotes = releaseNotes;
    this.htmlUrl = htmlUrl;
    this.publishedAt = publishedAt;
  }

  /** The latest version string, e.g. {@code "2.1.0"} (may be prefixed with "v"). */
  public String getVersion() {
    return version;
  }

  /** Direct download URL for the release JAR asset, or null if none was published. */
  public String getDownloadUrl() {
    return downloadUrl;
  }

  /** Human-readable release notes / changelog body (may be empty). */
  public String getReleaseNotes() {
    return releaseNotes;
  }

  /** URL of the release page on GitHub (may be null). */
  public String getHtmlUrl() {
    return htmlUrl;
  }

  /** ISO-8601 publish timestamp (may be null). */
  public String getPublishedAt() {
    return publishedAt;
  }
}
