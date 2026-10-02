package com.octant.common.model;

import com.octant.common.ContentAxis;

import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class ContentCatalog {

    private final Map<ContentAxis, Set<String>> visible = new EnumMap<>(ContentAxis.class);
    private final Set<ContentAxis> approximate = new LinkedHashSet<>();

    public void register(ContentAxis axis, Iterable<String> keys, boolean approx) {
        Set<String> set = visible.computeIfAbsent(axis, a -> new LinkedHashSet<>());
        for (String k : keys) {
            set.add(k);
        }
        if (approx) {
            approximate.add(axis);
        }
    }

    public Set<String> keysOf(ContentAxis axis) {
        return visible.getOrDefault(axis, Set.of());
    }

    public int visibleCount(ContentAxis axis) {
        return keysOf(axis).size();
    }

    public boolean isApproximate(ContentAxis axis) {
        return approximate.contains(axis);
    }

    public boolean isCurated(ContentAxis axis) {
        return visibleCount(axis) > 0 && !approximate.contains(axis);
    }
}
