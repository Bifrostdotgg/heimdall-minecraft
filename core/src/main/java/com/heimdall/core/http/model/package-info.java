/**
 * Immutable request and response models for the bot's Minecraft API.
 *
 * <p>These are the plugin's view of the wire, not the wire itself: parsing lives in {@code
 * com.heimdall.core.http}, so a field the bot renames is a change in one parser rather than in
 * every module that reads the value.
 *
 * <p><strong>Every type here is immutable, and none is built by a positional constructor.</strong>
 * A static factory is used only where the arguments cannot be confused with one another (a single
 * value, or values of distinct types); everything else takes a builder. v2's {@code
 * WhitelistResponse} had five telescoping constructors, four of which existed only to pass
 * defaults, and a new field meant a sixth — but the sharper failure is the one that still compiles:
 * four nullable Strings in a row, and nothing to say which is the ETag and which is the hash.
 *
 * <p>They also all implement {@code equals}, {@code hashCode} and {@code toString}. Change
 * detection needs it — deciding whether a re-fetched offense-type list actually differs, or whether
 * a role-sync directive has moved, is value comparison, and identity comparison quietly answers
 * "yes, always".
 */
package com.heimdall.core.http.model;
