package com.heimdall.core.http.model;

/**
 * The latest published plugin release, as reported by {@code GET /plugin/latest} (which the bot
 * sources from GitHub Releases and caches).
 */
public final class PluginRelease {

    private final String version;
    private final String downloadUrl;
    private final String releaseNotes;
    private final String htmlUrl;
    private final String publishedAt;

    public PluginRelease(
            String version, String downloadUrl, String releaseNotes, String htmlUrl, String publishedAt) {
        this.version = version;
        this.downloadUrl = downloadUrl;
        this.releaseNotes = releaseNotes == null ? "" : releaseNotes;
        this.htmlUrl = htmlUrl;
        this.publishedAt = publishedAt;
    }

    /** The version string, e.g. {@code v3.0.0}. May carry a leading {@code v}. */
    public String version() {
        return version;
    }

    /** Direct download URL for the jar asset, or {@code null} if the release published none. */
    public String downloadUrl() {
        return downloadUrl;
    }

    /** Release notes; empty rather than {@code null}. */
    public String releaseNotes() {
        return releaseNotes;
    }

    /** The release page URL, or {@code null}. */
    public String htmlUrl() {
        return htmlUrl;
    }

    /** ISO-8601 publish timestamp, or {@code null}. */
    public String publishedAt() {
        return publishedAt;
    }

    @Override
    public String toString() {
        return "PluginRelease{version='" + version + "'}";
    }
}
