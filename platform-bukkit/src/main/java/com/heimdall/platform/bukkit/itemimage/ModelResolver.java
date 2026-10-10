package com.heimdall.platform.bukkit.itemimage;

import com.heimdall.core.items.ChatItem;
import com.heimdall.core.items.CustomModelData;
import com.heimdall.core.items.Snbt;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Works out what an item's icon is made of, from the packs, the way the client would.
 *
 * <h2>Order</h2>
 *
 * <ol>
 *   <li>{@code item_model}: on 1.21.4+ an item definition at {@code items/<path>.json}; on 1.21.2 and
 *       1.21.3 it named a model directly, so {@code models/<path>.json} is tried next.
 *   <li>The item type's own definition, {@code items/<id>.json} (1.21.4+), evaluated with the item's
 *       {@code custom_model_data}: {@code range_dispatch} takes the highest threshold at or below
 *       the value, {@code select} matches strings, {@code condition} reads flags, {@code composite}
 *       layers everything, and tints come from constants, dye, potion or custom model data colours.
 *       ItemsAdder's Warded Jar resolves this way: its pack overrides
 *       {@code minecraft:items/heart_of_the_sea.json} with a range dispatch on 10002.
 *   <li>The pre-1.21.4 model {@code models/item/<id>.json} and its {@code overrides}, matched on
 *       {@code custom_model_data} (and {@code damage}/{@code damaged}); the last match wins, as in
 *       the client.
 * </ol>
 *
 * <p>Each chosen model's {@code parent} chain is merged (textures child-first, the nearest elements,
 * the nearest {@code display.gui}) and its texture variables resolved. {@code builtin/generated} at
 * the end of the chain means flat layered sprites; elements mean a 3D model; a
 * {@code minecraft:special} model, {@code builtin/entity}, or anything that cannot be resolved falls
 * back to the base model's particle texture, and failing that to a placeholder. Player skins are
 * never fetched.
 *
 * <p>Pure data: no AWT, no I/O beyond the {@link PackStack}. Bounded: definition nesting, parent
 * chains and texture-variable hops are all capped, and every step checks the render's
 * {@link Deadline}. Used on the render thread only.
 */
final class ModelResolver {

    static final int MAX_DEFINITION_DEPTH = 32;
    static final int MAX_PARENT_DEPTH = 32;
    static final int MAX_TEXTURE_HOPS = 16;
    static final int MAX_MODELS = 16;
    static final int MAX_ELEMENTS = 256;

    /** The default potion tint when nothing says otherwise (vanilla's "no effects" colour). */
    static final int DEFAULT_POTION_TINT = 0xF800F8;

    private final PackStack stack;
    private final Map<String, Object> jsonCache = new HashMap<String, Object>();

    ModelResolver(PackStack stack) {
        this.stack = stack;
    }

    // ── Results ──────────────────────────────────────────────────────────────

    /** One element face, with its texture already resolved to a texture reference. */
    static final class Face {
        final String direction;
        final float[] uv;
        final String texture;
        final int rotation;
        final int tintIndex;

        Face(String direction, float[] uv, String texture, int rotation, int tintIndex) {
            this.direction = direction;
            this.uv = uv;
            this.texture = texture;
            this.rotation = rotation;
            this.tintIndex = tintIndex;
        }
    }

    /** One cuboid of a 3D model, in model units (0 to 16). */
    static final class Element {
        final float[] from;
        final float[] to;
        final float[] rotationOrigin;
        final char rotationAxis;
        final float rotationAngle;
        final boolean rescale;
        final boolean shade;
        final List<Face> faces;

        Element(float[] from, float[] to, float[] rotationOrigin, char rotationAxis,
                float rotationAngle, boolean rescale, boolean shade, List<Face> faces) {
            this.from = from;
            this.to = to;
            this.rotationOrigin = rotationOrigin;
            this.rotationAxis = rotationAxis;
            this.rotationAngle = rotationAngle;
            this.rescale = rescale;
            this.shade = shade;
            this.faces = faces;
        }
    }

    /** A model's GUI display transform: rotation in degrees, translation in pixels, scale. */
    static final class Transform {
        static final Transform IDENTITY = new Transform(
                new float[] {0, 0, 0}, new float[] {0, 0, 0}, new float[] {1, 1, 1});

