package com.heimdall.platform.bukkit.itemimage;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Material;

/**
 * The defaults a 1.21.5 hover leaves out, answered by the server the plugin runs on.
 *
 * <p>A hover carries the item's component patch, so a fresh netherite shovel says nothing about its
 * durability and an ordinary heart of the sea nothing about its rarity. Both are properties of the
 * item type, which the server knows. No {@code ItemStack} is built for this: the type's
 * {@link Material} answers durability on every version, and on 1.20.5+ Paper the type's
 * {@code ItemType#getItemRarity()} answers rarity, reached reflectively because the 1.8.8 API this
 * module compiles against has neither. Before that, or when the call fails, a short table of the
 * types that are not common stands in.
 *
 * <p>Answers are memoised per id. Thread-safe.
 */
final class BukkitItemDefaults implements ItemDefaults {

    private static final Set<String> EPIC = new HashSet<String>(Arrays.asList(
            "enchanted_golden_apple", "command_block", "chain_command_block",
            "repeating_command_block", "command_block_minecart", "structure_block", "jigsaw",
            "debug_stick", "knowledge_book", "dragon_egg", "elytra", "mace", "heavy_core",
            "barrier", "light"));

    private static final Set<String> RARE = new HashSet<String>(Arrays.asList(
            "beacon", "conduit", "end_crystal", "golden_apple", "nether_star", "trident",
            "music_disc_pigstep", "music_disc_otherside", "music_disc_5", "music_disc_relic"));

    private static final Set<String> UNCOMMON = new HashSet<String>(Arrays.asList(
            "heart_of_the_sea", "experience_bottle", "dragon_breath", "enchanted_book",
            "creeper_head", "dragon_head", "piglin_head", "player_head", "skeleton_skull",
            "wither_skeleton_skull", "zombie_head", "totem_of_undying", "nautilus_shell",
            "trial_key", "ominous_trial_key", "ominous_bottle"));

    private final Map<String, Integer> durability = new ConcurrentHashMap<String, Integer>();
    private final Map<String, String> rarity = new ConcurrentHashMap<String, String>();

    @Override
    public int maxDurability(String id) {
        Integer known = durability.get(id);
        if (known != null) {
            return known;
        }
        int value = 0;
        try {
            Material material = material(id);
            value = material == null ? 0 : Math.max(0, material.getMaxDurability());
        } catch (Throwable unavailable) {
            value = 0;
        }
        durability.put(id, value);
        return value;
    }

    @Override
    public String rarity(String id) {
        String known = rarity.get(id);
        if (known != null) {
            return known.isEmpty() ? null : known;
        }
        String value = reflectiveRarity(id);
        if (value == null) {
            value = tableRarity(id);
        }
        rarity.put(id, value == null ? "" : value);
        return value;
    }

    private static String reflectiveRarity(String id) {
        try {
            Material material = material(id);
            if (material == null) {
                return null;
            }
            Method asItemType = Material.class.getMethod("asItemType");
            Object type = asItemType.invoke(material);
            if (type == null) {
                return null;
            }
            Method getItemRarity = type.getClass().getMethod("getItemRarity");
            getItemRarity.setAccessible(true);
            Object value = getItemRarity.invoke(type);
            return value == null ? null : value.toString().toLowerCase(Locale.ROOT);
        } catch (Throwable unavailable) {
            // Older than 1.20.5, Spigot rather than Paper, or the experimental API moved.
            return null;
        }
    }

    static String tableRarity(String id) {
        String path = id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : null;
        if (path == null) {
            return null;
        }
        if (EPIC.contains(path)) {
            return "epic";
        }
        if (RARE.contains(path)) {
            return "rare";
        }
        if (UNCOMMON.contains(path) || path.startsWith("music_disc_")) {
            return "uncommon";
        }
        return "common";
    }

    private static Material material(String id) {
        Material found = Material.matchMaterial(id);
        if (found == null && id.startsWith("minecraft:")) {
            // Before 1.13, matchMaterial does not understand the namespace.
            found = Material.matchMaterial(id.substring("minecraft:".length()));
        }
        return found;
    }
}
