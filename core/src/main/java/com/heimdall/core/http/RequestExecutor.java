package com.heimdall.core.http;

import com.heimdall.core.BuildConstants;
import com.heimdall.core.log.HeimdallLogger;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * The one place an HTTP request actually happens.
 *
 * <p>v2 had four copy-pasted request methods of roughly ninety lines each — {@code makeRequest},
 * {@code makeRequestForLinkCode}, {@code makeGetRequest}, {@code makeRequestGeneric} — differing in
 * the method verb, whether a body was written, and which parser ran at the end. Three of them
 * carried their own retry loop and their own error-reading block, and the four drifted: only two
 * logged the attempt number, one silently swallowed a failure to read the error body, and the
 * generic one accepted any 2xx while the others insisted on exactly 200.
 *
 * <p>Here there is one loop and one connection. Everything a caller can vary is on {@link HttpCall}.
 *
 * <p><strong>Blocking.</strong> {@link #execute} blocks the calling thread, including the retry
 * sleeps. {@link ApiClient} only ever calls it on its supplied executor.
 */
final class RequestExecutor {

    /** Announced to the bot so a fleet's plugin versions are visible in its logs. */
    static final String USER_AGENT = "Heimdall/" + BuildConstants.VERSION;

    private final HeimdallLogger logger;

    RequestExecutor(HeimdallLogger logger) {
        this.logger = logger;
    }

    /**
     * Signs, sends and retries one call until it succeeds or the attempts run out.
     *
     * @throws ApiError if the bot answered with a failure it will keep answering with
     * @throws UncheckedIOException if every attempt failed at the transport level
     */
    RawResponse execute(ApiSettings settings, HttpCall call) {
        int attempts = settings.retries();
        RuntimeException failure = null;

        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                RawResponse response = attemptOnce(settings, call);
                if (response.isSuccess()) {
                    return response;
                }
                ApiError error = Envelopes.errorFor(response);
                if (!error.isRetryable()) {
                    // A 400 will still be a 400 in a second's time. v2 retried these anyway.
                    throw error;
                }
                failure = error;
            } catch (IOException e) {
                failure = new UncheckedIOException(call + " failed: " + e.getMessage(), e);
            }

            if (attempt < attempts) {
                logger.warn(call + " failed (attempt " + attempt + "/" + attempts + "): "
                        + failure.getMessage() + " — retrying in " + settings.retryDelayMs() + "ms");
                sleep(settings.retryDelayMs());
            }
        }

        logger.severe("All " + attempts + " attempts failed for " + call);
        throw failure;
    }

    private RawResponse attemptOnce(ApiSettings settings, HttpCall call) throws IOException {
        URL url = new URL(settings.baseUrl() + call.pathWithQuery());
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();

        connection.setRequestMethod(call.method());
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setConnectTimeout(call.timeoutMs());
        connection.setReadTimeout(call.timeoutMs());
        // The JDK's response cache is a JVM-wide, application-installed thing; nothing here should
        // be served from it, least of all a whitelist decision.
        connection.setUseCaches(false);
        connection.setInstanceFollowRedirects(false);

        for (Map.Entry<String, String> header : call.headers().entrySet()) {
            connection.setRequestProperty(header.getKey(), header.getValue());
        }

        applySignature(settings, call, connection);

        if (call.hasBody()) {
            byte[] payload = call.body().getBytes(StandardCharsets.UTF_8);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(payload.length);
            OutputStream out = connection.getOutputStream();
            try {
                out.write(payload);
            } finally {
                out.close();
            }
        }

        int status = connection.getResponseCode();
        String etag = connection.getHeaderField("ETag");
        // A 304 carries no body, and asking for one on some JDKs blocks until the read timeout.
        String body = status == HttpURLConnection.HTTP_NOT_MODIFIED ? "" : readBody(connection, status);
        return new RawResponse(status, body, etag);
    }

    private void applySignature(ApiSettings settings, HttpCall call, HttpURLConnection connection) {
        if (settings.apiKey().isEmpty()) {
            // Fail loudly-ish rather than silently sending an unsigned request the bot will 401:
            // "Unauthorized" with no local explanation is the hardest version of this to diagnose.
            logger.warn("No API key configured — " + call + " will be rejected as unauthorized");
            return;
        }
        Signature signature = new HmacSigner(settings.apiKey())
                .forHttp(call.method(), call.pathWithQuery(), call.signedBody());
        connection.setRequestProperty(Signature.HEADER_SIGNATURE, signature.signature());
        connection.setRequestProperty(Signature.HEADER_TIMESTAMP, signature.timestamp());
    }

    /**
     * Reads the response body from whichever stream the status implies.
     *
     * <p>Read in full even on failure: leaving an error stream unread returns the connection to the
     * keep-alive pool in a state the JDK will not reuse, so every failed request costs a new socket.
     */
    private static String readBody(HttpURLConnection connection, int status) throws IOException {
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (stream == null) {
            return "";
        }
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = stream.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            stream.close();
        }
    }

    private static void sleep(int millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UncheckedIOException(new IOException("interrupted while waiting to retry", e));
        }
    }
}
