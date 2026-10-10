package com.heimdall.core.items;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds a {@link ChatItem} from the arguments of a MiniMessage {@code show_item} hover.
 *
 * <h2>The grammar, as ChatControl writes it</h2>
 *
 * <p>After {@code hover:show_item:} come the item id, an optional count, and then either:
 *
 * <ul>
 *   <li><strong>data components</strong> (Paper 1.20.5+, and what Adam's 1.21.5 captures show):
 *       {@code key:value} pairs, the key without {@code minecraft:} and the value SNBT, quoted or
 *       bare ({@code unbreakable:{}}, {@code repair_cost:7}). A key written {@code !key} is a removed
 *       component and is skipped with its value;
 *   <li><strong>one legacy NBT holder</strong> (Paper 1.16.5 to 1.20.4, and below 1.16): a single
 *       quoted SNBT compound with the item's {@code tag}, or sometimes the whole stack
 *       ({@code {id:..., Count:..., tag:{...}}});
 *   <li><strong>nothing</strong>: {@code show_item:stone}, {@code show_item:stone:5}.
 * </ul>
 *
 * <p>The id is normally bare. A namespaced id is either quoted (one argument with a colon in it) or
 * arrives split across two arguments; the second case is recognised only when the first argument is
 * {@code minecraft} or the pairs would not otherwise line up, because a vanilla item called
 * {@code minecraft} does not exist and a split custom id is the only way the arity can be odd.
 *
 * <h2>Strict where it matters, lenient where it does not</h2>
 *
 * <p>A shape that cannot be an item (no id, an id with characters no id has, an odd number of
 * key/value arguments) yields {@code null}: the caller then leaves the line exactly as it was. A
 * single component value that will not parse is skipped instead, and the rest of the item kept: a
 * new component format in some future release should cost one tooltip line, not the whole image.
 */
public final class ShowItem {

    /** 1.8 to 1.12 numeric enchantment ids, for the legacy {@code ench} list. */
    private static final String[] LEGACY_ENCHANTMENTS = new String[72];

    static {
        String[][] table = {
            {"0", "protection"}, {"1", "fire_protection"}, {"2", "feather_falling"},
            {"3", "blast_protection"}, {"4", "projectile_protection"}, {"5", "respiration"},
            {"6", "aqua_affinity"}, {"7", "thorns"}, {"8", "depth_strider"},
            {"9", "frost_walker"}, {"10", "binding_curse"}, {"16", "sharpness"}, {"17", "smite"},
            {"18", "bane_of_arthropods"}, {"19", "knockback"}, {"20", "fire_aspect"},
            {"21", "looting"}, {"22", "sweeping"}, {"32", "efficiency"}, {"33", "silk_touch"},
            {"34", "unbreaking"}, {"35", "fortune"}, {"48", "power"}, {"49", "punch"},
            {"50", "flame"}, {"51", "infinity"}, {"61", "luck_of_the_sea"}, {"62", "lure"},
            {"70", "mending"}, {"71", "vanishing_curse"},
        };
        for (String[] row : table) {
            LEGACY_ENCHANTMENTS[Integer.parseInt(row[0])] = row[1];
        }
    }

    private ShowItem() {
    }

    /**
     * The item described by {@code args} (everything after {@code show_item}), or {@code null} if
     * the arguments cannot describe one.
     */
    public static ChatItem parse(List<String> args, ItemTranslations translations) {
        if (args == null || args.isEmpty()) {
            return null;
        }
        ItemTranslations tr = translations == null ? ItemTranslations.NONE : translations;
        ChatItem bare = attempt(args, 1, tr);
        boolean preferSplit = args.size() > 1 && "minecraft".equalsIgnoreCase(args.get(0));
        if (bare != null && !preferSplit) {
            return bare;
        }
        if (args.size() > 1 && isNamespace(args.get(0)) && isPath(args.get(1))) {
            ChatItem split = attempt(args, 2, tr);
            if (split != null) {
                return split;
            }
        }
        return bare;
    }

    /** Parses with the id taking the first {@code idArgs} arguments. */
    private static ChatItem attempt(List<String> args, int idArgs, ItemTranslations tr) {
        if (args.size() < idArgs) {
            return null;
        }
        String id = idArgs == 1 ? args.get(0) : args.get(0) + ":" + args.get(1);
        if (!isItemId(id)) {
            return null;
        }
        ChatItem.Builder item = ChatItem.builder(id);
        int i = idArgs;
        if (i < args.size() && isCount(args.get(i))) {
            item.count(Integer.parseInt(args.get(i).trim()));
            i++;
        }
        int remaining = args.size() - i;
        if (remaining == 0) {
            return item.build();
        }
        if (remaining == 1) {
            String holder = args.get(i).trim();
            if (!holder.startsWith("{")) {
                return null;
            }
            try {
                Map<String, Object> nbt = Snbt.asMap(Snbt.parse(holder, 256 * 1024));
                if (nbt == null) {
                    return null;
                }
                applyLegacy(item, nbt, tr);
                return item.build();
            } catch (Snbt.SyntaxException malformed) {
                // The legacy holder IS the item's data: without it there is nothing to show.
                return null;
            }
        }
        if (remaining % 2 != 0) {
            return null;
        }
        for (; i + 1 < args.size(); i += 2) {
            String key = args.get(i).trim().toLowerCase(Locale.ROOT);
            if (key.isEmpty() || key.startsWith("!")) {
                continue;
            }
            if (key.startsWith("minecraft:")) {
                key = key.substring("minecraft:".length());
            }
            Object value;
            try {
                value = Snbt.parse(args.get(i + 1), 256 * 1024);
            } catch (Snbt.SyntaxException malformed) {
                continue;
            }
            applyComponent(item, key, value, tr);
        }
        return item.build();
    }

    /** One data component onto the builder. Unknown keys are ignored. */
    static void applyComponent(ChatItem.Builder item, String key, Object value,
            ItemTranslations tr) {
        if ("custom_name".equals(key)) {
            item.customName(TextComponents.read(value, tr));
        } else if ("item_name".equals(key)) {
            item.itemName(TextComponents.read(value, tr));
        } else if ("lore".equals(key)) {
            List<Object> lines = Snbt.asList(value);
            if (lines != null) {
                item.clearLore();
                for (Object line : lines) {
                    item.addLore(TextComponents.read(line, tr));
                }
            }
        } else if ("enchantments".equals(key) || "stored_enchantments".equals(key)) {
            boolean stored = "stored_enchantments".equals(key);
            Map<String, Object> map = Snbt.asMap(value);
            if (map == null) {
                return;
            }
            // 1.20.5 to 1.21.4 wrapped the levels: {levels:{...}, show_in_tooltip:0b}.
            Map<String, Object> levels = Snbt.asMap(map.get("levels"));
            if (levels == null) {
                levels = map;
            } else if (Boolean.FALSE.equals(Snbt.asBoolean(map.get("show_in_tooltip"), null))) {
                item.hide(key);
            }
            for (Map.Entry<String, Object> entry : levels.entrySet()) {
                Integer level = Snbt.asInt(entry.getValue(), null);
                if (level == null) {
                    continue;
                }
                if (stored) {
                    item.storedEnchantment(entry.getKey(), level);
                } else {
                    item.enchantment(entry.getKey(), level);
                }
            }
        } else if ("damage".equals(key)) {
            item.damage(Snbt.asInt(value, null));
        } else if ("max_damage".equals(key)) {
            item.maxDamage(Snbt.asInt(value, null));
        } else if ("unbreakable".equals(key)) {
            item.unbreakable(true);
            Map<String, Object> map = Snbt.asMap(value);
            if (map != null
                    && Boolean.FALSE.equals(Snbt.asBoolean(map.get("show_in_tooltip"), null))) {
                item.hide("unbreakable");
            }
        } else if ("tooltip_display".equals(key)) {
            Map<String, Object> map = Snbt.asMap(value);
            if (map == null) {
                return;
            }
            if (Boolean.TRUE.equals(Snbt.asBoolean(map.get("hide_tooltip"), null))) {
                item.hideTooltip(true);
            }
            List<Object> hidden = Snbt.asList(map.get("hidden_components"));
            if (hidden != null) {
                for (Object component : hidden) {
                    if (component instanceof String) {
                        item.hide((String) component);
                    }
                }
            }
        } else if ("hide_tooltip".equals(key)) {
            item.hideTooltip(true);
        } else if ("custom_model_data".equals(key)) {
            item.customModelData(CustomModelData.read(value));
        } else if ("item_model".equals(key)) {
            item.itemModel(Snbt.asString(value));
        } else if ("rarity".equals(key)) {
            item.rarity(Snbt.asString(value));
        } else if ("enchantment_glint_override".equals(key)) {
            item.glintOverride(Snbt.asBoolean(value, null));
        } else if ("dyed_color".equals(key)) {
            Map<String, Object> map = Snbt.asMap(value);
            item.dyedColor(Snbt.asInt(map != null ? map.get("rgb") : value, null));
        } else if ("potion_contents".equals(key)) {
            Map<String, Object> map = Snbt.asMap(value);
            if (map != null) {
                item.potionColor(Snbt.asInt(map.get("custom_color"), null));
            }
        }
    }

    /** A pre-1.20.5 NBT tag (or a whole stack holding one) onto the builder. */
    static void applyLegacy(ChatItem.Builder item, Map<String, Object> nbt, ItemTranslations tr) {
        Map<String, Object> tag = nbt;
        Map<String, Object> nested = Snbt.asMap(nbt.get("tag"));
        if (nested != null && nbt.containsKey("id")) {
            // The whole stack: {id, Count, Damage, tag}. Before 1.13 Damage lives here, on the stack.
            tag = nested;
            Integer stackDamage = Snbt.asInt(nbt.get("Damage"), null);
            if (stackDamage != null && stackDamage > 0) {
                item.damage(stackDamage);
            }
        }
        Map<String, Object> display = Snbt.asMap(tag.get("display"));
        if (display != null) {
            if (display.containsKey("Name")) {
                item.customName(TextComponents.read(display.get("Name"), tr));
            }
            List<Object> lore = Snbt.asList(display.get("Lore"));
            if (lore != null) {
                for (Object line : lore) {
                    item.addLore(TextComponents.read(line, tr));
                }
            }
            Integer color = Snbt.asInt(display.get("color"), null);
            if (color != null) {
                item.dyedColor(color);
            }
        }
        readLegacyEnchantments(item, tag.get("Enchantments"), false);
        readLegacyEnchantments(item, tag.get("ench"), false);
        readLegacyEnchantments(item, tag.get("StoredEnchantments"), true);
        Integer damage = Snbt.asInt(tag.get("Damage"), null);
        if (damage != null) {
            item.damage(damage);
        }
        if (Boolean.TRUE.equals(Snbt.asBoolean(tag.get("Unbreakable"), null))) {
            item.unbreakable(true);
        }
        Integer cmd = Snbt.asInt(tag.get("CustomModelData"), null);
        if (cmd != null) {
            item.customModelData(CustomModelData.ofLegacy(cmd));
        }
        Integer hideFlags = Snbt.asInt(tag.get("HideFlags"), null);
        if (hideFlags != null) {
            if ((hideFlags & 1) != 0) {
                item.hide("enchantments");
            }
            if ((hideFlags & 4) != 0) {
                item.hide("unbreakable");
            }
            if ((hideFlags & 32) != 0) {
                // "Additional" information, which on a book is its stored enchantments.
                item.hide("stored_enchantments");
            }
        }
    }

    private static void readLegacyEnchantments(ChatItem.Builder item, Object value,
            boolean stored) {
        List<Object> list = Snbt.asList(value);
        if (list == null) {
            return;
        }
        for (Object element : list) {
            Map<String, Object> entry = Snbt.asMap(element);
            if (entry == null) {
                continue;
            }
            Integer level = Snbt.asInt(entry.get("lvl"), null);
            Object rawId = entry.get("id");
            String id = null;
            if (rawId instanceof String) {
                id = (String) rawId;
            } else if (rawId instanceof Number) {
                int numeric = ((Number) rawId).intValue();
                id = numeric >= 0 && numeric < LEGACY_ENCHANTMENTS.length
                        ? LEGACY_ENCHANTMENTS[numeric] : null;
            }
            if (id == null || level == null) {
                continue;
            }
            if (stored) {
                item.storedEnchantment(id, level);
            } else {
                item.enchantment(id, level);
            }
        }
    }

    private static boolean isCount(String arg) {
        String s = arg.trim();
        if (s.isEmpty() || s.length() > 6) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    /** A namespaced or bare resource location: {@code [a-z0-9_.-]+(:[a-z0-9_./-]+)?}. */
    static boolean isItemId(String id) {
        if (id == null || id.isEmpty() || id.length() > 128) {
            return false;
        }
        String lower = id.toLowerCase(Locale.ROOT);
        int colon = lower.indexOf(':');
        if (colon < 0) {
            return isPath(lower);
        }
        return colon > 0 && isNamespace(lower.substring(0, colon))
                && isPath(lower.substring(colon + 1));
    }

    private static boolean isNamespace(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = Character.toLowerCase(s.charAt(i));
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '.'
                    || c == '-')) {
                return false;
            }
        }
        return true;
    }

    private static boolean isPath(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = Character.toLowerCase(s.charAt(i));
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '.'
                    || c == '-' || c == '/')) {
                return false;
            }
        }
        // A path made only of digits is a count that lost its place, not an item.
        return !isCount(s);
    }
}
