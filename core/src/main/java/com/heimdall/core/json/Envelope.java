package com.heimdall.core.json;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.util.UUID;

/**
 * One frame on the tunnel: {@code {id, type, payload}}, in both directions.
 *
 * <p>Correlation is by <strong>echoed id</strong>. A request carries a fresh id; the reply carries
 * the same one. Anything arriving with an id nobody is waiting on is unsolicited.
 *
 * <p>Lives beside {@link Payload} rather than in {@code com.heimdall.core.tunnel} for one concrete
 * reason: {@code Payload}'s Gson bridge is package-private, so putting the codec here means
 * building a frame around a payload that was just constructed does not have to serialise it to a
 * string and parse it back. Everything outside this package sees only {@code Envelope} and
 * {@code Payload}, neither of which names a Gson type.
 *
 * <p>Immutable and thread-safe.
 */
public final class Envelope {

    private final String id;
    private final String type;
    private final Payload payload;

    private Envelope(String id, String type, Payload payload) {
        this.id = id;
        this.type = type;
        this.payload = payload == null ? Payload.empty() : payload;
    }

    /** A frame with the given id — used for replies, which echo the request's id. */
    public static Envelope of(String id, String type, Payload payload) {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("id is required");
        }
        if (type == null || type.isEmpty()) {
            throw new IllegalArgumentException("type is required");
        }
        return new Envelope(id, type, payload);
    }

    /** A frame with a freshly generated id — used for requests and fire-and-forget sends. */
    public static Envelope fresh(String type, Payload payload) {
        return of(newId(), type, payload);
    }

    /**
     * A fresh correlation id.
     *
     * <p>Eight hex characters from a random UUID, exactly as v2 minted them. Short because these
     * appear in every debug line on both sides, and unique enough: the id only has to be
     * distinguishable among the handful of requests outstanding on one socket at one moment, not
     * globally.
     */
    public static String newId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Parses an inbound frame.
     *
     * <p>The bot's own guard is {@code if (!msg.type || !msg.id)} — a JavaScript truthiness check,
     * so an empty string is rejected as firmly as a missing key. Matching that exactly matters:
     * accepting {@code {"id":""}} would register a correlation against an id no reply can
     * meaningfully carry.
     *
     * @return the parsed frame, or {@code null} if the text is not a JSON object or is missing a
     *     usable {@code id} or {@code type}. Returning null rather than throwing keeps a garbage
     *     frame from unwinding the socket read loop.
     */
    public static Envelope parse(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        JsonObject object;
        try {
            JsonElement parsed = JsonParser.parseString(text);
            if (parsed == null || !parsed.isJsonObject()) {
                return null;
            }
            object = parsed.getAsJsonObject();
        } catch (JsonParseException e) {
            return null;
        }
        String id = truthyString(object, "id");
        String type = truthyString(object, "type");
        if (id == null || type == null) {
            return null;
        }
        JsonElement payload = object.get("payload");
        Payload body = payload != null && payload.isJsonObject()
                ? Payload.wrap(payload.getAsJsonObject())
                : Payload.empty();
        return new Envelope(id, type, body);
    }

    /** The correlation id. */
    public String id() {
        return id;
    }

    /** The message type, e.g. {@code ping}, {@code role_sync}, {@code config.push}. */
    public String type() {
        return type;
    }

    /** The body. Never {@code null}; {@link Payload#empty()} when the frame carried none. */
    public Payload payload() {
        return payload;
    }

    /** This frame as compact JSON, ready to write to the socket. */
    public String toJson() {
        JsonObject object = new JsonObject();
        object.addProperty("id", id);
        object.addProperty("type", type);
        // Copied, not lent. Payload.empty() is a shared singleton, and handing its backing
        // object to a container someone else owns is the one way it could ever be mutated.
        object.add("payload", payload.json().deepCopy());
        return object.toString();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Envelope)) {
            return false;
        }
        Envelope that = (Envelope) other;
        return id.equals(that.id) && type.equals(that.type) && payload.equals(that.payload);
    }

    @Override
    public int hashCode() {
        int result = id.hashCode();
        result = 31 * result + type.hashCode();
        result = 31 * result + payload.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "Envelope{id='" + id + "', type='" + type + "', payload=" + payload + "}";
    }

    /** A field's string value, or null if JavaScript would consider it falsy. */
    private static String truthyString(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive()) {
            return null;
        }
        String text = value.getAsString();
        if (text.isEmpty() || "0".equals(text) || "false".equals(text)) {
            return null;
        }
        return text;
    }
}
