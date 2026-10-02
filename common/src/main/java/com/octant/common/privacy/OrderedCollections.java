package com.octant.common.privacy;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class OrderedCollections {

    private OrderedCollections() {
    }

    public static <K, V> Map<K, V> copyOf(Map<K, V> map) {
        return Collections.unmodifiableMap(
                new LinkedHashMap<>(map == null ? Map.of() : map));
    }

    public static <E> List<E> copyOf(List<E> list) {
        return list == null ? List.of() : Collections.unmodifiableList(new java.util.ArrayList<>(list));
    }

    public static <E> Set<E> copyOf(Set<E> set) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(set == null ? Set.of() : set));
    }
}
