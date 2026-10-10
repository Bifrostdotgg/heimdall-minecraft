package com.heimdall.platform.bukkit.itemimage;

/**
 * What the server knows about an item type that a hover does not carry: its default maximum damage
 * and its default rarity. A seam so the renderer can be tested without a server, and its own type
 * (no AWT anywhere near it) so {@link BukkitItemImages} can hold one before the AWT probe has run.
 */
interface ItemDefaults {

    /** Nothing known. */
    ItemDefaults NONE = new ItemDefaults() {
        @Override
        public int maxDurability(String id) {
            return 0;
        }

        @Override
        public String rarity(String id) {
            return null;
        }
    };

    /** The type's default maximum damage; 0 if it cannot be damaged or is unknown. */
    int maxDurability(String id);

    /** The type's default rarity name ({@code common}...), or {@code null}. */
    String rarity(String id);
}
