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

    private PluginRelease(Builder builder) {
        this.version = builder.version;
        this.downloadUrl = builder.downloadUrl;
        this.releaseNotes = builder.releaseNotes == null ? "" : builder.releaseNotes;
        this.htmlUrl = builder.htmlUrl;
        this.publishedAt = builder.publishedAt;
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
                && Objects.equals(publishedAt, that.publishedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(version, downloadUrl, releaseNotes, htmlUrl, publishedAt);
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

        public PluginRelease build() {
            return new PluginRelease(this);
        }
    }
}
