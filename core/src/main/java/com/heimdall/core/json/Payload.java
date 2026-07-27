package com.heimdall.core.json;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.math.BigDecimal;
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
 * (departure D1) is exactly this, and a fallback-shaped API cannot express it. Both are strict
 * about type for the same reason: an accessor that answered {@code false} for a number, or
 * truncated an out-of-range value into a plausible-looking int, would be giving a confident wrong
 * answer where the whole point is to distinguish "no answer" from one.
 *
 * <p>{@link #string} and {@link #doubleValue} are deliberately <em>coercive</em> in the other
 * direction — {@code string} renders a number or a boolean as text — because a bot that sends
 * {@code {"code": 135790}} where a string was expected should still produce a usable code rather
 * than a fallback.
 *
 * <h2>Numbers</h2>
 *
 * <p>Numeric values are compared and hashed through one canonical form ({@link #canonicalNumber}),
 * not through Gson's. Gson's own {@code JsonPrimitive.equals} is <strong>not transitive</strong>
 * across the parser and the builder: it only compares two numbers as integers when both are a
 * {@code Long}/{@code Integer}/{@code Short}/{@code Byte}/{@code BigInteger}, and the parser
 * produces neither of those — it produces a {@code LazilyParsedNumber}, which falls to the
 * {@code double} branch. So a parsed {@code 1234567890123456789} equals a built
 * {@code 1234567890123456789} and also equals a built {@code 1234567890123456788}, while those two
 * built values are not equal to each other. Anything holding a 64-bit id as a JSON number — a
 * Discord snowflake, most obviously — lands squarely in that gap, and near 1.2e18 a {@code double}
 * cannot tell apart ids minted seconds apart.
 *
 * <p>That matters here rather than being a curiosity, because {@code RemoteConfig} decides whether
 * to fire a module's change listeners by comparing settings for equality. A setting carrying a
 * snowflake would silently stop producing change notifications.
 *
 * <p>So both {@link #equals} and {@link #hashCode} go through the same normalisation, and neither
 * defers to Gson: values are compared as {@link BigDecimal}, which keeps 64-bit ids exact, treats
 * {@code 1} and {@code 1.0} as the same number, and folds {@code -0.0} into {@code 0.0} on both
 * sides. Two payloads that are {@code equals} therefore always hash alike, whatever built them.
 *
 * <p><strong>Immutable and thread-safe.</strong> The exact invariant, since two of the mechanisms
 * are worth naming: the backing object graph is never reachable from outside this package, and it
 * is never mutated after construction. Everything entering — {@link #parse}, {@link #wrap},
 * {@link Builder#build()} — copies or takes exclusive ownership at that boundary, so no caller
 * retains a handle on it. {@link #child} and {@link #children} alias a subtree rather than copying,
 * which is safe precisely because nothing outside can reach the tree they alias. A payload is
 * therefore safe to hand from the socket's reading thread to any number of handler executors.
 */
public final class Payload {

    private static final Payload EMPTY = new Payload(new JsonObject());

    private static final BigDecimal INT_MAX = BigDecimal.valueOf(Integer.MAX_VALUE);
    private static final BigDecimal INT_MIN = BigDecimal.valueOf(Integer.MIN_VALUE);
    private static final BigDecimal LONG_MAX = BigDecimal.valueOf(Long.MAX_VALUE);
    private static final BigDecimal LONG_MIN = BigDecimal.valueOf(Long.MIN_VALUE);

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

    /**
     * A string field, or {@code fallback} when it is absent, null, or not a primitive.
     *
     * <p><strong>Coercive:</strong> a number or a boolean is rendered as its text. That is
     * deliberate — a bot sending {@code {"code": 135790}} where a string was expected should still
     * produce a usable code — but it means this never distinguishes {@code "1"} from {@code 1}.
     */
    public String string(String key, String fallback) {
        JsonElement value = primitive(key);
        return value == null ? fallback : value.getAsString();
    }

    /**
     * An int field, or {@code fallback} when it is absent, null, not numeric, or out of range.
     *
     * <p>Out-of-range falls back rather than truncating — see {@link #optInt}.
     */
    public int intValue(String key, int fallback) {
        Integer value = optInt(key);
        return value == null ? fallback : value.intValue();
    }

    /**
     * A long field, or {@code fallback} when it is absent, null, not numeric, or outside the range
     * of a long.
     *
     * <p>Range-checked for the same reason as {@link #optInt}: a silent narrowing produces a number
     * that looks entirely reasonable. A fractional value is truncated toward zero.
     */
    public long longValue(String key, long fallback) {
        BigDecimal number = number(key);
        if (number == null || number.compareTo(LONG_MAX) > 0 || number.compareTo(LONG_MIN) < 0) {
            return fallback;
        }
        return number.longValue();
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

    /**
     * A boolean field, or {@code fallback} when it is absent, null, or <strong>not actually a
     * boolean</strong>.
     *
     * <p>Strict rather than coercive. Gson would happily answer {@code false} for the string
     * {@code "yes"} and for the number {@code 5}, which is a confident wrong answer about whether a
     * module is enabled — and "enabled" is the single most consequential boolean on this wire.
     */
    public boolean bool(String key, boolean fallback) {
        Boolean value = optBool(key);
        return value == null ? fallback : value.booleanValue();
    }

    /**
     * An int field, or {@code null} when it is absent, null, not numeric, or <strong>outside the
     * range of an int</strong>.
     *
     * <p>The nullable form exists because absent and zero are different answers on this wire — see
     * the class javadoc. Which is exactly why the range check matters: {@code getAsInt()} on a
     * parsed number is a narrowing cast that never complains, so {@code 5000000000} came back as
     * {@code 705032704} — a wrong answer indistinguishable from a right one. Departure D1's
     * nullable {@code queuePosition} rests on this method being trustworthy.
     *
     * <p>A value with a fractional part is truncated toward zero, as it always was.
     */
    public Integer optInt(String key) {
        BigDecimal number = number(key);
        if (number == null || number.compareTo(INT_MAX) > 0 || number.compareTo(INT_MIN) < 0) {
            return null;
        }
        return Integer.valueOf(number.intValue());
    }

    /**
     * A boolean field, or {@code null} when it is absent, null, or not a JSON boolean.
     *
     * <p>Type-strict: see {@link #bool}. Coercing anything non-boolean into {@code FALSE} would
     * defeat this method's only purpose, which is telling an absent value from a present one.
     */
    public Boolean optBool(String key) {
        JsonElement value = primitive(key);
        if (value == null || !value.getAsJsonPrimitive().isBoolean()) {
            return null;
        }
        return Boolean.valueOf(value.getAsBoolean());
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
        return equal(json, ((Payload) other).json);
    }

    /**
     * A hash that agrees with {@link #equals(Object)} — because both call the same normalisation.
     *
     * <p>Object members are summed rather than combined in order, so the hash is independent of key
     * order exactly as {@code Map.equals} is. Numbers go through {@link #canonicalNumber}, the same
     * function {@link #equal} uses, which is what makes the agreement structural rather than a
     * property two separate implementations happen to share today.
     */
    @Override
    public int hashCode() {
        return hash(json);
    }

    /**
     * Structural equality, with numbers compared by value.
     *
     * <p>Kinds are compared before contents, so the string {@code "1"} is never equal to the number
     * {@code 1}.
     */
    private static boolean equal(JsonElement left, JsonElement right) {
        if (left == null || left.isJsonNull() || right == null || right.isJsonNull()) {
            return (left == null || left.isJsonNull()) && (right == null || right.isJsonNull());
        }
        if (left.isJsonObject() && right.isJsonObject()) {
            JsonObject a = left.getAsJsonObject();
            JsonObject b = right.getAsJsonObject();
            if (a.size() != b.size()) {
                return false;
            }
            for (String key : a.keySet()) {
                if (!b.has(key) || !equal(a.get(key), b.get(key))) {
                    return false;
                }
            }
            return true;
        }
        if (left.isJsonArray() && right.isJsonArray()) {
            JsonArray a = left.getAsJsonArray();
            JsonArray b = right.getAsJsonArray();
            if (a.size() != b.size()) {
                return false;
            }
            for (int i = 0; i < a.size(); i++) {
                if (!equal(a.get(i), b.get(i))) {
                    return false;
                }
            }
            return true;
        }
        if (left.isJsonPrimitive() && right.isJsonPrimitive()) {
            return equalPrimitives(left.getAsJsonPrimitive(), right.getAsJsonPrimitive());
        }
        return false;
    }

    private static boolean equalPrimitives(JsonPrimitive left, JsonPrimitive right) {
        if (left.isNumber() != right.isNumber() || left.isBoolean() != right.isBoolean()) {
            return false;
        }
        if (left.isNumber()) {
            BigDecimal a = canonicalNumber(left);
            BigDecimal b = canonicalNumber(right);
            if (a == null || b == null) {
                // Not representable as a decimal — NaN or an infinity, which only Gson's lenient
                // mode can produce. Comparing the text is the only meaningful thing left.
                return left.getAsString().equals(right.getAsString());
            }
            return a.compareTo(b) == 0;
        }
        if (left.isBoolean()) {
            return left.getAsBoolean() == right.getAsBoolean();
        }
        return left.getAsString().equals(right.getAsString());
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
            BigDecimal canonical = canonicalNumber(primitive);
            return canonical == null ? primitive.getAsString().hashCode() : canonical.hashCode();
        }
        if (primitive.isBoolean()) {
            return Boolean.valueOf(primitive.getAsBoolean()).hashCode();
        }
        return primitive.getAsString().hashCode();
    }

    /**
     * <strong>The single normalisation.</strong> Both {@link #equal} and {@link #hash} call this,
     * and nothing else decides what a number means.
     *
     * <p>Two rules, each fixing a concrete bug:
     *
     * <ul>
     *   <li><strong>Trailing zeros are stripped</strong>, so {@code 1} and {@code 1.0} are one
     *       number and hash alike. Without it, values that compare equal would hash differently.
     *   <li><strong>Zero is canonicalised</strong>, so {@code 0} and {@code -0.0} are one number.
     *       Hashing the raw double bits made those two equal-but-differently-hashed, which is the
     *       exact contract violation that loses entries from a {@code HashMap}.
     * </ul>
     *
     * <p>Built from {@link JsonPrimitive#getAsString()} rather than from a {@code double}, because
     * going through a {@code double} is what discards the low bits of a 64-bit id.
     *
     * @return the canonical value, or {@code null} for a number no decimal can represent (NaN, an
     *     infinity) — only reachable through Gson's lenient mode
     */
    private static BigDecimal canonicalNumber(JsonPrimitive primitive) {
        try {
            BigDecimal value = new BigDecimal(primitive.getAsString());
            return value.signum() == 0 ? BigDecimal.ZERO : value.stripTrailingZeros();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The canonical form of a numeric field, or {@code null} if it is not a usable number. */
    private BigDecimal number(String key) {
        JsonElement value = primitive(key);
        if (value == null || !value.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        return canonicalNumber(value.getAsJsonPrimitive());
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

    /**
     * Adopts an object as a payload, copying it at the boundary.
     *
     * <p>The copy is what makes the immutability guarantee unconditional rather than a contract the
     * caller has to keep. This is the entry point every inbound frame takes, and the payload it
     * produces is then handed to handler executors on other threads; sharing a subtree with
     * whatever parsed it would make that safe only for as long as nobody touched the parse result
     * afterwards.
     */
    static Payload wrap(JsonObject json) {
        return json == null ? EMPTY : new Payload(json.deepCopy());
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

        /**
         * Writes a 64-bit integer.
         *
         * <p><strong>Use this, not {@link #put(String, double)}, for ids and timestamps.</strong>
         * The {@code double} overload emits {@code 1.7E12} for an epoch-millis value — valid JSON,
         * read back as a different number — and silently loses precision above 2^53, which covers
         * every Discord snowflake.
         */
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
