package com.heimdall.core.tunnel;

import com.heimdall.core.http.HmacSigner;
import com.heimdall.core.http.Signature;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

/**
 * Builds the WebSocket upgrade URL, and signs it the way the bot expects.
 *
 * <p>Small, and separate, because the one thing it gets right is easy to get wrong and invisible
 * when you do: <strong>the signature covers the path WITHOUT the query string, even though the
 * signature itself travels in that query string.</strong> The HTTP side signs the path WITH the
 * query. Both facts are the bot's real behaviour ({@code url.pathname} versus
 * {@code req.originalUrl}) and there is nothing in either log to say which one was used when a
 * connection is refused.
 *
 * <p>{@link HmacSigner#forWsHandshake} names the case, and this class is the only caller — so the
 * asymmetry is expressed once and tested once, rather than re-derived at whatever call site
 * happens to open a socket.
 */
final class TunnelUrls {

    private TunnelUrls() {
    }

    /**
     * The path the signature covers: {@code /ws/minecraft/{guildId}}, no query string.
     *
     * <p>The bot's route regex insists on a 17-20 digit guild id; anything else does not match the
     * route at all and the upgrade is refused before authentication is even attempted.
     */
    static String path(String guildId) {
        return "/ws/minecraft/" + guildId;
    }

    /**
     * The full upgrade URL.
     *
     * <p>{@code http}/{@code https} is rewritten to {@code ws}/{@code wss} by replacing the leading
     * scheme, which is what v2 did and what keeps one configured endpoint serving both transports —
     * the bot answers HTTP and the upgrade on the same port.
     */
    static String upgradeUrl(TunnelSettings settings, HmacSigner signer) {
        String path = path(settings.guildId());
        Signature signature = signer.forWsHandshake(path);
        return settings.endpoint().replaceFirst("^http", "ws") + path
                + "?serverId=" + encode(settings.serverId())
                + "&signature=" + encode(signature.signature())
                + "&timestamp=" + encode(signature.timestamp());
    }

    /**
     * The URL with its query string replaced by an ellipsis.
     *
     * <p>Every log line about connecting uses this. The query carries a valid HMAC signature for
     * this server, and a signature in a log file that gets pasted into a support ticket is a
     * credential in a support ticket — for the five minutes until the timestamp goes stale, which
     * is five minutes longer than it needs to be.
     */
    static String sanitize(String url) {
        int question = url.indexOf('?');
        return question > 0 ? url.substring(0, question) + "?…" : url;
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            // UTF-8 is required of every JVM, so this cannot happen; returning the raw value keeps
            // the failure to "a connection with an odd serverId is refused" rather than a crash.
            return value;
        }
    }
}
