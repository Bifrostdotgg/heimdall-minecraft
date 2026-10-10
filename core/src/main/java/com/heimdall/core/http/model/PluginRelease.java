package com.heimdall.core.http.model;

import java.util.Objects;

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
    private final String sha256;

    private PluginRelease(Builder builder) {
        this.version = builder.version;
        this.downloadUrl = builder.downloadUrl;
        this.releaseNotes = builder.releaseNotes == null ? "" : builder.releaseNotes;
        this.htmlUrl = builder.htmlUrl;
        this.publishedAt = builder.publishedAt;
        this.sha256 = builder.sha256 == null || builder.sha256.trim().isEmpty()
                ? null : builder.sha256.trim();
    }

    public static Builder builder() {
        return new Builder();
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

    /**
     * The jar asset's SHA-256 as the bot reported it from GitHub's asset digest: 64 lowercase hex
     * characters, or {@code null} when the bot could not say.
     *
     * <p>Kept exactly as received. Whether it is well-formed is the downloader's question, and a
     * malformed one fails the download rather than being quietly normalised. {@code null} is not a
     * failure: the release still installs for the next restart, but it is never swapped in live,
     * because a live swap runs downloaded code at once and nothing could vouch for it (D87).
     */
    public String sha256() {
        return sha256;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PluginRelease)) {
            return false;
        }
        PluginRelease that = (PluginRelease) other;
        return Objects.equals(version, that.version)
                && Objects.equals(downloadUrl, that.downloadUrl)
                && releaseNotes.equals(that.releaseNotes)
                && Objects.equals(htmlUrl, that.htmlUrl)
                && Objects.equals(publishedAt, that.publishedAt)
                && Objects.equals(sha256, that.sha256);
    }

    @Override
    public int hashCode() {
        return Objects.hash(version, downloadUrl, releaseNotes, htmlUrl, publishedAt, sha256);
    }

    @Override
    public String toString() {
        return "PluginRelease{version='" + version + "'}";
    }

    /** Mutable writer. Five nullable Strings is exactly the shape a positional call gets wrong. */
    public static final class Builder {

        private String version;
        private String downloadUrl;
        private String releaseNotes;
        private String htmlUrl;
        private String publishedAt;
        private String sha256;

        private Builder() {
        }

        public Builder version(String value) {
            this.version = value;
            return this;
        }

        public Builder downloadUrl(String value) {
            this.downloadUrl = value;
            return this;
        }

        public Builder releaseNotes(String value) {
            this.releaseNotes = value;
            return this;
        }

        public Builder htmlUrl(String value) {
            this.htmlUrl = value;
            return this;
        }

        public Builder publishedAt(String value) {
            this.publishedAt = value;
            return this;
        }

        public Builder sha256(String value) {
            this.sha256 = value;
            return this;
        }

        public PluginRelease build() {
            return new PluginRelease(this);
        }
    }
}
