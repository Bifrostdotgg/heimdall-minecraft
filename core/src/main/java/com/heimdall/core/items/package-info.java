/**
 * Items shown in chat: reading ChatControl's {@code show_item} hovers into a version-neutral
 * {@link com.heimdall.core.items.ChatItem}, and replacing each hover in a relayed line with the plain
 * {@code [Name]} the players saw. Departure D86.
 *
 * <p>Platform-free, like the rest of core: parsing needs no server. Drawing the tooltip image does
 * (a client jar's textures and fonts, resource packs, {@code java.awt}), so that half lives behind
 * {@link com.heimdall.core.platform.ItemImages} on the Bukkit family and is a no-op everywhere else.
 *
 * <p>Nothing here stores an item or logs a name or lore line: those are player-authored text, and
 * the bridge's relay-only rules apply to them exactly as they do to the chat line they arrived in.
 */
package com.heimdall.core.items;
