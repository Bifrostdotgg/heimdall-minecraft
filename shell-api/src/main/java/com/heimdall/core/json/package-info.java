/**
 * The wire's data model, with no Gson in its public signatures.
 *
 * <p>Gson is shaded, relocated and declared {@code implementation} — a deliberate choice that keeps
 * the relocation surface in one place, and one that costs nothing right up until core has to hand a
 * feature module something JSON-shaped. Phase 1b has two of those: tunnel payloads, and a module's
 * own slice of remote config.
 *
 * <p>{@link com.heimdall.core.json.Payload} is the answer to both. It is a read-only view over a
 * JSON object with defaulting accessors, and it is the same type
 * {@code RemoteConfig.moduleSettings(id)} returns — because "a typed view over a JSON object, with
 * defaults" is one problem and solving it twice is how the two drift apart.
 *
 * <p>{@link com.heimdall.core.json.Envelope} lives here rather than in the tunnel package purely so
 * it can reach {@code Payload}'s package-private Gson bridge, and therefore build a frame without
 * serialising and re-parsing a payload that was just constructed.
 */
package com.heimdall.core.json;
