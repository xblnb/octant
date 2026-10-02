package com.octant.pipeline.raw;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ContentCatalog {

    private final Map<UnitKind, LinkedHashSet<String>> keys = new LinkedHashMap<>();
    private final Map<UnitKind, Boolean> approximate = new LinkedHashMap<>();
    private final String version;

    public ContentCatalog() {
        this("content-catalog@1.0.0");
    }

    public ContentCatalog(String version) {
        this.version = version == null ? "content-catalog@1.0.0" : version;
    }

    public String version() {
        return version;
    }

    public ContentCatalog register(UnitKind kind, List<String> ids, boolean isApproximate) {
        LinkedHashSet<String> set = keys.computeIfAbsent(kind, k -> new LinkedHashSet<>());
        if (ids != null) {
            set.addAll(ids);
        }
        approximate.put(kind, isApproximate);
        return this;
    }

    public boolean hasKind(UnitKind kind) {
        return keys.containsKey(kind);
    }

    public int visibleCount(UnitKind kind) {
        Set<String> s = keys.get(kind);
        return s == null ? 0 : s.size();
    }

    public boolean isApproximate(UnitKind kind) {
        return Boolean.TRUE.equals(approximate.get(kind));
    }

    public boolean allApproximate() {
        if (keys.isEmpty()) {
            return false;
        }
        return keys.keySet().stream().allMatch(this::isApproximate);
    }

    public List<String> keysOf(UnitKind kind) {
        Set<String> s = keys.get(kind);
        return s == null ? List.of() : List.copyOf(s);
    }

    public Set<String> idWhitelist() {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (Set<String> s : keys.values()) {
            out.addAll(s);
        }
        return Collections.unmodifiableSet(out);
    }

    public List<UnitKind> kinds() {
        return List.copyOf(keys.keySet());
    }

    public int axisCount() {
        int n = 0;
        for (UnitKind k : keys.keySet()) {
            if (!keys.get(k).isEmpty()) {
                n++;
            }
        }
        return n;
    }

    public List<UnitRef> reachableUnits() {
        List<UnitRef> out = new ArrayList<>();
        for (Map.Entry<UnitKind, LinkedHashSet<String>> e : keys.entrySet()) {
            for (String id : e.getValue()) {
                out.add(new UnitRef(e.getKey(), id, isApproximate(e.getKey())));
            }
        }
        return out;
    }

    public record UnitRef(UnitKind kind, String id, boolean approximate) {
        public String unitKey() {
            return kind.wireName() + ":" + id;
        }
    }
}
