/**
 * Immutable request and response models for the bot's Minecraft API.
 *
 * <p>These are the plugin's view of the wire, not the wire itself: parsing lives in {@code
 * com.heimdall.core.http}, so a field the bot renames is a change in one parser rather than in
 * every module that reads the value.
 *
 * <p>Every type here is immutable and built by a static factory or a builder. v2's {@code
 * WhitelistResponse} had five telescoping constructors, four of which existed only to pass
 * defaults, and a new field meant a sixth.
 */
package com.heimdall.core.http.model;
