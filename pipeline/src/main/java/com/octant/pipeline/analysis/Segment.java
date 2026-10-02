package com.octant.pipeline.analysis;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record Segment(
        String segmentId,
        String status,
        String segmentKey,
        List<String> dimensionIds,
        Map<String, Double> scores,
        double scoreTotal,
        List<String> evidenceIds,
        SampleSize sampleSize,
        ConfidenceLevel confidence,
        ConfidenceDetail confidenceDetail,
        AnalysisWindow window,
        String reasonCode) {

    public Segment {
        Objects.requireNonNull(segmentId, "segmentId 必填");
        if (!segmentId.matches("S\\d{2}")) {
            throw new IllegalArgumentException("segmentId 必须形如 S01，实际 " + segmentId);
        }
        if (!"available".equals(status) && !"suppressed".equals(status)) {
            throw new IllegalArgumentException("segment.status 只允许 available/suppressed");
        }
        dimensionIds = dimensionIds == null ? List.of() : List.copyOf(dimensionIds);
        scores = scores == null ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(scores));
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        Objects.requireNonNull(sampleSize, segmentId + ": sampleSize 必填（强制条款）");
        Objects.requireNonNull(confidence, segmentId + ": confidence 必填（强制条款）");
        Objects.requireNonNull(confidenceDetail, segmentId + ": confidenceDetail 必填（强制条款）");
        Objects.requireNonNull(window, segmentId + ": window 必填");
        if ("suppressed".equals(status)) {
            ReasonCodes.require(reasonCode);
            if (!segmentKey.contains("suppressed")) {
                throw new IllegalArgumentException(segmentId + ": 抑制段不得给出真实段名（禁止用段名暗示结论）");
            }
            if (confidence != ConfidenceLevel.SUPPRESSED) {
                throw new IllegalArgumentException(segmentId + ": 抑制段 confidence 必须为 suppressed");
            }
        } else {
            if (reasonCode != null) {
                throw new IllegalArgumentException(segmentId + ": available 段不得带 reasonCode");
            }
            if (evidenceIds.isEmpty()) {
                throw new IllegalArgumentException(segmentId + ": available 段必须带 ≥1 条证据引用");
            }
        }
    }

    public static Segment suppressed(String segmentId, String reasonCode,
                                     List<String> dimensions, SampleSize sampleSize,
                                     ConfidenceLevel confidence, ConfidenceDetail detail,
                                     AnalysisWindow window) {
        return new Segment(segmentId, "suppressed", "segment.suppressed_insufficient_evidence",
                dimensions, Map.of(), 0.0d, List.of(), sampleSize, ConfidenceLevel.SUPPRESSED,
                detail == null ? ConfidenceDetail.suppressedDetail(0, 0, 0, 5L, 0.0d, 1.0d, 0.0d, reasonCode)
                        : detail,
                window, reasonCode);
    }

    public static Segment available(String segmentId, String segmentKey, List<String> dimensions,
                                    Map<String, Double> scores, List<String> evidenceIds,
                                    SampleSize sampleSize, ConfidenceLevel confidence,
                                    ConfidenceDetail detail, AnalysisWindow window) {
        double total = 0.0d;
        int n = 0;
        for (double v : scores.values()) {
            total += v;
            n++;
        }
        double mean = n == 0 ? 0.0d : total / n;
        return new Segment(segmentId, "available", segmentKey, dimensions, scores,
                Math.round(mean * 1000.0d) / 1000.0d, evidenceIds, sampleSize, confidence, detail,
                window, null);
    }

    public Map<String, Object> asMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("segmentId", segmentId);
        m.put("status", status);
        m.put("segmentKey", segmentKey);
        m.put("dimensionIds", dimensionIds);
        if (!scores.isEmpty()) {
            m.put("scores", scores);
            m.put("scoreTotal", scoreTotal);
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
        return m;
    }
}
