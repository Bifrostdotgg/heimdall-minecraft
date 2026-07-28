package com.heimdall.core.http;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Validates a bot endpoint before it is written to {@code bootstrap.yml} and trusted forever.
 *
 * <h2>Why this is a security boundary, not a convenience</h2>
 *
 * <p>The endpoint an operator passes to {@code /hd setup} becomes the HTTP <em>and</em> WebSocket bot
 * for the life of the install. Everything downstream trusts it completely: the bot chooses the
 * token, answers the login gate (so it can admit anyone), pushes the role-sync snapshots that grant
 * LuckPerms groups, and — through the offenses module dispatching the punishment command it is
 * handed — runs console commands of its choosing. A typo'd or hostile endpoint is therefore remote
 * code execution on that server, and a plain {@code http://} endpoint puts the token on the wire in
 * cleartext.
 *
 * <p>This is the same class of untrusted-input problem {@link com.heimdall.core.update.DownloadPolicy}
 * already guards for a jar download, and the rules here are the deliberate equivalent:
 *
 * <ul>
 *   <li><strong>HTTPS for a public host.</strong> An operator who fat-fingers a public hostname must
 *       not end up shipping their token to it in cleartext, and an on-path attacker must not be able
 *       to answer for it.
 *   <li><strong>HTTP is allowed for a loopback or private host.</strong> A self-hosted bot on a LAN,
 *       and the smoke harness's {@code http://stub-bot:8080}, are legitimate and have no certificate.
 *       "Private" is decided without a DNS lookup — see {@link #isLocalHost} — because resolving a
 *       hostname here would itself be a network call driven by operator input.
 *   <li><strong>No path, query, userinfo or fragment.</strong> The endpoint is a base URL the client
 *       concatenates onto; anything past the authority means somebody pasted a full URL, and a
 *       {@code user:pass@} authority is a credential-in-a-config smell that has no business here.
 * </ul>
 *
 * <p>Validation happens <strong>before the setup code is spent</strong>: rejecting a bad endpoint
 * costs nothing, whereas claiming first and then refusing to write the result burns a single-use
 * code for an endpoint that was never going to work.
 *
 * <p>Stateless and thread-safe.
 */
public final class BotEndpoint {

    /** A dotted-quad IPv4 literal, so it can be range-checked without a DNS lookup. */
    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");

    private BotEndpoint() {
    }

    /** The outcome of validating an endpoint: either a normalised URL, or a reason it was refused. */
    public static final class Result {

        private final String endpoint;
        private final String error;

        private Result(String endpoint, String error) {
            this.endpoint = endpoint;
            this.error = error;
        }

        /** Whether the endpoint may be written and trusted. */
        public boolean valid() {
            return error == null;
        }

        /** The endpoint to persist — scheme + authority, trailing slash removed. Only when {@link #valid()}. */
        public String endpoint() {
            return endpoint;
        }

        /** One operator-facing sentence naming what was wrong, or {@code null} when {@link #valid()}. */
        public String error() {
            return error;
        }
    }

    /**
     * Checks {@code raw} and normalises it.
     *
     * @param raw the endpoint as the operator typed it
     * @return a {@link Result} that is either valid with a normalised endpoint, or invalid with a
     *     reason — never throwing, because the reason is what the command prints
     */
    public static Result validate(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return invalid("no endpoint was given");
        }
        String trimmed = raw.trim();

        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException malformed) {
            return invalid("'" + trimmed + "' is not a valid URL");
        }
        if (!uri.isAbsolute() || uri.getScheme() == null) {
            return invalid("the endpoint must be a full URL including https://");
        }

        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"https".equals(scheme) && !"http".equals(scheme)) {
            return invalid("the endpoint must use http or https, not '" + scheme + "'");
        }
        if (uri.getHost() == null || uri.getHost().isEmpty()) {
            return invalid("the endpoint has no host — check for a typo");
        }
        if (uri.getUserInfo() != null) {
            return invalid("the endpoint must not contain a username or password");
        }
        // getPath() is "" for `https://host` and "/" for `https://host/`; both are the bare base URL
        // the client concatenates onto. Anything else means a full endpoint URL was pasted in.
        String path = uri.getPath();
        if (path != null && !path.isEmpty() && !"/".equals(path)) {
            return invalid("the endpoint must be a base URL with no path (got '" + path + "')");
        }
        if (uri.getQuery() != null) {
            return invalid("the endpoint must not contain a query string");
        }
        if (uri.getFragment() != null) {
            return invalid("the endpoint must not contain a '#' fragment");
        }

        if ("http".equals(scheme) && !isLocalHost(uri.getHost())) {
            return invalid("refusing a plain http:// endpoint for the public host '" + uri.getHost()
                    + "' — the token would travel in cleartext. Use https://, or a private/loopback "
                    + "host for a self-hosted bot.");
        }

        // Reassembled from the parsed parts rather than echoing the input, so a normalisation the
        // parser applied (an upper-case scheme, say) is what gets written. BootstrapConfig strips a
        // trailing slash too; doing it here keeps the persisted value and the validated value equal.
        String authority = uri.getHost() + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
        return new Result(scheme + "://" + authority, null);
    }

    /**
     * Whether {@code host} is a loopback, private or otherwise non-public address, decided from the
     * string alone with no name resolution.
     *
     * <p>An IP literal is range-checked. A hostname is treated as private when it is a single label
     * with no dot — a container or intranet name like {@code stub-bot} cannot be a public FQDN — or
     * carries an unmistakably local suffix. Everything else is assumed public and must use HTTPS,
     * which is the safe default to be wrong in: the cost of misjudging a genuinely-private host as
     * public is that its operator has to type {@code https://}, while the reverse would wave a
     * cleartext token onto the internet.
     */
    private static boolean isLocalHost(String host) {
        String lower = host.toLowerCase(Locale.ROOT);
        if (lower.startsWith("[") && lower.endsWith("]")) {
            // A bracketed IPv6 literal. ::1 is loopback; fc00::/7 (unique-local) and fe80::/10
            // (link-local) are private. Cheap prefix checks rather than parsing.
            String inner = lower.substring(1, lower.length() - 1);
            return inner.equals("::1")
                    || inner.startsWith("fc") || inner.startsWith("fd")
                    || inner.startsWith("fe8") || inner.startsWith("fe9")
                    || inner.startsWith("fea") || inner.startsWith("feb");
        }
        if (lower.equals("localhost") || lower.equals("localhost.")) {
            return true;
        }
        if (IPV4.matcher(lower).matches()) {
            return isPrivateIpv4(lower);
        }
        if (lower.indexOf('.') < 0) {
            // A single-label hostname: a container name, a Bonjour/NetBIOS name, an intranet short
            // name. None of these is routable on the public internet, so none can be MITM'd from it.
            return true;
        }
        return lower.endsWith(".local") || lower.endsWith(".internal")
                || lower.endsWith(".lan") || lower.endsWith(".home.arpa")
                || lower.endsWith(".localhost");
    }

    /** RFC 1918, loopback, link-local and this-host ranges, from the four octets. */
    private static boolean isPrivateIpv4(String ip) {
        String[] parts = ip.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        int a;
        int b;
        try {
            a = Integer.parseInt(parts[0]);
            b = Integer.parseInt(parts[1]);
        } catch (NumberFormatException notANumber) {
            return false;
        }
        if (a < 0 || a > 255 || b < 0 || b > 255) {
            return false;
        }
        if (a == 10 || a == 127 || a == 0) {
            return true;
        }
        if (a == 192 && b == 168) {
            return true;
        }
        if (a == 169 && b == 254) {
            return true;
        }
        return a == 172 && b >= 16 && b <= 31;
    }

    private static Result invalid(String reason) {
        return new Result(null, reason);
    }
}
