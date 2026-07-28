package com.heimdall.core.update;

import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * What {@link UpdateDownloader} is allowed to fetch, as a value rather than as five constants
 * buried in the method that enforces them.
 *
 * <p>v2 held the host allowlist, the HTTPS requirement, the 50 MB ceiling and both timeouts as
 * {@code private static final} fields on {@code UpdateChecker}, checked inline in
 * {@code downloadUpdate}. That worked, and it was completely untestable: proving the allowlist
 * actually refuses a host meant either reaching GitHub for real or not proving it. The v2 suite did
 * not prove it. This is the same rules, extracted so they can be pointed at a loopback server in a
 * test — and so that the production values are a named thing an operator can be told about.
 *
 * <h2>Why the rules are what they are</h2>
 *
 * <p>The bot supplies the download URL, and the bot is remote. That makes {@code /hd update} a
 * write-arbitrary-jar-to-disk primitive driven by a value from the network, so the URL is treated
 * as untrusted input even though the bot is a trusted peer: a compromised or misconfigured bot must
 * not be able to point a fleet of servers at an attacker's jar, and — because the Velocity strategy
 * replaces the running jar in place — must not be able to point one at {@code 127.0.0.1} or a
 * link-local metadata endpoint either. Hence the host allowlist. HTTPS is required because an
 * allowlisted host reached over plain HTTP is an allowlisted host an on-path attacker can answer
 * for. The byte ceiling bounds a body with no honest end, which no {@code Content-Length} check
 * would catch because {@code Content-Length} is also supplied by the sender.
 *
 * <h2>{@link #github()} is the only policy production ever uses</h2>
 *
 * <p>{@link #builder()} exists for tests and its javadoc says so. The pair of assertions in
 * {@code DownloadPolicyGithubTest} — that {@link #github()} refuses {@code 127.0.0.1} and refuses
 * {@code http://} — are what stop a loosened test policy quietly becoming the shipped default.
 *
 * <p><strong>Immutable and thread-safe.</strong> Holds no I/O resources and owns nothing;
 * {@link #github()} is a shared constant.
 */
public final class DownloadPolicy {

    /** v2's allowlist, unchanged: releases and their assets both live under these two. */
    public static final List<String> GITHUB_HOSTS =
            Collections.unmodifiableList(hosts("github.com", "githubusercontent.com"));

    /** v2's ceiling: 50 MB. The shipped jar is under 5. */
    public static final long MAX_DOWNLOAD_BYTES = 50L * 1024 * 1024;

    /** v2's connect timeout. */
    public static final int CONNECT_TIMEOUT_MS = 10_000;

    /** v2's read timeout. Generous: this is a multi-megabyte transfer, not an API call. */
    public static final int READ_TIMEOUT_MS = 60_000;

    private static final DownloadPolicy GITHUB = new DownloadPolicy(new Builder());

    private final List<String> allowedHosts;
    private final boolean requireHttps;
    private final long maxBytes;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    private DownloadPolicy(Builder builder) {
        this.allowedHosts = Collections.unmodifiableList(new ArrayList<String>(builder.allowedHosts));
        this.requireHttps = builder.requireHttps;
        this.maxBytes = Math.max(1L, builder.maxBytes);
        this.connectTimeoutMs = Math.max(0, builder.connectTimeoutMs);
        this.readTimeoutMs = Math.max(0, builder.readTimeoutMs);
    }

    /**
     * The production policy: GitHub only, HTTPS only, 50 MB, v2's timeouts.
     *
     * <p>The default for every real {@link UpdateDownloader}.
     */
    public static DownloadPolicy github() {
        return GITHUB;
    }

    /**
     * A writer pre-populated with {@link #github()}'s values.
     *
     * <p><strong>For tests.</strong> Production code must use {@link #github()}. This exists so a
     * test can point the downloader at a loopback server over plain HTTP with a small ceiling, and
     * every one of its setters therefore weakens something. It starts from the production values
     * rather than from permissive ones so that a test which forgets to loosen a rule still gets the
     * safe behaviour instead of silently exercising a policy nobody ships.
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Whether both the host and the scheme of {@code url} are acceptable. */
    public boolean allows(URL url) {
        return url != null && allowsHost(url.getHost()) && allowsScheme(url.getProtocol());
    }

    /**
     * Whether {@code host} is on the allowlist.
     *
     * <p>v2's matching rule, unchanged: an exact match, or a subdomain — the host must equal an
     * allowed entry or end with {@code "." + entry}. The dot is load-bearing. Matching on a bare
     * {@code endsWith("github.com")} would also accept {@code evilgithub.com}, which is the classic
     * way an allowlist turns into a suggestion.
     */
    public boolean allowsHost(String host) {
        if (host == null) {
            return false;
        }
        String lower = host.toLowerCase(Locale.ROOT);
        for (String allowed : allowedHosts) {
            if (lower.equals(allowed) || lower.endsWith("." + allowed)) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code scheme} is acceptable. Only {@code https} is, unless a test says otherwise. */
    public boolean allowsScheme(String scheme) {
        if (!requireHttps) {
            return "https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme);
        }
        return "https".equalsIgnoreCase(scheme);
    }

    /** The allowlist, lowercased and unmodifiable. */
    public List<String> allowedHosts() {
        return allowedHosts;
    }

    /** Whether plain HTTP is refused. True for {@link #github()}. */
    public boolean requireHttps() {
        return requireHttps;
    }

    /** The hard ceiling on a downloaded body, in bytes. */
    public long maxBytes() {
        return maxBytes;
    }

    /** Connect timeout for the download connection. */
    public int connectTimeoutMs() {
        return connectTimeoutMs;
    }

    /** Read timeout for the download connection. */
    public int readTimeoutMs() {
        return readTimeoutMs;
    }

    @Override
    public String toString() {
        return "DownloadPolicy{hosts=" + allowedHosts + ", https=" + requireHttps
                + ", maxBytes=" + maxBytes + "}";
    }

    private static List<String> hosts(String... values) {
        List<String> out = new ArrayList<String>(values.length);
        for (String value : values) {
            out.add(value.toLowerCase(Locale.ROOT));
        }
        return out;
    }

    /** Mutable writer. See {@link DownloadPolicy#builder()} — every setter here loosens a rule. */
    public static final class Builder {

        private List<String> allowedHosts = new ArrayList<String>(GITHUB_HOSTS);
        private boolean requireHttps = true;
        private long maxBytes = MAX_DOWNLOAD_BYTES;
        private int connectTimeoutMs = CONNECT_TIMEOUT_MS;
        private int readTimeoutMs = READ_TIMEOUT_MS;

        private Builder() {
        }

        /** Replaces the allowlist outright. Lowercased on the way in. */
        public Builder allowedHosts(String... values) {
            this.allowedHosts = hosts(values);
            return this;
        }

        /** {@code false} permits plain HTTP as well as HTTPS. */
        public Builder requireHttps(boolean value) {
            this.requireHttps = value;
            return this;
        }

        /** Clamped to at least one byte, so a zero cannot make every download fail immediately. */
        public Builder maxBytes(long value) {
            this.maxBytes = value;
            return this;
        }

        public Builder connectTimeoutMs(int value) {
            this.connectTimeoutMs = value;
            return this;
        }

        public Builder readTimeoutMs(int value) {
            this.readTimeoutMs = value;
            return this;
        }

        public DownloadPolicy build() {
            return new DownloadPolicy(this);
        }
    }
}
