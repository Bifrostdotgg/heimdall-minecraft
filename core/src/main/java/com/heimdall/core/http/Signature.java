package com.heimdall.core.http;

/**
 * A signature and the timestamp it was computed over — the two values that must travel together.
 *
 * <p>Returned as a pair rather than as two calls because the timestamp is an <em>input</em> to the
 * signature: computing it twice (once to sign, once to send) is a request that fails to verify
 * whenever the two calls land either side of a second boundary. v2 returned a two-element {@code
 * String[]} and indexed into it at four call sites.
 */
public final class Signature {

    /** The header the signature travels in. */
    public static final String HEADER_SIGNATURE = "X-Signature";

    /** The header the timestamp travels in. */
    public static final String HEADER_TIMESTAMP = "X-Timestamp";

    private final String signature;
    private final String timestamp;

    Signature(String signature, String timestamp) {
        this.signature = signature;
        this.timestamp = timestamp;
    }

    /** Lower-case hex HMAC-SHA256, for {@link #HEADER_SIGNATURE}. */
    public String signature() {
        return signature;
    }

    /** Unix seconds as a decimal string, for {@link #HEADER_TIMESTAMP}. */
    public String timestamp() {
        return timestamp;
    }

    @Override
    public String toString() {
        return "Signature{timestamp=" + timestamp + "}";
    }
}
