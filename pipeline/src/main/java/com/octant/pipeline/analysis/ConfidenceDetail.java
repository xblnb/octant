package com.octant.pipeline.analysis;

import com.octant.pipeline.feature.Thresholds;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

public record ConfidenceDetail(
        ConfidenceLevel level,
        double score,
        int nEff,
        long k,
        Integer nUnits,
        double inputCoverage,
        double proxyQuality,
        double afkShare,
        double sampleAdequacy,
        boolean contaminated,
        List<String> degradedBy) {

    public static final List<String> DEGRADE_CODES = List.of(
            "proxy_below_threshold", "input_substituted", "afk_share_over_30pct", "contaminated_source",
            "events_below_30", "group_downgraded_to_individual", "baseline_absolute_fallback",
            "open_session_closed_at_last_event", "truncated_stream");

    public ConfidenceDetail {
        java.util.Objects.requireNonNull(level, "confidenceDetail.level 必填");
        if (score < 0.0d || score > 1.0d) {
            throw new IllegalArgumentException("confidenceDetail.score 必须 ∈ [0,1]，实际 " + score);
        }
        if (nEff < 0) {
            throw new IllegalArgumentException("confidenceDetail.nEff 必须 ≥ 0");
        }
        if (k < 0L) {
            throw new IllegalArgumentException("confidenceDetail.k 必须 ≥ 0");
        }
        if (nUnits != null && nUnits < 0) {
            throw new IllegalArgumentException("confidenceDetail.nUnits 必须 ≥ 0");
        }
        if (inputCoverage < 0.0d || inputCoverage > 1.0d) {
            throw new IllegalArgumentException("confidenceDetail.inputCoverage 必须 ∈ [0,1]");
        }
        if (afkShare < 0.0d || afkShare > 1.0d) {
            throw new IllegalArgumentException("confidenceDetail.afkShare 必须 ∈ [0,1]");
        }
        if (sampleAdequacy < 0.0d || sampleAdequacy > 1.0d) {
            throw new IllegalArgumentException("confidenceDetail.sampleAdequacy 必须 ∈ [0,1]");
        }
        if (!PROXY_QUALITIES.contains(proxyQuality)) {
            throw new IllegalArgumentException("confidenceDetail.proxyQuality 必须取 "
                    + PROXY_QUALITIES + " 之一，实际 " + proxyQuality);
        }
        TreeSet<String> sorted = new TreeSet<>(degradedBy == null ? List.of() : degradedBy);
        for (String code : sorted) {
            if (!DEGRADE_CODES.contains(code)) {
                throw new IllegalArgumentException("非法降级码（闭集外）：" + code);
            }
        }
        degradedBy = List.copyOf(sorted);
    }

    private static final List<Double> PROXY_QUALITIES = List.of(0.0d, 0.6d, 0.85d, 1.0d);

    public static double computeScore(double sampleAdequacy, double inputCoverage,
                                      double proxyQuality, double afkShare) {
        return Thresholds.W_SAMPLE_ADEQUACY * sampleAdequacy
                + Thresholds.W_INPUT_COVERAGE * inputCoverage
                + Thresholds.W_PROXY_QUALITY * proxyQuality
                + Thresholds.W_AFK * (1.0d - afkShare);
    }

    public static double sampleAdequacy(int nEff, long k) {
        if (k <= 0L) {
            return 1.0d;
        }
        return nEff / (double) (nEff + k);
    }

    public static ConfidenceLevel levelFor(boolean suppressed, long eventCount,
                                           boolean heuristicSource, boolean estimatedModel,
                                           List<String> degradedBy) {
        if (suppressed) {
            return ConfidenceLevel.SUPPRESSED;
        }
        if (heuristicSource) {
            return ConfidenceLevel.HEURISTIC;
        }
        if (estimatedModel) {
            return ConfidenceLevel.ESTIMATED;
        }
        if (eventCount >= Thresholds.MIN_EVENTS_FOR_CONFIDENCE && degradedBy.isEmpty()) {
            return ConfidenceLevel.CERTAIN;
        }
        return ConfidenceLevel.ESTIMATED;
    }

    public static ConfidenceDetail suppressedDetail(long eventCount, int sessionCount, Integer unitCount,
                                                    long k, double inputCoverage, double proxyQuality,
                                                    double afkShare, String reasonCode) {
        int nEff = nEffOf(eventCount, sessionCount, unitCount, k);
        double adequacy = sampleAdequacy(nEff, k);
        double score = computeScore(adequacy, inputCoverage, proxyQuality, afkShare);
        List<String> degraded = new java.util.ArrayList<>();
        if (reasonCode != null && DEGRADE_CODES.contains(reasonCode.toLowerCase())) {
            degraded.add(reasonCode.toLowerCase());
        }
        if (eventCount < Thresholds.MIN_EVENTS_FOR_CONFIDENCE) {
            degraded.add("events_below_30");
        }
        return new ConfidenceDetail(ConfidenceLevel.SUPPRESSED, score, nEff, k, unitCount,
                inputCoverage, proxyQuality, afkShare, adequacy, false, degraded);
    }

    public static int nEffOf(long eventCount, int sessionCount, Integer unitCount, long k) {
        if (unitCount != null && unitCount > 0) {
            return Math.min(unitCount, (int) Math.min(Integer.MAX_VALUE, eventCount));
        }
        return (int) Math.min(Math.max(sessionCount, 1), Math.max(eventCount, 1));
    }

    public Map<String, Object> asMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("level", level.detailLevel());
        m.put("score", round2(score));
        m.put("nEff", (long) nEff);
        m.put("k", k);
        if (nUnits != null) {
            m.put("nUnits", (long) nUnits);
        }
        m.put("inputCoverage", round2(inputCoverage));
        m.put("proxyQuality", round2(proxyQuality));
        m.put("afkShare", round2(afkShare));
        m.put("sampleAdequacy", round2(sampleAdequacy));
        m.put("contaminated", contaminated);
        m.put("degradedBy", List.copyOf(degradedBy));
        return Collections.unmodifiableMap(m);
    }

    public ConfidenceDetail withExtraDegrade(String code) {
        java.util.List<String> codes = new java.util.ArrayList<>(degradedBy);
        if (!codes.contains(code)) {
            codes.add(code);
        }
        return new ConfidenceDetail(level, score, nEff, k, nUnits, inputCoverage, proxyQuality,
                afkShare, sampleAdequacy, contaminated, codes);
    }

    public static double round2(double v) {
        return Math.round(v * 100.0d) / 100.0d;
    }
}
