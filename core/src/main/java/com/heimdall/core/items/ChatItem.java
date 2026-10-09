package com.heimdall.core.items;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * An item a player showed in chat, as a tooltip needs to see it, whatever version described it.
 *
 * <h2>Version-neutral on purpose</h2>
 *
 * <p>The same item reaches the plugin as 1.21.5 data components, as 1.20.5 components with
 * {@code show_in_tooltip} flags, or as a pre-1.20.5 NBT tag with {@code HideFlags}. {@link ShowItem}
 * folds every one of those into this one shape, so the renderer never has to know which it was.
 *
 * <h2>Only what the hover carried</h2>
 *
 * <p>From 1.21.5 a hover carries the item's component <em>patch</em>: what differs from the item
 * type's defaults. So an undamaged pickaxe has no {@link #damage()} here, and a netherite shovel no
 * {@link #maxDamage()}. Filling in a default is the renderer's job, from the server it runs on, and
 * that split is deliberate: core cannot ask a server anything, and the patch is the truth about the
 * stack.
 *
 * <h2>Never stored</h2>
 *
 * <p>Like {@code ChatMessage}, this lives for the length of one relay. {@link #cacheKey()} lets a
 * renderer reuse an image it already drew for an identical item; it is a key into rendered pixels,
 * not a record of who showed what. {@link #toString()} names the id and counts only, because a name
 * or a lore line is player-authored text.
 *
 * <p>Immutable; built with {@link #builder(String)}.
 */
public final class ChatItem {

    private final String id;
    private final int count;
    private final ItemText customName;
    private final ItemText itemName;
    private final List<ItemText> lore;
    private final Map<String, Integer> enchantments;
    private final Map<String, Integer> storedEnchantments;
    private final Integer damage;
    private final Integer maxDamage;
    private final boolean unbreakable;
    private final Set<String> hiddenComponents;
    private final boolean hideTooltip;
    private final CustomModelData customModelData;
    private final String itemModel;
    private final String rarity;
    private final Boolean glintOverride;
    private final Integer dyedColor;
    private final Integer potionColor;

    private ChatItem(Builder b) {
        this.id = b.id;
        this.count = b.count;
        this.customName = b.customName;
        this.itemName = b.itemName;
        this.lore = Collections.unmodifiableList(new ArrayList<ItemText>(b.lore));
        this.enchantments = Collections.unmodifiableMap(
                new LinkedHashMap<String, Integer>(b.enchantments));
        this.storedEnchantments = Collections.unmodifiableMap(
                new LinkedHashMap<String, Integer>(b.storedEnchantments));
        this.damage = b.damage;
        this.maxDamage = b.maxDamage;
        this.unbreakable = b.unbreakable;
        this.hiddenComponents = Collections.unmodifiableSet(
                new LinkedHashSet<String>(b.hiddenComponents));
        this.hideTooltip = b.hideTooltip;
        this.customModelData = b.customModelData;
        this.itemModel = b.itemModel;
        this.rarity = b.rarity;
        this.glintOverride = b.glintOverride;
        this.dyedColor = b.dyedColor;
        this.potionColor = b.potionColor;
    }

    /** Starts an item of {@code id}; a bare id gains the {@code minecraft:} namespace. */
    public static Builder builder(String id) {
        return new Builder(namespaced(id));
    }

    /** {@code stone} becomes {@code minecraft:stone}; an id with a namespace is unchanged. */
    public static String namespaced(String id) {
        if (id == null) {
            return "minecraft:air";
        }
        String trimmed = id.trim().toLowerCase(Locale.ROOT);
        return trimmed.indexOf(':') >= 0 ? trimmed : "minecraft:" + trimmed;
    }

    /** The namespaced item id, e.g. {@code minecraft:netherite_shovel}. */
    public String id() {
        return id;
    }

    /** The namespace half of {@link #id()}. */
    public String namespace() {
        return id.substring(0, id.indexOf(':'));
    }

    /** The path half of {@link #id()}. */
    public String path() {
        return id.substring(id.indexOf(':') + 1);
    }

    public int count() {
        return count;
    }

    /** {@code custom_name} (an anvil name), or {@code null}. Shown italic by default. */
    public ItemText customName() {
        return customName;
    }

    /** {@code item_name} (a non-italic name set by a plugin or datapack), or {@code null}. */
    public ItemText itemName() {
        return itemName;
    }

    public List<ItemText> lore() {
        return lore;
    }

    /** Enchantment id to level, in the order the hover listed them. */
    public Map<String, Integer> enchantments() {
        return enchantments;
    }

    /** An enchanted book's stored enchantments, in order. */
    public Map<String, Integer> storedEnchantments() {
        return storedEnchantments;
    }

    /** The damage taken, or {@code null} when the hover did not say (the type's default, 0). */
    public Integer damage() {
        return damage;
    }

    /** The maximum damage, or {@code null} when the hover did not say (the type's default). */
    public Integer maxDamage() {
        return maxDamage;
    }

    public boolean unbreakable() {
        return unbreakable;
    }

    /**
     * Components the tooltip must not show, as namespaced ids ({@code minecraft:unbreakable}),
     * whichever mechanism hid them: 1.21.5 {@code tooltip_display}, 1.20.5
     * {@code show_in_tooltip:false}, or a legacy {@code HideFlags} bit.
     */
    public Set<String> hiddenComponents() {
        return hiddenComponents;
    }

    /** Whether {@code component} (bare or namespaced) is hidden from the tooltip. */
    public boolean isHidden(String component) {
        return hideTooltip || hiddenComponents.contains(namespaced(component));
    }

    /** The whole tooltip is hidden; only the icon is shown. */
    public boolean hideTooltip() {
        return hideTooltip;
    }

    public CustomModelData customModelData() {
        return customModelData;
    }

    /** {@code item_model}, namespaced, or {@code null}. */
    public String itemModel() {
        return itemModel;
    }

    /** {@code rarity} ({@code common}, {@code uncommon}, {@code rare}, {@code epic}), or {@code null}. */
    public String rarity() {
        return rarity;
    }

    /** {@code enchantment_glint_override}, or {@code null} when the hover did not set it. */
    public Boolean glintOverride() {
        return glintOverride;
    }

    /** {@code dyed_color} (RGB), or {@code null}. */
    public Integer dyedColor() {
        return dyedColor;
    }

    /** A potion's {@code custom_color} (RGB), or {@code null}. */
    public Integer potionColor() {
        return potionColor;
    }

    /**
     * The name a reader sees, as plain text: the custom name, else the item name, else the item
     * type's English name from {@code translations}, else the id made readable
     * ({@code netherite_shovel} becomes {@code Netherite Shovel}).
     *
     * <p>Control characters are removed: this goes into a single relayed line.
     */
    public String displayName(ItemTranslations translations) {
        ItemText name = nameText(translations);
        String plain = stripControls(name.plain()).trim();
        return plain.isEmpty() ? humanise(path()) : plain;
    }

    /**
     * The name line as text, for a tooltip: {@link #customName()}, else {@link #itemName()}, else
     * the translated default. Whether it is a custom name decides the default italic, which is the
     * renderer's to apply.
     */
    public ItemText nameText(ItemTranslations translations) {
        if (customName != null && !customName.plain().trim().isEmpty()) {
            return customName;
        }
        if (itemName != null && !itemName.plain().trim().isEmpty()) {
            return itemName;
        }
        return ItemText.plain(defaultName(translations));
    }

    /** Whether the name line is a {@code custom_name}, which vanilla shows in italic. */
    public boolean hasCustomName() {
        return customName != null && !customName.plain().trim().isEmpty();
    }

    /**
     * The item type's own name: {@code item.<ns>.<path>} then {@code block.<ns>.<path>} from the
     * language data, else the humanised id.
     */
    public String defaultName(ItemTranslations translations) {
        ItemTranslations tr = translations == null ? ItemTranslations.NONE : translations;
        String ns = namespace();
        String path = path().replace('/', '.');
        String[] keys = {"item." + ns + "." + path, "block." + ns + "." + path};
        for (String key : keys) {
            try {
                String found = tr.translate(key);
                if (found != null && !found.trim().isEmpty()) {
                    return found;
                }
            } catch (RuntimeException ignored) {
                // A translation source that throws is treated as one that does not know.
            }
        }
        return humanise(path());
    }

    /** {@code netherite_shovel} to {@code Netherite Shovel}; also for enchantment ids. */
    public static String humanise(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        String leaf = path.substring(path.lastIndexOf('/') + 1);
        StringBuilder out = new StringBuilder();
        boolean upper = true;
        for (int i = 0; i < leaf.length(); i++) {
            char c = leaf.charAt(i);
            if (c == '_' || c == '-' || c == '.') {
                out.append(' ');
                upper = true;
            } else if (upper) {
                out.append(Character.toUpperCase(c));
                upper = false;
            } else {
                out.append(c);
            }
        }
        return out.toString().trim();
    }

    private static String stripControls(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t') {
                out.append(' ');
            } else if (!Character.isISOControl(c)) {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * A stable key covering everything that changes the picture, for a render cache. Contains
     * player-authored text, so it is never logged.
     */
    public String cacheKey() {
        StringBuilder out = new StringBuilder(128);
        out.append(id).append('|').append(count).append('|');
        out.append(customName == null ? "-" : customName.key()).append('|');
        out.append(itemName == null ? "-" : itemName.key()).append('|');
        out.append(lore.size()).append(':');
        for (ItemText line : lore) {
            out.append(line.key());
        }
        out.append('|').append(enchantments).append('|').append(storedEnchantments);
        out.append('|').append(damage).append('/').append(maxDamage).append('|').append(unbreakable);
        out.append('|').append(hiddenComponents).append('|').append(hideTooltip);
        out.append('|').append(customModelData).append('|').append(itemModel);
        out.append('|').append(rarity).append('|').append(glintOverride);
        out.append('|').append(dyedColor).append('|').append(potionColor);
        return out.toString();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ChatItem && cacheKey().equals(((ChatItem) other).cacheKey());
    }

    @Override
    public int hashCode() {
        return cacheKey().hashCode();
    }

    /** The id and counts, never the name or lore text. */
    @Override
    public String toString() {
        return "ChatItem{" + id + " x" + count + ", lore=" + lore.size() + ", enchantments="
                + enchantments.size() + (customName != null ? ", named" : "") + "}";
    }

    /** Mutable assembly for {@link ChatItem}. Not thread-safe. */
    public static final class Builder {

        /** Lore lines kept; more is dropped. A tooltip renderer caps lower still. */
        static final int MAX_LORE = 64;

        /** Enchantments kept per map. */
        static final int MAX_ENCHANTMENTS = 64;

        private final String id;
        private int count = 1;
        private ItemText customName;
        private ItemText itemName;
        private final List<ItemText> lore = new ArrayList<ItemText>();
        private final Map<String, Integer> enchantments = new LinkedHashMap<String, Integer>();
        private final Map<String, Integer> storedEnchantments =
                new LinkedHashMap<String, Integer>();
        private Integer damage;
        private Integer maxDamage;
        private boolean unbreakable;
        private final Set<String> hiddenComponents = new LinkedHashSet<String>();
        private boolean hideTooltip;
        private CustomModelData customModelData = CustomModelData.NONE;
        private String itemModel;
        private String rarity;
        private Boolean glintOverride;
        private Integer dyedColor;
        private Integer potionColor;

        private Builder(String id) {
            this.id = id;
        }

        public Builder count(int value) {
            this.count = Math.max(1, Math.min(value, 99_999));
            return this;
        }

        public Builder customName(ItemText value) {
            this.customName = value;
            return this;
        }

        public Builder itemName(ItemText value) {
            this.itemName = value;
            return this;
        }

        public Builder addLore(ItemText line) {
            if (line != null && lore.size() < MAX_LORE) {
                lore.add(line);
            }
            return this;
        }

        public Builder clearLore() {
            lore.clear();
            return this;
        }

        public Builder enchantment(String enchantment, int level) {
            if (enchantment != null && enchantments.size() < MAX_ENCHANTMENTS) {
                enchantments.put(namespaced(enchantment), level);
            }
            return this;
        }

        public Builder storedEnchantment(String enchantment, int level) {
            if (enchantment != null && storedEnchantments.size() < MAX_ENCHANTMENTS) {
                storedEnchantments.put(namespaced(enchantment), level);
            }
            return this;
        }

        public Builder damage(Integer value) {
            this.damage = value == null ? null : Math.max(0, value);
            return this;
        }

        public Builder maxDamage(Integer value) {
            this.maxDamage = value == null ? null : Math.max(0, value);
            return this;
        }

        public Builder unbreakable(boolean value) {
            this.unbreakable = value;
            return this;
        }

        public Builder hide(String component) {
            if (component != null && !component.trim().isEmpty() && hiddenComponents.size() < 64) {
                hiddenComponents.add(namespaced(component));
            }
            return this;
        }

        public Builder hideTooltip(boolean value) {
            this.hideTooltip = value;
            return this;
        }

        public Builder customModelData(CustomModelData value) {
            this.customModelData = value == null ? CustomModelData.NONE : value;
            return this;
        }

        public Builder itemModel(String value) {
            this.itemModel = value == null || value.trim().isEmpty() ? null : namespaced(value);
            return this;
        }

        public Builder rarity(String value) {
            this.rarity = value == null ? null : value.trim().toLowerCase(Locale.ROOT);
            return this;
        }

        public Builder glintOverride(Boolean value) {
            this.glintOverride = value;
            return this;
        }

        public Builder dyedColor(Integer value) {
            this.dyedColor = value == null ? null : value & 0xFFFFFF;
            return this;
        }

        public Builder potionColor(Integer value) {
            this.potionColor = value == null ? null : value & 0xFFFFFF;
            return this;
        }

        public ChatItem build() {
            return new ChatItem(this);
        }
    }
}