        final float[] rotation;
        final float[] translation;
        final float[] scale;

        Transform(float[] rotation, float[] translation, float[] scale) {
            this.rotation = rotation;
            this.translation = translation;
            this.scale = scale;
        }
    }

    /** One drawable part of the icon. Exactly one of {@code layers} or {@code elements} is used. */
    static final class Part {
        /** Flat sprites, bottom first, each with its tint (-1 for none). */
        final List<String> layers;
        final int[] layerTints;
        final List<Element> elements;
        final Transform gui;
        final boolean sideLit;
        final int[] tints;

        Part(List<String> layers, int[] layerTints, List<Element> elements, Transform gui,
                boolean sideLit, int[] tints) {
            this.layers = layers;
            this.layerTints = layerTints;
            this.elements = elements;
            this.gui = gui;
            this.sideLit = sideLit;
            this.tints = tints;
        }

        boolean flat() {
            return elements == null;
        }

        int tint(int index) {
            return index >= 0 && index < tints.length ? tints[index] : -1;
        }
    }

    /** What to draw: parts in order, or nothing usable (a placeholder). */
    static final class Icon {
        final List<Part> parts;

        Icon(List<Part> parts) {
            this.parts = parts;
        }

        boolean placeholder() {
            return parts.isEmpty();
        }
    }

    /** The item's properties a definition can read. */
    static final class Context {
        final ChatItem item;
        final int maxDamage;

        Context(ChatItem item, int maxDamage) {
            this.item = item;
            this.maxDamage = maxDamage;
        }

        CustomModelData cmd() {
            return item.customModelData();
        }

        int damage() {
            return item.damage() == null ? 0 : item.damage();
        }
    }

    // ── Entry point ──────────────────────────────────────────────────────────

    /** The icon for {@code item}; a placeholder when nothing resolves. */
    Icon resolve(ChatItem item, int maxDamage, Deadline deadline) {
        Context context = new Context(item, maxDamage);
        List<Selected> selected = new ArrayList<Selected>();

        if (item.itemModel() != null) {
            if (!definition(item.itemModel(), context, selected, deadline)) {
                // 1.21.2 / 1.21.3: item_model named a model, not a definition.
                selected.add(new Selected(item.itemModel(), new int[0], false));
            }
        }
        if (selected.isEmpty()) {
            definition(item.id(), context, selected, deadline);
        }
        if (selected.isEmpty()) {
            selected.add(new Selected(legacyModel(item, context, deadline), new int[0], false));
        }

        List<Part> parts = new ArrayList<Part>();
        for (Selected choice : selected) {
            if (parts.size() >= MAX_MODELS) {
                break;
            }
            deadline.check();
            Part part = part(choice, context, deadline);
            if (part != null) {
                parts.add(part);
            }
        }
        return new Icon(parts);
    }

    /** A model chosen by a definition, with the tints its definition supplied. */
    private static final class Selected {
        final String model;
        final int[] tints;
        final boolean special;

        Selected(String model, int[] tints, boolean special) {
            this.model = model;
            this.tints = tints;
            this.special = special;
        }
    }

    // ── Item definitions (1.21.4+) ───────────────────────────────────────────

    /** Evaluates {@code items/<id>.json}; {@code false} if there is no such definition. */
    private boolean definition(String id, Context context, List<Selected> out, Deadline deadline) {
        Map<String, Object> def = Snbt.asMap(json(path(id, "items", ".json")));
        if (def == null || !def.containsKey("model")) {
            return false;
        }
        evaluate(def.get("model"), context, out, deadline, 0);
        return true;
    }

