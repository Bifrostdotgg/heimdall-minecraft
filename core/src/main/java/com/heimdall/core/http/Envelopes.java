package com.heimdall.core.http;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/**
 * Unwrapping the bot's response envelope, and reading errors out of it.
 *
 * <p>The documented shape is {@code {success: true, data: …}}, but not every response has ever had
 * it — v2 tolerated a bare body at every call site, so that tolerance is kept here in one place
 * rather than re-derived six times.
 *
 * <p>The failure shapes are two, and the second is a genuine inconsistency in the bot rather than
 * something to tidy up: the standard envelope is {@code {success: false, error: {code, message}}},
 * but the HMAC middleware answers a failed guild-route signature with a bare {@code
 * {"error": "Unauthorized"}} and no envelope at all. A client that assumes {@code error.code}
 * exists on every failure breaks against the real bot on the one response it most needs to
 * understand.
 */
final class Envelopes {

    private Envelopes() {
    }

    /**
     * The payload of a successful response.
     *
     * @throws ApiError if the body carries {@code success: false}, whatever the HTTP status said
     * @throws JsonParseException if the body is not JSON
     */
    static JsonElement unwrap(int httpStatus, String body) {
        JsonElement root = parse(body);
        if (!root.isJsonObject()) {
            return root;
        }
        JsonObject object = root.getAsJsonObject();
        if (object.has("success") && object.get("success").isJsonPrimitive()
                && !object.get("success").getAsBoolean()) {
            // A 200 with success:false should not exist, but reading the body rather than the
            // status is what makes that a loud failure instead of a null-shaped one.
            throw errorFrom(httpStatus, object);
        }
        if (object.has("data") && !object.get("data").isJsonNull()) {
            return object.get("data");
        }
        return object;
    }

    /** The payload as an object; an empty object when the response carried something else. */
    static JsonObject unwrapObject(int httpStatus, String body) {
        JsonElement data = unwrap(httpStatus, body);
        return data.isJsonObject() ? data.getAsJsonObject() : new JsonObject();
    }

    /** The payload as an array; an empty array when the response carried something else. */
    static JsonArray unwrapArray(int httpStatus, String body) {
        JsonElement data = unwrap(httpStatus, body);
        return data.isJsonArray() ? data.getAsJsonArray() : new JsonArray();
    }

    /**
     * Turns a non-success response into an {@link ApiError}, whichever failure shape it used.
     *
     * <p>Never throws on a malformed body: a proxy's HTML error page is still a failure worth
     * reporting, and losing it to a parse exception would replace a useful status with a confusing
     * one.
     */
    static ApiError errorFor(RawResponse response) {
        try {
            JsonElement root = parse(response.body());
            if (root.isJsonObject()) {
                return errorFrom(response.status(), root.getAsJsonObject());
            }
        } catch (JsonParseException e) {
            // Falls through to the status-only error below.
        }
        return new ApiError(response.status(), "HTTP_" + response.status(), snippet(response.body()));
    }

    private static ApiError errorFrom(int httpStatus, JsonObject object) {
        JsonElement error = object.get("error");
        if (error == null || error.isJsonNull()) {
            return new ApiError(httpStatus, "HTTP_" + httpStatus, "Request failed");
        }
        if (error.isJsonObject()) {
            JsonObject details = error.getAsJsonObject();
            return new ApiError(httpStatus, string(details, "code", "UNKNOWN"),
                    string(details, "message", "Unknown error"));
        }
        // The bare {"error": "Unauthorized"} shape.
        return new ApiError(httpStatus, "UNAUTHORIZED", error.getAsString());
    }

    private static JsonElement parse(String body) {
        JsonElement parsed = JsonParser.parseString(body == null ? "" : body);
        if (parsed == null || parsed.isJsonNull()) {
            throw new JsonParseException("empty response body");
        }
        return parsed;
    }

    private static String string(JsonObject object, String key, String fallback) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? fallback : value.getAsString();
    }

    /** Keeps an unexpected body out of the log at full length while still saying what arrived. */
    private static String snippet(String body) {
        String trimmed = body == null ? "" : body.trim();
        if (trimmed.isEmpty()) {
            return "no response body";
        }
        return trimmed.length() <= 200 ? trimmed : trimmed.substring(0, 200) + "…";
    }
}
