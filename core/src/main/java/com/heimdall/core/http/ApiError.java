package com.heimdall.core.http;

/**
 * The bot refused a request, and said why.
 *
 * <p>Distinct from a transport failure (which surfaces as {@link java.io.UncheckedIOException}):
 * this means we reached the bot and it answered. The distinction is what lets the retry loop know
 * that hammering a {@code 400 MISSING_FIELDS} three times will not help, and lets a caller tell
 * "the bot says no" apart from "the bot is unreachable" when deciding whether to fail open.
 *
 * <p>The bot's error envelope is {@code {success: false, error: {code, message}}} — except on the
 * HMAC middleware's rejection path, which answers a bare {@code {"error": "Unauthorized"}} with no
 * envelope at all. Both shapes land here; see {@code Envelopes.errorFor}.
 */
public final class ApiError extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int httpStatus;
    private final String code;

    ApiError(int httpStatus, String code, String message) {
        super(code + ": " + message + " (HTTP " + httpStatus + ")");
        this.httpStatus = httpStatus;
        this.code = code;
    }

    /** The HTTP status the bot answered with. */
    public int httpStatus() {
        return httpStatus;
    }

    /** The bot's error code, e.g. {@code NOT_CONFIGURED}, or {@code UNKNOWN} when it sent none. */
    public String code() {
        return code;
    }

    /**
     * Whether trying again could plausibly succeed.
     *
     * <p>5xx is the server having a bad moment; 408 and 429 are explicit invitations to come back.
     * Everything else in the 4xx range is a statement about the request itself, and v2 retried
     * those too — three signed round trips to be told the same thing about a malformed body.
     */
    public boolean isRetryable() {
        return httpStatus >= 500 || httpStatus == 408 || httpStatus == 429;
    }
}
