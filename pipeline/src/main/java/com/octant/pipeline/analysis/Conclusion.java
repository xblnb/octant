package com.octant.pipeline.analysis;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record Conclusion(
        String conclusionId,
        MetricStatus status,
        List<String> metricIds,
        List<String> featureIds,
        List<String> ruleIds,
        String statementKey,
        Map<String, Object> statementArgs,
        SampleSize sampleSize,
        ConfidenceLevel confidence,
        ConfidenceDetail confidenceDetail,
        AnalysisWindow window,
        List<String> evidenceIds,
        List<String> chartIds,
        String reasonCode,
        List<String> limitations,
        List<String> alternativeExplanations) {

    public Conclusion {
        Objects.requireNonNull(conclusionId, "conclusionId 必填");
        if (!conclusionId.matches("C\\d{3}")) {
            throw new IllegalArgumentException("conclusionId 必须形如 C001，实际 " + conclusionId);
        }
        Objects.requireNonNull(status, conclusionId + ": status 必填");
        if (metricIds == null || metricIds.isEmpty()) {
            throw new IllegalArgumentException(conclusionId + ": metricIds 必须 ≥ 1");
        }
        if (featureIds == null || featureIds.isEmpty()) {
            throw new IllegalArgumentException(conclusionId + ": featureIds 必须 ≥ 1");
        }
        if (ruleIds == null || ruleIds.isEmpty()) {
            throw new IllegalArgumentException(conclusionId + ": ruleIds 必须 ≥ 1");
        }
        Objects.requireNonNull(sampleSize, conclusionId + ": sampleSize 必填（强制条款）");
        Objects.requireNonNull(confidence, conclusionId + ": confidence 必填（强制条款）");
        Objects.requireNonNull(confidenceDetail, conclusionId + ": confidenceDetail 必填（强制条款）");
        Objects.requireNonNull(window, conclusionId + ": window 必填");
        if (statementKey == null || statementKey.isEmpty() || !statementKey.matches("[A-Za-z0-9_.]+")) {
            throw new IllegalArgumentException(conclusionId
                    + ": statementKey 必须是语言键（仅字母数字点下划线），禁止自然语言直写： " + statementKey);
        }
        if (confidenceDetail.k() != sampleSize.minRequired()) {
            throw new IllegalArgumentException(conclusionId + ": confidenceDetail.k 必须等于 sampleSize.minRequired");
        }
        metricIds = List.copyOf(metricIds);
        featureIds = List.copyOf(featureIds);
        ruleIds = List.copyOf(ruleIds);
        statementArgs = statementArgs == null ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(statementArgs));
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        chartIds = chartIds == null ? List.of() : List.copyOf(chartIds);
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
        alternativeExplanations = alternativeExplanations == null
                ? List.of() : List.copyOf(alternativeExplanations);

        if (status == MetricStatus.AVAILABLE || status == MetricStatus.ZERO) {
            if (evidenceIds.isEmpty()) {
                throw new IllegalArgumentException(conclusionId
                        + ": available 结论必须带 ≥1 条证据引用（HIG-REP-08 缺一不输出）");
            }
            if (chartIds.isEmpty()) {
                throw new IllegalArgumentException(conclusionId
                        + ": available 结论必须带 ≥1 个图表引用（HIG-REP-08 缺一不输出）");
            }
        } else {
            ReasonCodes.require(reasonCode);
            if (confidence != ConfidenceLevel.SUPPRESSED) {
                throw new IllegalArgumentException(conclusionId + ": status ≠ available ⇒ confidence 必须为 suppressed");
            }
            for (Object v : statementArgs.values()) {
                if (v instanceof Number) {
                    throw new IllegalArgumentException(conclusionId + ": 被抑制的结论不得携带数值型 statementArgs");
                }
            }
        }
        if (confidence == ConfidenceLevel.HEURISTIC && alternativeExplanations.isEmpty()) {
            throw new IllegalArgumentException(conclusionId + ": confidence=heuristic ⇒ 必须给出替代解释");
        }
    }

    public Map<String, Object> asMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("conclusionId", conclusionId);
        m.put("status", status.wireName());
        m.put("metricIds", metricIds);
        m.put("featureIds", featureIds);
        m.put("ruleIds", ruleIds);
        m.put("statementKey", statementKey);
        if (!statementArgs.isEmpty()) {
            m.put("statementArgs", statementArgs);
        }
        m.put("sampleSize", sampleSize.asMap());
        m.put("confidence", confidence.wireName());
        m.put("confidenceDetail", confidenceDetail.asMap());
        m.put("window", window.asMap());
        if (!evidenceIds.isEmpty()) {
            m.put("evidenceIds", evidenceIds);
        }
        if (reasonCode != null) {
            m.put("reasonCode", reasonCode);
        }
        if (!chartIds.isEmpty()) {
            m.put("chartIds", chartIds);
        }
        if (!limitations.isEmpty()) {
            m.put("limitations", limitations);
        }
        if (!alternativeExplanations.isEmpty()) {
            m.put("alternativeExplanations", alternativeExplanations);
        }
        return m;
    }
}
