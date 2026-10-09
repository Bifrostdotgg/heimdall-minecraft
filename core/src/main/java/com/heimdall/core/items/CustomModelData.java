package com.heimdall.core.items;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The {@code custom_model_data} an item carries, in its 1.21.4+ shape whatever shape it arrived in.
 *
 * <p>Before 1.21.4 it was one integer (the legacy {@code CustomModelData} tag, then the
 * {@code custom_model_data} component). From 1.21.4 it is four lists, {@code floats}, {@code flags},
 * {@code strings} and {@code colors}, each indexed by the item-model definition that reads it. A
 * legacy integer is exactly {@code floats: [n]}: that is how vanilla's own migration maps it, and it
 * is what lets one model resolver serve both the {@code range_dispatch} on {@code custom_model_data}
 * that ItemsAdder generates and a pre-1.21.4 pack's {@code overrides} predicate.
 *
 * <p>Immutable. Each list is capped, because the value is player-influenced.
 */
public final class CustomModelData {

    /** No custom model data at all. */
    public static final CustomModelData NONE = new CustomModelData(
            Collections.<Float>emptyList(), Collections.<Boolean>emptyList(),
            Collections.<String>emptyList(), Collections.<Integer>emptyList());

    static final int MAX_ENTRIES = 64;

    private final List<Float> floats;
    private final List<Boolean> flags;
    private final List<String> strings;
    private final List<Integer> colors;

    public CustomModelData(List<Float> floats, List<Boolean> flags, List<String> strings,
            List<Integer> colors) {
        this.floats = freeze(floats);
        this.flags = freeze(flags);
        this.strings = freeze(strings);
        this.colors = freeze(colors);
    }

    /** The legacy single integer. */
    public static CustomModelData ofLegacy(int value) {
        return new CustomModelData(Collections.singletonList((float) value),
                Collections.<Boolean>emptyList(), Collections.<String>emptyList(),
                Collections.<Integer>emptyList());
    }

    /**
     * Reads the component from its parsed SNBT: a number (the pre-1.21.4 integer) or the 1.21.4+
     * compound. Anything else is {@link #NONE}.
     */
    public static CustomModelData read(Object value) {
        if (value instanceof Number) {
            return ofLegacy(((Number) value).intValue());
        }
        Map<String, Object> map = Snbt.asMap(value);
        if (map == null) {
            return NONE;
        }
        List<Float> floats = new ArrayList<Float>();
        for (Object element : list(map.get("floats"))) {
            Double d = Snbt.asDouble(element, null);
            if (d != null) {
                floats.add(d.floatValue());
            }
        }
        List<Boolean> flags = new ArrayList<Boolean>();
        for (Object element : list(map.get("flags"))) {
            Boolean b = Snbt.asBoolean(element, null);
            if (b != null) {
                flags.add(b);
            }
        }
        List<String> strings = new ArrayList<String>();
        for (Object element : list(map.get("strings"))) {
            if (element instanceof String) {
                strings.add((String) element);
            }
        }
        List<Integer> colors = new ArrayList<Integer>();
        Object rawColors = map.get("colors");
        if (rawColors instanceof int[]) {
            for (int c : (int[]) rawColors) {
                colors.add(c);
            }
        } else {
            for (Object element : list(rawColors)) {
                Integer c = colorOf(element);
                if (c != null) {
                    colors.add(c);
                }
            }
        }
        return new CustomModelData(floats, flags, strings, colors);
    }

    /** A colour written as an int, or as {@code [r, g, b]} floats in 0..1. */
    public static Integer colorOf(Object element) {
        if (element instanceof Number) {
            return ((Number) element).intValue() & 0xFFFFFF;
        }
        List<Object> rgb = Snbt.asList(element);
        if (rgb != null && rgb.size() >= 3) {
            int r = channel(rgb.get(0));
            int g = channel(rgb.get(1));
            int b = channel(rgb.get(2));
            return (r << 16) | (g << 8) | b;
        }
        return null;
    }

    private static int channel(Object value) {
        Double d = Snbt.asDouble(value, 0.0);
        return Math.max(0, Math.min(255, (int) Math.round(d * 255.0)));
    }

    private static List<Object> list(Object value) {
        if (value instanceof int[]) {
            List<Object> out = new ArrayList<Object>();
            for (int i : (int[]) value) {
                out.add(i);
            }
            return out;
        }
        List<Object> list = Snbt.asList(value);
        return list == null ? Collections.emptyList() : list;
    }

    private static <T> List<T> freeze(List<T> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        List<T> copy = new ArrayList<T>(values.subList(0, Math.min(values.size(), MAX_ENTRIES)));
        return Collections.unmodifiableList(copy);
    }

    public List<Float> floats() {
        return floats;
    }

    public List<Boolean> flags() {
        return flags;
    }

    public List<String> strings() {
        return strings;
    }

    public List<Integer> colors() {
        return colors;
    }

    public boolean isEmpty() {
        return floats.isEmpty() && flags.isEmpty() && strings.isEmpty() && colors.isEmpty();
    }

    /** {@code floats[index]}, or {@code null} when absent (a model then takes its fallback). */
    public Float floatAt(int index) {
        return index >= 0 && index < floats.size() ? floats.get(index) : null;
    }

    /** {@code flags[index]}, absent reading as {@code false}, as vanilla's condition does. */
    public boolean flagAt(int index) {
        return index >= 0 && index < flags.size() && flags.get(index);
    }

    public String stringAt(int index) {
        return index >= 0 && index < strings.size() ? strings.get(index) : null;
    }

    public Integer colorAt(int index) {
        return index >= 0 && index < colors.size() ? colors.get(index) : null;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof CustomModelData)) {
            return false;
        }
        CustomModelData that = (CustomModelData) other;
        return floats.equals(that.floats) && flags.equals(that.flags)
                && strings.equals(that.strings) && colors.equals(that.colors);
    }

    @Override
    public int hashCode() {
        return ((floats.hashCode() * 31 + flags.hashCode()) * 31 + strings.hashCode()) * 31
                + colors.hashCode();
    }

    @Override
    public String toString() {
        return "CustomModelData{floats=" + floats + ", flags=" + flags + ", strings=" + strings
                + ", colors=" + colors + "}";
    }
}
