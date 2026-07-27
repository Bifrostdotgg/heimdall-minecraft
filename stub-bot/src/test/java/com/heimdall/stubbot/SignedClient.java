package com.heimdall.stubbot;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * A minimal signing HTTP client for the tests — the plugin's half of the contract.
 *
 * <p>Signs exactly as {@code ApiClient} does: {@code X-Signature} + {@code X-Timestamp} over the
 * path <em>including</em> the query string.
 */
final class SignedClient {

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final String baseUrl;
    private final String secret;

    SignedClient(String baseUrl, String secret) {
        this.baseUrl = baseUrl;
        this.secret = secret;
    }

    HttpResponse<String> get(String path) throws Exception {
        return get(path, null);
    }

    HttpResponse<String> get(String path, String ifNoneMatch) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(10))
                .GET();
        sign(request, "GET", path, "");
        if (ifNoneMatch != null) {
            request.header("If-None-Match", ifNoneMatch);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        sign(request, "POST", path, body);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Posts with a signature computed over a different path — the "tampered request" case. */
    HttpResponse<String> postWithBadSignature(String path, String body) throws Exception {
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000L);
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("X-Signature", Hmac.sign(secret, timestamp, "POST", path + "-tampered", body))
                .header("X-Timestamp", timestamp)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** Posts with no signing headers at all. */
    HttpResponse<String> postUnsigned(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** Posts with a signature whose timestamp is well outside the replay window. */
    HttpResponse<String> postWithStaleTimestamp(String path, String body) throws Exception {
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000L - 3600L);
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("X-Signature", Hmac.sign(secret, timestamp, "POST", path, body))
                .header("X-Timestamp", timestamp)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private void sign(HttpRequest.Builder request, String method, String path, String body) {
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000L);
        request.header("X-Signature", Hmac.sign(secret, timestamp, method, path, body));
        request.header("X-Timestamp", timestamp);
    }

    /** The {@code data} object out of a success envelope, failing loudly if the envelope is wrong. */
    static JsonObject data(HttpResponse<String> response) {
        JsonObject envelope = JsonParser.parseString(response.body()).getAsJsonObject();
        if (!envelope.has("success") || !envelope.get("success").getAsBoolean()) {
            throw new AssertionError("expected a success envelope but got: " + response.body());
        }
        return envelope.getAsJsonObject("data");
    }

    static JsonObject envelope(HttpResponse<String> response) {
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }
}
