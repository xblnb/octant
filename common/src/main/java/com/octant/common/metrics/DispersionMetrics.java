package com.octant.common.metrics;

import com.octant.common.Confidence;
import com.octant.common.ContentAxis;
import com.octant.common.MetricResult;
import com.octant.common.SuppressionPolicy;
import com.octant.common.SuppressionReason;
import com.octant.common.model.ContentCatalog;
import com.octant.common.model.ContentUnit;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DispersionMetrics {

    private final long totalActiveSeconds;
    private final Map<ContentAxis, Long> axisSeconds = new EnumMap<>(ContentAxis.class);
    private final Map<ContentAxis, List<Long>> axisSessionSeconds = new EnumMap<>(ContentAxis.class);
    private final int visibleUnitCount;

    public DispersionMetrics(List<ContentUnit> units, ContentCatalog catalog, long totalActiveSeconds) {
        this.totalActiveSeconds = totalActiveSeconds;
        int visible = 0;
        for (ContentAxis axis : ContentAxis.values()) {
            if (catalog.visibleCount(axis) >= SuppressionPolicy.MIN_CONTENT_UNIT) {
                visible++;
            }
        }
        this.visibleUnitCount = visible;

        if (units != null) {
            for (ContentUnit u : units) {
                axisSeconds.merge(u.axis(), u.activeSeconds(), Long::sum);
                axisSessionSeconds.computeIfAbsent(u.axis(), a -> new ArrayList<>()).add(u.activeSeconds());
            }
        }
    }

    private List<ContentAxis> unsuppressedAxes() {
        List<ContentAxis> axes = new ArrayList<>();
        for (ContentAxis axis : ContentAxis.values()) {
            if (axisSeconds.getOrDefault(axis, 0L) > 0) {
                axes.add(axis);
            }
        }
        return axes;
    }

    private List<Double> durationsOf(List<ContentAxis> axes) {
        List<Double> out = new ArrayList<>(axes.size());
        for (ContentAxis a : axes) {
            out.add(axisSeconds.getOrDefault(a, 0L).doubleValue());
        }
        return out;
    }

    public Map<String, MetricResult> compute() {
        Map<String, MetricResult> out = new LinkedHashMap<>();
        List<ContentAxis> axes = unsuppressedAxes();

        if (totalActiveSeconds < SuppressionPolicy.MIN_ACTIVE_S) {
            String detail = SuppressionReason.INSUFFICIENT_ACTIVE_TIME
                    .describe(totalActiveSeconds, SuppressionPolicy.MIN_ACTIVE_S);
            out.put("M5a", MetricResult.suppressed("M5a", SuppressionReason.INSUFFICIENT_ACTIVE_TIME, detail));
            out.put("M5b", MetricResult.suppressed("M5b", SuppressionReason.INSUFFICIENT_ACTIVE_TIME, detail));
            return com.octant.common.privacy.OrderedCollections.copyOf(out);
        }
        if (axes.size() < SuppressionPolicy.MIN_CONTENT_UNIT) {
            String detail = SuppressionReason.INSUFFICIENT_AXIS_COUNT
                    .describeCount(axes.size(), SuppressionPolicy.MIN_CONTENT_UNIT);
            out.put("M5a", MetricResult.suppressed("M5a", SuppressionReason.INSUFFICIENT_AXIS_COUNT, detail));
            out.put("M5b", MetricResult.suppressed("M5b", SuppressionReason.INSUFFICIENT_AXIS_COUNT, detail));
            return com.octant.common.privacy.OrderedCollections.copyOf(out);
        }

        boolean premature = totalActiveSeconds < SuppressionPolicy.DISPERSION_PREMATURE_S;
        List<Double> durations = durationsOf(axes);

        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("K", axes.size());
        extras.put("visibleUnits", visibleUnitCount);
        extras.put("premature", premature);
        extras.put("axisLabels", axes.stream().map(ContentAxis::label).toList());
        if (premature) {
            extras.put("verdictSuppressed", true);
            extras.put("verdictReason", SuppressionReason.PREMATURE_PROGRESSION.name());
        }

        out.put("M5a", MetricResult.of("M5a", round(Stats.normalizedShannonEntropy(durations)),
                Confidence.B, extras));
        out.put("M5b", MetricResult.of("M5b", round(Stats.gini(durations)), Confidence.B, extras));
        out.put("M5c", m5c(axes));
        out.put("M5d", m5d(axes));
        return com.octant.common.privacy.OrderedCollections.copyOf(out);
    }

    private MetricResult m5c(List<ContentAxis> axes) {
        ContentAxis maxAxis = null;
        long maxSeconds = -1;
        long sum = 0;
        for (ContentAxis a : axes) {
            long s = axisSeconds.getOrDefault(a, 0L);
            sum += s;
            if (s > maxSeconds) {
                maxSeconds = s;
                maxAxis = a;
            }
        }
        if (maxAxis == null || sum == 0) {
            return MetricResult.suppressed("M5c", SuppressionReason.INSUFFICIENT_AXIS_COUNT,
                    "无任何轴具备实质时长");
        }
        double pMax = maxSeconds / (double) sum;
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("axis", maxAxis.label());
        extras.put("pMax", round(pMax));
        extras.put("dominantSystem", pMax > 0.60d);

        List<Long> sessionsOfMax = axisSessionSeconds.getOrDefault(maxAxis, List.of());
        List<Double> sessionMinutes = new ArrayList<>();
        for (Long s : sessionsOfMax) {
            sessionMinutes.add(s / 60.0d);
        }
        if (!sessionMinutes.isEmpty()) {
            double p50 = Stats.median(sessionMinutes);
            extras.put("p50SessionMin", round(p50));
            double maxAxisP50Share = p50 / Math.max(1.0d, totalAxisP50(axes));
            extras.put("p50Share", round(maxAxisP50Share));
            extras.put("robustConflict", maxAxisP50Share < 0.5d && pMax > 0.60d);
        }
        return MetricResult.of("M5c", round(pMax), Confidence.B, extras);
    }

    private double totalAxisP50(List<ContentAxis> axes) {
        double sum = 0;
        for (ContentAxis a : axes) {
            List<Long> s = axisSessionSeconds.getOrDefault(a, List.of());
            if (s.isEmpty()) {
                continue;
            }
            List<Double> minutes = new ArrayList<>();
            for (Long v : s) {
                minutes.add(v / 60.0d);
            }
            sum += Stats.median(minutes);
        }
        return sum;
    }

    private MetricResult m5d(List<ContentAxis> axes) {
        ContentAxis minAxis = null;
        long minSeconds = Long.MAX_VALUE;
        for (ContentAxis a : axes) {
            long s = axisSeconds.getOrDefault(a, 0L);
            if (s < minSeconds) {
                minSeconds = s;
                minAxis = a;
            }
        }
        if (minAxis == null) {
            return MetricResult.suppressed("M5d", SuppressionReason.INSUFFICIENT_AXIS_COUNT,
                    "无任何轴具备实质时长");
        }
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("axis", minAxis.label());
        extras.put("axisSeconds", minSeconds);
        return MetricResult.of("M5d", minSeconds, Confidence.B, extras);
    }

    private static double round(double v) {
        return Math.round(v * 10_000.0d) / 10_000.0d;
    }
}
