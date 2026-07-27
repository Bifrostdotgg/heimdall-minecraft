package com.heimdall.core.http;

/** An HTTP response before anything has been made of it: status, body text, and the ETag. */
final class RawResponse {

    private final int status;
    private final String body;
    private final String etag;

    RawResponse(int status, String body, String etag) {
        this.status = status;
        this.body = body == null ? "" : body;
        this.etag = etag;
    }

    int status() {
        return status;
    }

    /** The response body, never {@code null}; empty for a 304 or a bodyless answer. */
    String body() {
        return body;
    }

    /** The {@code ETag} header verbatim, quotes and all, or {@code null}. */
    String etag() {
        return etag;
    }

    /** Whether this is an answer to act on rather than an error. 304 counts. */
    boolean isSuccess() {
        return (status >= 200 && status < 300) || status == 304;
    }

    @Override
    public String toString() {
        return "HTTP " + status + " (" + body.length() + " bytes)";
    }
}
