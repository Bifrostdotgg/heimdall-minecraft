package com.heimdall.core.http;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One request, described rather than performed.
 *
 * <p>Separating the description from the execution is what collapses v2's four near-identical
 * ~90-line request methods into a single path: they differed only in method, body, timeout and one
 * extra header, all of which are fields here.
 */
final class HttpCall {

    private final String method;
    private final String pathWithQuery;
    private final String body;
    private final int timeoutMs;
    private final Map<String, String> headers;

    private HttpCall(String method, String pathWithQuery, String body, int timeoutMs, Map<String, String> headers) {
        this.method = method;
        this.pathWithQuery = pathWithQuery;
        this.body = body;
        this.timeoutMs = timeoutMs;
        this.headers = headers.isEmpty()
                ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, String>(headers));
    }

    static HttpCall get(String pathWithQuery, int timeoutMs) {
        return new HttpCall("GET", pathWithQuery, null, timeoutMs, Collections.<String, String>emptyMap());
    }

    static HttpCall post(String pathWithQuery, String body, int timeoutMs) {
        return new HttpCall("POST", pathWithQuery, body, timeoutMs, Collections.<String, String>emptyMap());
    }

    /** A copy of this call carrying one more header. */
    HttpCall withHeader(String name, String value) {
        Map<String, String> merged = new LinkedHashMap<String, String>(headers);
        merged.put(name, value);
        return new HttpCall(method, pathWithQuery, body, timeoutMs, merged);
    }

    String method() {
        return method;
    }

    /** The path <em>including</em> any query string — what the HTTP signature covers. */
    String pathWithQuery() {
        return pathWithQuery;
    }

    /** The request body, or {@code null} for a bodyless request. */
    String body() {
        return body;
    }

    /** The body as it is signed: {@code ""} when there is none. */
    String signedBody() {
        return body == null ? "" : body;
    }

    boolean hasBody() {
        return body != null;
    }

    int timeoutMs() {
        return timeoutMs;
    }

    Map<String, String> headers() {
        return headers;
    }

    @Override
    public String toString() {
        return method + " " + pathWithQuery;
    }
}