    private void evaluate(Object node, Context context, List<Selected> out, Deadline deadline,
            int depth) {
        Map<String, Object> m = Snbt.asMap(node);
        if (m == null || depth > MAX_DEFINITION_DEPTH || out.size() >= MAX_MODELS) {
            return;
        }
        deadline.check();
        String type = strip(Snbt.asString(m.get("type")));
        if (type == null) {
            type = "model";
        }
        if ("model".equals(type)) {
            String model = Snbt.asString(m.get("model"));
            if (model != null) {
                out.add(new Selected(model, tints(m.get("tints"), context), false));
            }
        } else if ("composite".equals(type)) {
            List<Object> models = Snbt.asList(m.get("models"));
            if (models != null) {
                for (Object child : models) {
                    evaluate(child, context, out, deadline, depth + 1);
                }
            }
        } else if ("condition".equals(type)) {
            boolean value = condition(m, context);
            evaluate(m.get(value ? "on_true" : "on_false"), context, out, deadline, depth + 1);
        } else if ("select".equals(type)) {
            String value = selectValue(m, context);
            Object chosen = null;
            List<Object> cases = Snbt.asList(m.get("cases"));
            if (value != null && cases != null) {
                for (Object element : cases) {
                    Map<String, Object> entry = Snbt.asMap(element);
                    if (entry != null && whenMatches(entry.get("when"), value)) {
                        chosen = entry.get("model");
                        break;
                    }
                }
            }
            evaluate(chosen != null ? chosen : m.get("fallback"), context, out, deadline,
                    depth + 1);
        } else if ("range_dispatch".equals(type)) {
            Float value = rangeValue(m, context);
            Object chosen = null;
            List<Object> entries = Snbt.asList(m.get("entries"));
            if (value != null && entries != null) {
                double scale = Snbt.asDouble(m.get("scale"), 1.0);
                double scaled = value * scale;
                double best = Double.NEGATIVE_INFINITY;
                for (Object element : entries) {
                    Map<String, Object> entry = Snbt.asMap(element);
                    Double threshold = entry == null ? null
                            : Snbt.asDouble(entry.get("threshold"), null);
                    if (threshold != null && threshold <= scaled && threshold >= best) {
                        best = threshold;
                        chosen = entry.get("model");
                    }
                }
            }
            evaluate(chosen != null ? chosen : m.get("fallback"), context, out, deadline,
                    depth + 1);
        } else if ("special".equals(type)) {
            String base = Snbt.asString(m.get("base"));
            if (base != null) {
                out.add(new Selected(base, new int[0], true));
            }
        } else if (!"empty".equals(type)) {
            // bundle/selected_item and anything newer: whatever fallback it offers.
            evaluate(m.get("fallback"), context, out, deadline, depth + 1);
        }
    }

    private static boolean condition(Map<String, Object> m, Context context) {
        String property = strip(Snbt.asString(m.get("property")));
        if ("custom_model_data".equals(property)) {
            return context.cmd().flagAt(Snbt.asInt(m.get("index"), 0));
        }
        if ("damaged".equals(property)) {
            return context.damage() > 0;
        }
        if ("broken".equals(property)) {
            return context.maxDamage > 0 && context.damage() >= context.maxDamage - 1;
        }
        if ("has_component".equals(property)) {
            String component = strip(Snbt.asString(m.get("component")));
            ChatItem item = context.item;
            if ("custom_name".equals(component)) {
                return item.customName() != null;
            }
            if ("enchantments".equals(component)) {
                return !item.enchantments().isEmpty();
            }
            if ("unbreakable".equals(component)) {
                return item.unbreakable();
            }
            if ("damage".equals(component)) {
                return item.damage() != null;
            }
            return false;
        }
        // using_item, fishing_rod/cast, selected, carried, keybind_down...: not in a tooltip.
        return false;
    }

    private static String selectValue(Map<String, Object> m, Context context) {
        String property = strip(Snbt.asString(m.get("property")));
        if ("custom_model_data".equals(property)) {
            return context.cmd().stringAt(Snbt.asInt(m.get("index"), 0));
        }
        if ("display_context".equals(property)) {
            return "gui";
        }
        if ("main_hand".equals(property)) {
            return "right";
        }
        if ("charge_type".equals(property)) {
            return "none";
        }
        return null;
    }

