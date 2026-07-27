package com.heimdall.core.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Defensive-copy helpers for the immutable response models. */
public final class Lists {

    private Lists() {
    }

    /**
     * An unmodifiable copy of {@code values}, or an empty list when it is {@code null}.
     *
     * <p>Copy <em>and</em> wrap: wrapping alone leaves the caller holding a handle that can still
     * mutate what an "immutable" model returns.
     */
    public static <T> List<T> copyOf(List<T> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<T>(values));
    }
}
