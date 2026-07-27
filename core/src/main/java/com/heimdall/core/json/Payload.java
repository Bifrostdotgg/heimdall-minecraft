package com.heimdall.core.json;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * An immutable JSON object with a Gson-free public surface.
 *
 * <p><strong>Why this exists.</strong> Gson is declared {@code implementation} in core precisely so
 * the shaded-and-relocated libraries stay core's private business — a feature module that needs
 * JSON goes through a core utility rather than importing Gson itself. But phase 1b hands modules
 * two things that are unavoidably JSON-shaped: tunnel payloads, and their own remote-config
 * settings. Without a core-owned value type, either Gson leaks into every module's compile
 * classpath or every module invents its own map-of-Object parsing.
 *
 * <p>So this is the one type both of those speak. {@code RemoteConfig.moduleSettings(id)} returns a
 * {@code Payload}; so does {@code TunnelBus}'s subscription handler. That is deliberate reuse: "a
 * typed view over a JSON object, with defaults" is one problem, not two.
 *
 * <h2>Reading contract</h2>
 *
 * <p>Every accessor takes a fallback and never throws for a missing, null or wrong-typed value. A
 * malformed remote config must not be able to take a login path down, and a plugin that
 * {@code ClassCastException}s on a field the bot renamed is worse than one that used its default.
 *
 * <p>Two accessors deliberately break that pattern by returning {@code null}: {@link #optInt} and
 * {@link #optBool}. Absent and zero are different answers — the {@code queuePosition} case
 * (departure D1) is exactly this, and a fallback-shaped API cannot express it.
 *
 * <p><strong>Immutable and thread-safe.</strong> The backing object is never handed out and never
 * mutated after construction; {@link #toBuilder()} copies.
 */
public final class Payload {

    private static final Payload EMPTY = new Payload(new JsonObject());

    private final JsonObject json;

    private Payload(JsonObject json) {
        this.json = json;
    }

    /** A payload with no fields. */
    public static Payload empty() {
        return EMPTY;
    }

    /**
     * Parses a JSON object.
     *
     * @return the parsed payload, or {@link #empty()} if the text is not a JSON <em>object</em>
     *     (including {@code null}, an array, a bare primitive, and anything unparseable). Callers
     *     that need to tell "absent" from "empty" should check the source before parsing — nothing
     *     downstream of this class can act usefully on a malformed payload anyway, and throwing
     *     here would put a parse failure on the socket read thread.
     */
    public static Payload parse(String text) {
        if (text == null || text.isEmpty()) {
            return EMPTY;
        }
        try {
            JsonElement parsed = JsonParser.parseString(text);
            return parsed != null && parsed.isJsonObject() ? new Payload(parsed.getAsJsonObject()) : EMPTY;
        } catch (JsonParseException e) {
            return EMPTY;
        }
    }

    /** A fresh writer. */
    public static Builder builder() {
        return new Builder();
    }

    /** A writer pre-populated with this payload's fields. */
    public Builder toBuilder() {
        return new Builder().putAll(this);
    }

    // ── Reads ────────────────────────────────────────────────────────────────

    /** Whether the key is present at all, including when its value is JSON {@code null}. */
    public boolean has(String key) {
        return json.has(key);
    }

    /** Whether the key is present <em>and</em> its value is JSON {@code null}. */
    public boolean isExplicitNull(String key) {
        JsonElement value = json.get(key);
        return value != null && value.isJsonNull();
    }

    /** Whether this payload has no fields. */
    public boolean isEmpty() {
        return json.size() == 0;
    }

    /** The field names, in insertion order. */
    public Set<String> keys() {
        return Collections.unmodifiableSet(new LinkedHashSet<String>(json.keySet()));
    }

    /** A string field, or {@code fallback} when it is absent, null, or not a primitive. */
    public String string(String key, String fallback) {
        JsonElement value = primitive(key);
        return value == null ? fallback : value.getAsString();
    }

    /** An int field, or {@code fallback} when it is absent, null, or not numeric. */
    public int intValue(String key, int fallback) {
        Integer value = optInt(key);
        return value == null ? fallback : value.intValue();
    }

    /** A long field, or {@code fallback} when it is absent, null, or not numeric. */
    public long longValue(String key, long fallback) {
        JsonElement value = primitive(key);
        if (value == null) {
            return fallback;
        }
        try {
            return value.getAsLong();
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** A double field, or {@code fallback} when it is absent, null, or not numeric. */
    public double doubleValue(String key, double fallback) {
        JsonElement value = primitive(key);
        if (value == null) {
            return fallback;
        }
        try {
            return value.getAsDouble();
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** A boolean field, or {@code fallback} when it is absent, null, or not a primitive. */
    public boolean bool(String key, boolean fallback) {
        Boolean value = optBool(key);
        return value == null ? fallback : value.booleanValue();
    }

    /**
     * An int field, or {@code null} when it is absent, null, or not numeric.
     *
     * <p>The nullable form exists because absent and zero are different answers on this wire — see
     * the class javadoc.
     */
    public Integer optInt(String key) {
        JsonElement value = primitive(key);
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value.getAsInt());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** A boolean field, or {@code null} when it is absent, null, or not a primitive. */
    public Boolean optBool(String key) {
        JsonElement value = primitive(key);
        return value == null ? null : Boolean.valueOf(value.getAsBoolean());
    }

    /**
     * A string-array field.
     *
     * @return an unmodifiable list, empty when the key is absent or not an array. Non-primitive
     *     elements are skipped rather than failing the whole list: one bad entry in a group list
     *     should cost that entry, not the sync.
     */
    public List<String> strings(String key) {
        JsonElement value = json.get(key);
        if (value == null || !value.isJsonArray()) {
            return Collections.emptyList();
        }
        JsonArray array = value.getAsJsonArray();
        List<String> out = new ArrayList<String>(array.size());
        for (JsonElement element : array) {
            if (element != null && element.isJsonPrimitive()) {
                out.add(element.getAsString());
            }
        }
        return Collections.unmodifiableList(out);
    }

    /** A nested object field, or {@link #empty()} when it is absent or not an object. */
    public Payload child(String key) {
        JsonElement value = json.get(key);
        return value != null && value.isJsonObject() ? new Payload(value.getAsJsonObject()) : EMPTY;
    }

    /**
     * An object-array field.
     *
     * @return an unmodifiable list, empty when the key is absent or not an array; non-object
     *     elements are skipped.
     */
    public List<Payload> children(String key) {
        JsonElement value = json.get(key);
        if (value == null || !value.isJsonArray()) {
            return Collections.emptyList();
        }
        JsonArray array = value.getAsJsonArray();
        List<Payload> out = new ArrayList<Payload>(array.size());
        for (JsonElement element : array) {
            if (element != null && element.isJsonObject()) {
                out.add(new Payload(element.getAsJsonObject()));
            }
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * A copy with the named keys removed.
     *
     * <p>For the case where a payload is part envelope and part content — a remote-config module
     * entry is {@code {enabled, ...and the rest is settings}} — and the content has to be lifted out
     * without naming every field of it in advance.
     *
     * @return this payload unchanged if none of the keys were present, so the common case allocates
     *     nothing
     */
    public Payload without(String... keys) {
        if (keys == null || keys.length == 0) {
            return this;
        }
        boolean anyPresent = false;
        for (String key : keys) {
            if (json.has(key)) {
                anyPresent = true;
                break;
            }
        }
        if (!anyPresent) {
            return this;
        }
        JsonObject copy = json.deepCopy();
        for (String key : keys) {
            copy.remove(key);
        }
        return new Payload(copy);
    }

    /** This payload as compact JSON. */
    public String toJson() {
        return json.toString();
    }

    // ── Value semantics ──────────────────────────────────────────────────────

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Payload)) {
            return false;
        }
        return json.equals(((Payload) other).json);
    }

    /**
     * A hash that actually agrees with {@link #equals(Object)}.
     *
     * <p><strong>Gson's own does not, and the disagreement is invisible until it bites.</strong>
     * {@code JsonPrimitive.equals} compares numbers by value — a parsed {@code 1} equals a built
     * {@code 1} — but {@code JsonPrimitive.hashCode} branches on the concrete {@code Number}
     * subclass: an {@code Integer} hashes as a long, while the {@code LazilyParsedNumber} the parser
     * produces falls through to the double-bits branch. So {@code Payload.parse("{\"x\":1}")} and
     * {@code Payload.builder().put("x", 1).build()} are equal and hash differently, which is exactly
     * the contract violation that makes a {@code HashMap} lose entries.
     *
     * <p>It matters here because payloads are compared for change detection (departure D22): a
     * remote-config section that arrived over the wire is compared against one built locally, and
     * either could end up as a map key. So numbers are normalised to their double bits — the same
     * normalisation {@code equals} performs — and object members are summed so the hash stays
     * independent of key order, as {@code Map.equals} is.
     */
    @Override
    public int hashCode() {
        return hash(json);
    }

    private static int hash(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return 31;
        }
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            int result = 0;
            for (String key : object.keySet()) {
                result += 31 * key.hashCode() + hash(object.get(key));
            }
            return result;
        }
        if (element.isJsonArray()) {
            int result = 1;
            for (JsonElement child : element.getAsJsonArray()) {
                result = 31 * result + hash(child);
            }
            return result;
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isNumber()) {
            long bits = Double.doubleToLongBits(primitive.getAsDouble());
            return (int) (bits ^ (bits >>> 32));
        }
        if (primitive.isBoolean()) {
            return Boolean.valueOf(primitive.getAsBoolean()).hashCode();
        }
        return primitive.getAsString().hashCode();
    }

    /** The JSON text. Convenient in log lines and assertion failures alike. */
    @Override
    public String toString() {
        return toJson();
    }

    // ── Package-private bridge ───────────────────────────────────────────────

    /**
     * The backing object, for {@link Envelope} only.
     *
     * <p>Package-private on purpose: this is the seam that keeps Gson out of core's public API.
     * Everything outside this package goes through {@link #toJson()} / {@link #parse(String)}, and
     * {@code Envelope} lives here specifically so building a wire frame does not have to re-parse a
     * payload that was just constructed.
     *
     * <p>The returned object is the real one and must not be mutated.
     */
    JsonObject json() {
        return json;
    }

    /** Wraps an object as a payload without copying. The caller must not retain or mutate it. */
    static Payload wrap(JsonObject json) {
        return json == null ? EMPTY : new Payload(json);
    }

    private JsonElement primitive(String key) {
        JsonElement value = json.get(key);
        return value != null && value.isJsonPrimitive() ? value : null;
    }

    /**
     * The mutable writer.
     *
     * <p>Not thread-safe, and not meant to be: a builder is filled by one caller and then built.
     */
    public static final class Builder {

        private final JsonObject json = new JsonObject();

        private Builder() {
        }

        public Builder put(String key, String value) {
            json.addProperty(key, value);
            return this;
        }

        public Builder put(String key, int value) {
            json.addProperty(key, Integer.valueOf(value));
            return this;
        }

        public Builder put(String key, long value) {
            json.addProperty(key, Long.valueOf(value));
            return this;
        }

        public Builder put(String key, double value) {
            json.addProperty(key, Double.valueOf(value));
            return this;
        }

        public Builder put(String key, boolean value) {
            json.addProperty(key, Boolean.valueOf(value));
            return this;
        }

        /** Writes an explicit JSON {@code null} — which is not the same as omitting the key. */
        public Builder putNull(String key) {
            json.add(key, JsonNull.INSTANCE);
            return this;
        }

        /** Writes a nested object. A {@code null} payload writes an explicit JSON null. */
        public Builder put(String key, Payload value) {
            if (value == null) {
                return putNull(key);
            }
            json.add(key, value.json.deepCopy());
            return this;
        }

        /** Writes a string array. A {@code null} collection writes an empty array. */
        public Builder putStrings(String key, Iterable<String> values) {
            JsonArray array = new JsonArray();
            if (values != null) {
                for (String value : values) {
                    array.add(value);
                }
            }
            json.add(key, array);
            return this;
        }

        /** Writes an array of objects. A {@code null} collection writes an empty array. */
        public Builder putChildren(String key, Iterable<Payload> values) {
            JsonArray array = new JsonArray();
            if (values != null) {
                for (Payload value : values) {
                    array.add(value == null ? JsonNull.INSTANCE : value.json.deepCopy());
                }
            }
            json.add(key, array);
            return this;
        }

        /** Copies every field of {@code other} in, overwriting any key that collides. */
        public Builder putAll(Payload other) {
            if (other != null) {
                for (String key : other.json.keySet()) {
                    json.add(key, other.json.get(key).deepCopy());
                }
            }
            return this;
        }

        /**
         * The finished payload.
         *
         * <p>Deep-copies rather than wrapping the accumulator, so a builder that is kept and
         * written to again cannot mutate a payload someone is already holding. Payloads are small
         * and built a handful of times per connection; the copy is not worth avoiding.
         */
        public Payload build() {
            return new Payload(json.deepCopy());
        }
    }
}
