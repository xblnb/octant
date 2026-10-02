package com.octant.common.privacy;

import com.octant.common.model.Json;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

public final class RedactionReport {

    private final Map<String, Entry> entries = new TreeMap<>();

    private final java.util.Set<String> unregisteredRedacted = new TreeSet<>();

    public record Entry(String rawField, String transformSpecId, long count, boolean dropped) {
    }

    public void record(String rawField, FieldRegistry.Transform transform, boolean dropped) {
        if (rawField == null || rawField.isBlank()) {
            throw new com.octant.common.model.ContractException("rawField 不得为空");
        }
        if (transform == null) {
            throw new com.octant.common.model.ContractException("transform 不得为空");
        }
        entries.merge(rawField + "\u0000" + transform.specId(),
                new Entry(rawField, transform.specId(), 1L, dropped),
                (a, b) -> new Entry(a.rawField(), a.transformSpecId(), a.count() + b.count(), a.dropped()));
    }

    public void recordUnregistered(String rawField) {
        if (rawField == null || rawField.isBlank()) {
            throw new com.octant.common.model.ContractException("rawField 不得为空");
        }
        unregisteredRedacted.add(rawField);
    }

    public java.util.List<String> unregisteredRedactedFieldPaths() {
        return java.util.List.copyOf(unregisteredRedacted);
    }

    public boolean hasUnregisteredRedactions() {
        return !unregisteredRedacted.isEmpty();
    }

    public Map<String, Long> countByTransform() {
        Map<String, Long> out = new TreeMap<>();
        for (Entry e : entries.values()) {
            out.merge(e.transformSpecId(), e.count(), Long::sum);
        }
        return Collections.unmodifiableMap(out);
    }

    public java.util.List<String> fieldPaths() {
        return java.util.List.copyOf(new TreeSet<>(entries.values().stream()
                .map(Entry::rawField).toList()));
    }

    public java.util.List<String> droppedFieldPaths() {
        return java.util.List.copyOf(new TreeSet<>(entries.values().stream()
                .filter(Entry::dropped).map(Entry::rawField).toList()));
    }

    public int size() {
        return entries.size();
    }

    public String toJson(java.util.Collection<String> s3AbsentFields, int s2Required, int s2Confirmed) {
        if (s2Required != s2Confirmed) {
            throw new com.octant.common.model.ContractException(
                    "s2Confirmations 的 required 必须等于 confirmed：required=" + s2Required
                            + " confirmed=" + s2Confirmed);
        }
        for (String id : s3AbsentFields) {
            if (FieldRegistry.byFieldId(id).isEmpty()) {
                throw new com.octant.common.model.ContractException(
                        "s3AbsentFields 的元素必须是 field-registry 的字段 id：" + id);
            }
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("registryId", "field-registry");

        java.util.List<Map<String, Object>> applied = new java.util.ArrayList<>();
        for (Entry e : entries.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("fieldPath", e.rawField());
            m.put("transform", e.transformSpecId());
            m.put("count", e.count());
            applied.add(m);
        }
        root.put("applied", applied);
        root.put("transformCounts", countByTransform());
        root.put("droppedFieldPaths", droppedFieldPaths());
        root.put("unregisteredRedactedFields", unregisteredRedactedFieldPaths());
        root.put("unregisteredRedactedCount", (long) unregisteredRedacted.size());

        Map<String, Object> s3 = new LinkedHashMap<>();
        s3.put("s3AbsentFields", java.util.List.copyOf(new TreeSet<>(s3AbsentFields)));
        root.put("redaction", s3);

        Map<String, Object> s2 = new LinkedHashMap<>();
        s2.put("required", s2Required);
        s2.put("confirmed", s2Confirmed);
        s3.put("s2Confirmations", s2);
        return Json.encode(root);
    }

    public static boolean containsNoRawValues(String reportJson, java.util.Collection<String> rawCandidates) {
        for (String raw : rawCandidates) {
            if (raw != null && !raw.isBlank() && reportJson.contains(raw)) {
                return false;
            }
        }
        return true;
    }

    public long countOf(String transformSpecId) {
        return countByTransform().getOrDefault(transformSpecId, 0L);
    }
}