    private static boolean whenMatches(Object when, String value) {
        if (when instanceof String) {
            return value.equals(when) || value.equals(strip((String) when));
        }
        List<Object> options = Snbt.asList(when);
        if (options != null) {
            for (Object option : options) {
                if (option instanceof String
                        && (value.equals(option) || value.equals(strip((String) option)))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Float rangeValue(Map<String, Object> m, Context context) {
        String property = strip(Snbt.asString(m.get("property")));
        if ("custom_model_data".equals(property)) {
            return context.cmd().floatAt(Snbt.asInt(m.get("index"), 0));
        }
        if ("damage".equals(property)) {
            boolean normalize = Boolean.TRUE.equals(Snbt.asBoolean(m.get("normalize"), true));
            if (!normalize) {
                return (float) context.damage();
            }
            return context.maxDamage > 0
                    ? Math.max(0f, Math.min(1f, context.damage() / (float) context.maxDamage)) : 0f;
        }
        if ("count".equals(property)) {
            boolean normalize = Boolean.TRUE.equals(Snbt.asBoolean(m.get("normalize"), true));
            int count = context.item.count();
            return normalize ? Math.min(1f, count / 64f) : (float) count;
        }
        // compass, time, cooldown, use_duration...: what a still picture shows is the start.
        return 0f;
    }

    /** The tint list of a {@code minecraft:model} definition node, as RGB or -1. */
    private static int[] tints(Object raw, Context context) {
        List<Object> list = Snbt.asList(raw);
        if (list == null) {
            return new int[0];
        }
        int[] out = new int[Math.min(list.size(), 16)];
        for (int i = 0; i < out.length; i++) {
            out[i] = tint(Snbt.asMap(list.get(i)), context);
        }
        return out;
    }

    private static int tint(Map<String, Object> source, Context context) {
        if (source == null) {
            return -1;
        }
        String type = strip(Snbt.asString(source.get("type")));
        Integer fallback = CustomModelData.colorOf(source.get("default"));
        int base = fallback == null ? -1 : fallback;
        if ("constant".equals(type)) {
            Integer value = CustomModelData.colorOf(source.get("value"));
            return value == null ? -1 : value;
        }
        if ("dye".equals(type)) {
            Integer dyed = context.item.dyedColor();
            return dyed != null ? dyed : base;
        }
        if ("potion".equals(type)) {
            Integer potion = context.item.potionColor();
            return potion != null ? potion : base != -1 ? base : DEFAULT_POTION_TINT;
        }
        if ("custom_model_data".equals(type)) {
            Integer color = context.cmd().colorAt(Snbt.asInt(source.get("index"), 0));
            return color != null ? color : base;
        }
        if ("grass".equals(type)) {
            return 0x7CBD6B;
        }
        return base;
    }

    // ── Pre-1.21.4 overrides ─────────────────────────────────────────────────

    /** {@code item/<path>} with its {@code overrides} applied. */
    private String legacyModel(ChatItem item, Context context, Deadline deadline) {
        String base = item.namespace() + ":item/" + item.path();
        Map<String, Object> model = Snbt.asMap(json(path(base, "models", ".json")));
        List<Object> overrides = Snbt.asList(model == null ? null : model.get("overrides"));
        if (overrides == null) {
            return base;
        }
        String chosen = base;
        for (Object element : overrides) {
            deadline.check();
            Map<String, Object> override = Snbt.asMap(element);
            Map<String, Object> predicate = Snbt.asMap(override == null ? null
                    : override.get("predicate"));
            String target = override == null ? null : Snbt.asString(override.get("model"));
            if (predicate == null || target == null) {
                continue;
            }
            boolean matches = true;
            for (Map.Entry<String, Object> entry : predicate.entrySet()) {
                Double wanted = Snbt.asDouble(entry.getValue(), null);
                if (wanted == null || legacyProperty(entry.getKey(), context) < wanted) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                // Last match wins, as in the client.
                chosen = target;
            }
        }
        return chosen;
    }

    private static double legacyProperty(String key, Context context) {
        String property = strip(key);
        if ("custom_model_data".equals(property)) {
            Float value = context.cmd().floatAt(0);
            return value == null ? 0 : value;
        }
        if ("damaged".equals(property)) {
            return context.damage() > 0 ? 1 : 0;
        }
        if ("damage".equals(property)) {
            return context.maxDamage > 0 ? context.damage() / (double) context.maxDamage : 0;
        }
        return 0;
    }

    // ── Models ───────────────────────────────────────────────────────────────

    /** One selected model, merged along its parent chain, as a drawable part. */
    private Part part(Selected choice, Context context, Deadline deadline) {
        Map<String, Object> textures = new LinkedHashMap<String, Object>();
        List<Object> elements = null;
        Map<String, Object> gui = null;
        String guiLight = null;
        boolean generated = false;
        boolean entity = false;
        boolean found = false;
        String current = choice.model;
        for (int depth = 0; depth < MAX_PARENT_DEPTH && current != null; depth++) {
            deadline.check();
            String bare = strip(current);
            if ("builtin/generated".equals(bare)) {
                generated = true;
                break;
            }
            if ("builtin/entity".equals(bare)) {
                entity = true;
                break;
            }
            Map<String, Object> model = Snbt.asMap(json(path(current, "models", ".json")));
            if (model == null) {
                break;
            }
            found = true;
            Map<String, Object> own = Snbt.asMap(model.get("textures"));
            if (own != null) {
                for (Map.Entry<String, Object> entry : own.entrySet()) {
                    if (!textures.containsKey(entry.getKey())) {
                        textures.put(entry.getKey(), entry.getValue());
                    }
                }
            }
            if (elements == null) {
                elements = Snbt.asList(model.get("elements"));
            }
            if (gui == null) {
                Map<String, Object> display = Snbt.asMap(model.get("display"));
                gui = display == null ? null : Snbt.asMap(display.get("gui"));
            }
            if (guiLight == null) {
                guiLight = Snbt.asString(model.get("gui_light"));
            }
            current = Snbt.asString(model.get("parent"));
        }
        if (!found) {
            return null;
        }
        Transform transform = transform(gui);
        boolean sideLit = !"front".equals(guiLight);

        if (!choice.special && !entity && elements == null && generated) {
            List<String> layers = new ArrayList<String>();
            List<Integer> layerTints = new ArrayList<Integer>();
            for (int i = 0; i < 16; i++) {
                String texture = texture(textures, "#layer" + i);
                if (texture == null) {
                    if (i > 0) {
                        break;
                    }
                    continue;
                }
                layers.add(texture);
                // A generated layer's tint index is its layer number.
                layerTints.add(i < choice.tints.length ? choice.tints[i] : -1);
            }
            if (!layers.isEmpty()) {
                return new Part(layers, toArray(layerTints), null, transform, sideLit,
                        choice.tints);
            }
        }
        if (!choice.special && !entity && elements != null && !elements.isEmpty()) {
            List<Element> parsed = elements(elements, textures, deadline);
            if (!parsed.isEmpty()) {
                return new Part(null, new int[0], parsed, transform, sideLit, choice.tints);
            }
        }
        // special, builtin/entity, or a model with nothing drawable: its particle texture, flat.
        String particle = texture(textures, "#particle");
        if (particle != null) {
            return new Part(Collections.singletonList(particle), new int[] {-1}, null,
                    Transform.IDENTITY, false, new int[0]);
        }
        return null;
    }

    private List<Element> elements(List<Object> raw, Map<String, Object> textures,
            Deadline deadline) {
        List<Element> out = new ArrayList<Element>();
        for (Object element : raw) {
            if (out.size() >= MAX_ELEMENTS) {
                break;
            }
            deadline.check();
            Map<String, Object> e = Snbt.asMap(element);
            if (e == null) {
                continue;
            }
            float[] from = vector(e.get("from"), null);
            float[] to = vector(e.get("to"), null);
            Map<String, Object> faces = Snbt.asMap(e.get("faces"));
            if (from == null || to == null || faces == null) {
                continue;
            }
            float[] origin = new float[] {8, 8, 8};
            char axis = 0;
            float angle = 0;
            boolean rescale = false;
            Map<String, Object> rotation = Snbt.asMap(e.get("rotation"));
            if (rotation != null) {
                origin = vector(rotation.get("origin"), origin);
                String a = Snbt.asString(rotation.get("axis"));
                axis = a == null || a.isEmpty() ? 0 : a.toLowerCase(Locale.ROOT).charAt(0);
                angle = Snbt.asDouble(rotation.get("angle"), 0.0).floatValue();
                rescale = Boolean.TRUE.equals(Snbt.asBoolean(rotation.get("rescale"), false));
            }
            boolean shade = !Boolean.FALSE.equals(Snbt.asBoolean(e.get("shade"), true));
            List<Face> parsedFaces = new ArrayList<Face>();
            for (Map.Entry<String, Object> entry : faces.entrySet()) {
                Map<String, Object> face = Snbt.asMap(entry.getValue());
                String direction = entry.getKey().toLowerCase(Locale.ROOT);
                if (face == null || !isDirection(direction)) {
                    continue;
                }
                String texture = texture(textures, Snbt.asString(face.get("texture")));
                if (texture == null) {
                    continue;
                }
                float[] uv = null;
                List<Object> rawUv = Snbt.asList(face.get("uv"));
                if (rawUv != null && rawUv.size() == 4) {
                    uv = new float[4];
                    for (int i = 0; i < 4; i++) {
                        uv[i] = Snbt.asDouble(rawUv.get(i), 0.0).floatValue();
                    }
                }
                int faceRotation = Snbt.asInt(face.get("rotation"), 0);
                int tintIndex = Snbt.asInt(face.get("tintindex"), -1);
                parsedFaces.add(new Face(direction, uv, texture, faceRotation, tintIndex));
            }
            if (!parsedFaces.isEmpty()) {
                out.add(new Element(from, to, origin, axis, angle, rescale, shade, parsedFaces));
            }
        }
        return out;
    }

    private static boolean isDirection(String d) {
        return "up".equals(d) || "down".equals(d) || "north".equals(d) || "south".equals(d)
                || "east".equals(d) || "west".equals(d);
    }

    private static Transform transform(Map<String, Object> gui) {
        if (gui == null) {
            return Transform.IDENTITY;
        }
        float[] rotation = vector(gui.get("rotation"), new float[] {0, 0, 0});
        float[] translation = vector(gui.get("translation"), new float[] {0, 0, 0});
        float[] scale = vector(gui.get("scale"), new float[] {1, 1, 1});
        for (int i = 0; i < 3; i++) {
            translation[i] = Math.max(-80f, Math.min(80f, translation[i]));
            scale[i] = Math.max(-4f, Math.min(4f, scale[i]));
        }
        return new Transform(rotation, translation, scale);
    }

    private static float[] vector(Object raw, float[] fallback) {
        List<Object> list = Snbt.asList(raw);
        if (list == null || list.size() != 3) {
            return fallback;
        }
        float[] out = new float[3];
        for (int i = 0; i < 3; i++) {
            Double d = Snbt.asDouble(list.get(i), null);
            if (d == null || d.isNaN() || d.isInfinite()) {
                return fallback;
            }
            out[i] = d.floatValue();
        }
        return out;
    }

    /** A texture variable (or literal reference) followed to a texture reference, or null. */
    static String texture(Map<String, Object> textures, String reference) {
        String current = reference;
        for (int hop = 0; hop < MAX_TEXTURE_HOPS && current != null; hop++) {
            if (!current.startsWith("#")) {
                return current.isEmpty() ? null : current;
            }
            Object value = textures.get(current.substring(1));
            Map<String, Object> sprite = Snbt.asMap(value);
            current = sprite != null ? Snbt.asString(sprite.get("sprite")) : Snbt.asString(value);
        }
        return null;
    }

    // ── Paths ────────────────────────────────────────────────────────────────

    /** {@code ns:path} (or bare {@code path}) to {@code assets/ns/<folder>/path<suffix>}. */
    static String path(String reference, String folder, String suffix) {
        String ref = reference.trim();
        int colon = ref.indexOf(':');
        String namespace = colon < 0 ? "minecraft" : ref.substring(0, colon);
        String path = colon < 0 ? ref : ref.substring(colon + 1);
        return "assets/" + namespace.toLowerCase(Locale.ROOT) + "/" + folder + "/" + path + suffix;
    }

    /** {@code minecraft:foo} and {@code foo} to {@code foo}; another namespace is kept. */
    static String strip(String reference) {
        if (reference == null) {
            return null;
        }
        String trimmed = reference.trim();
        return trimmed.startsWith("minecraft:") ? trimmed.substring("minecraft:".length()) : trimmed;
    }

    private Object json(String path) {
        if (jsonCache.containsKey(path)) {
            return jsonCache.get(path);
        }
        Object parsed = stack.json(path);
        if (jsonCache.size() > 2048) {
            jsonCache.clear();
        }
        jsonCache.put(path, parsed);
        return parsed;
    }

    private static int[] toArray(List<Integer> values) {
        int[] out = new int[values.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = values.get(i);
        }
        return out;
    }
}
