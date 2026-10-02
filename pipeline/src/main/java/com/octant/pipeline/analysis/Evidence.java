package com.octant.pipeline.analysis;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record Evidence(
        String evidenceId,
        String ruleId,
        List<String> featureIds,
        double numerator,
        double denominator,
        String denominatorSource,
        Map<String, Double> statistic,
        String quantileMethod,
        String uncertainty,
        AnalysisWindow window,
        Map<String, Object> dataQuality,
        List<String> eventIds,
        ConfidenceLevel confidence,
        MetricStatus status,
        String reasonCode) {

    public Evidence {
        Objects.requireNonNull(evidenceId, "evidenceId 必填");
        if (!evidenceId.matches("E\\d{3}")) {
            throw new IllegalArgumentException("evidenceId 必须形如 E001，实际 " + evidenceId);
        }
        Objects.requireNonNull(ruleId, evidenceId + ": ruleId 必填（HIG E1）");
        if (featureIds == null || featureIds.isEmpty()) {
            throw new IllegalArgumentException(evidenceId + ": featureIds 必填（HIG E2）");
        }
        if (denominator < 0.0d) {
            throw new IllegalArgumentException(evidenceId + ": denominator 不得为负（HIG E3）");
        }
        if (statistic == null || !statistic.containsKey("median")) {
            throw new IllegalArgumentException(evidenceId + ": statistic 必须给出 median（HIG E4）");
        }
        if (uncertainty == null || uncertainty.isEmpty()) {
            throw new IllegalArgumentException(evidenceId + ": uncertainty 必填（HIG E5）");
        }
        Objects.requireNonNull(window, evidenceId + ": window 必填（HIG E6）");
        Objects.requireNonNull(dataQuality, evidenceId + ": dataQuality 必填（HIG E7）");
        Objects.requireNonNull(confidence, evidenceId + ": confidence 必填（HIG E8）");
        Objects.requireNonNull(status, evidenceId + ": status 必填（HIG E9）");
        featureIds = List.copyOf(featureIds);
        eventIds = eventIds == null ? List.of() : List.copyOf(eventIds);
        statistic = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(statistic));
    }

    public Map<String, Object> asMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("evidenceId", evidenceId);
        m.put("ruleId", ruleId);
        m.put("featureIds", featureIds);
        m.put("numerator", numerator);
        m.put("denominator", denominator);
        m.put("denominatorSource", denominatorSource);
        m.put("statistic", statistic);
        m.put("quantileMethod", quantileMethod);
        m.put("uncertainty", uncertainty);
        m.put("window", window.asMap());
        m.put("dataQuality", dataQuality);
        if (!eventIds.isEmpty()) {
            m.put("eventIds", eventIds);
        }
        m.put("confidence", confidence.wireName());
        m.put("status", status.wireName());
        if (reasonCode != null) {
            m.put("reasonCode", reasonCode);
        }
        return m;
    }
}
