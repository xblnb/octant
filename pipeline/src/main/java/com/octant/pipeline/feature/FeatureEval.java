package com.octant.pipeline.feature;

import java.util.List;
import java.util.Map;

public record FeatureEval(
        String metricId,
        String featureId,
        String expr,
        Double value,
        Map<String, Double> quantiles,
        Map<String, Double> series,
        Map<String, Double> metadata,
        String namedKey,
        double numerator,
        double denominator,
        String denominatorSource,
        Integer unitCount,
        boolean heuristic,
        List<String> dataQualityFlags,
        String zeroCode,
        String zeroReason) {

    public static FeatureEval scalar(String metricId, String featureId, String expr, double value) {
        return new FeatureEval(metricId, featureId, expr, value, null, null, null, null,
                0.0d, 0.0d, "-", null, false, List.of(), null, null);
    }

    private FeatureEval copy(Double value, Map<String, Double> quantiles, Map<String, Double> series,
                             Map<String, Double> metadata, String namedKey, double numerator,
                             double denominator, String denominatorSource, Integer unitCount,
                             boolean heuristic, List<String> flags, String zeroCode,
                             String zeroReason) {
        return new FeatureEval(metricId, featureId, expr, value, quantiles, series, metadata,
                namedKey, numerator, denominator, denominatorSource, unitCount, heuristic, flags,
                zeroCode, zeroReason);
    }

    public FeatureEval withEvidence(double num, double den, String source) {
        return copy(value, quantiles, series, metadata, namedKey, num, den, source, unitCount,
                heuristic, dataQualityFlags, zeroCode, zeroReason);
    }

    public FeatureEval withUnitCount(Integer units) {
        return copy(value, quantiles, series, metadata, namedKey, numerator, denominator,
                denominatorSource, units, heuristic, dataQualityFlags, zeroCode, zeroReason);
    }

    public FeatureEval withHeuristic(boolean flag) {
        return copy(value, quantiles, series, metadata, namedKey, numerator, denominator,
                denominatorSource, unitCount, flag, dataQualityFlags, zeroCode, zeroReason);
    }

    public FeatureEval withDataQualityFlags(List<String> flags) {
        return copy(value, quantiles, series, metadata, namedKey, numerator, denominator,
                denominatorSource, unitCount, heuristic, flags, zeroCode, zeroReason);
    }

    public FeatureEval withZeroCode(String code, String reason) {
        return copy(value, quantiles, series, metadata, namedKey, numerator, denominator,
                denominatorSource, unitCount, heuristic, dataQualityFlags, code, reason);
    }

    public FeatureEval withQuantiles(Map<String, Double> q) {
        return copy(value, q, series, metadata, namedKey, numerator, denominator, denominatorSource,
                unitCount, heuristic, dataQualityFlags, zeroCode, zeroReason);
    }

    public FeatureEval withSeries(Map<String, Double> s) {
        return copy(value, quantiles, s, metadata, namedKey, numerator, denominator,
                denominatorSource, unitCount, heuristic, dataQualityFlags, zeroCode, zeroReason);
    }

    public FeatureEval withMetadata(Map<String, Double> m) {
        return copy(value, quantiles, series, m, namedKey, numerator, denominator, denominatorSource,
                unitCount, heuristic, dataQualityFlags, zeroCode, zeroReason);
    }

    public FeatureEval withNamedKey(String key) {
        return copy(value, quantiles, series, metadata, key, numerator, denominator,
                denominatorSource, unitCount, heuristic, dataQualityFlags, zeroCode, zeroReason);
    }

    public FeatureEval withValue(Double v) {
        return copy(v, quantiles, series, metadata, namedKey, numerator, denominator,
                denominatorSource, unitCount, heuristic, dataQualityFlags, zeroCode, zeroReason);
    }

    public String outputType() {
        if (series != null) {
            return "series";
        }
        if (quantiles != null) {
            return "quantiles";
        }
        if (namedKey != null) {
            return "named";
        }
        return "scalar";
    }
}
