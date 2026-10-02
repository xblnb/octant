package com.octant.common.engine;

import com.octant.common.MetricResult;

import java.util.List;
import java.util.Map;

public final class AnalysisReport {

    private final int sessionCount;
    private final long totalActiveSeconds;
    private final long totalWallSeconds;
    private final Map<String, MetricResult> metrics;
    private final List<String> suppressed;
    private final List<String> warnings;

    AnalysisReport(int sessionCount, long totalActiveSeconds, long totalWallSeconds,
                   Map<String, MetricResult> metrics, List<String> suppressed, List<String> warnings) {
        this.sessionCount = sessionCount;
        this.totalActiveSeconds = totalActiveSeconds;
        this.totalWallSeconds = totalWallSeconds;
        this.metrics = orderedCopy(metrics);
        this.suppressed = List.copyOf(suppressed);
        this.warnings = List.copyOf(warnings);
    }

    static <K, V> Map<K, V> orderedCopy(Map<K, V> map) {
        return com.octant.common.privacy.OrderedCollections.copyOf(map);
    }

    public int sessionCount() {
        return sessionCount;
    }

    public long totalActiveSeconds() {
        return totalActiveSeconds;
    }

    public long totalWallSeconds() {
        return totalWallSeconds;
    }

    public Map<String, MetricResult> metrics() {
        return metrics;
    }

    public List<String> suppressed() {
        return suppressed;
    }

    public List<String> warnings() {
        return warnings;
    }

    public MetricResult metric(String id) {
        MetricResult r = metrics.get(id);
        if (r == null) {
            throw new IllegalArgumentException("未知指标 id: " + id);
        }
        return r;
    }
}
