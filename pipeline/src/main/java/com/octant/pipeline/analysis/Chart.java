package com.octant.pipeline.analysis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record Chart(
        String chartId,
        String form,
        List<String> metricIds,
        String titleKey,
        String unit,
        Map<String, Object> axes,
        SampleSize sampleSize,
        ConfidenceLevel confidence,
        ConfidenceDetail confidenceDetail,
        AnalysisWindow window,
        double coverageRatio,
        int missingPoints,
        String tableRef,
        List<Map<String, Object>> data,
        String suppressionReason,
        List<String> seriesKeys,
        String seriesLabel,
        String readingKey,
        List<String> evidenceIds) {

    public static final List<String> FORMS = List.of(
            "C1", "C2", "C3", "C4", "C5", "C6", "C7", "C8", "C9", "C10", "C11", "C12", "C13",
            "C14", "C15", "C16", "C17");

    public Chart {
        Objects.requireNonNull(chartId, "chartId 必填");
        if (!chartId.matches("C[0-9]{1,2}")) {
            throw new IllegalArgumentException("chartId 必须形如 C1 或 C12，实际 " + chartId);
        }
        if (form == null || !FORMS.contains(form)) {
            throw new IllegalArgumentException(chartId + ": form 必须 ∈ " + FORMS + "（不在表中即判定失败）");
        }
        if (metricIds == null || metricIds.isEmpty()) {
            throw new IllegalArgumentException(chartId + ": metricIds 必填");
        }
        if (titleKey == null || titleKey.isEmpty()) {
            throw new IllegalArgumentException(chartId + ": titleKey 必填（HIG-CHT-06）");
        }
        if (unit == null || unit.isEmpty()) {
            throw new IllegalArgumentException(chartId + ": unit 必填（HIG-CHT-06）");
        }
        Objects.requireNonNull(sampleSize, chartId + ": sampleSize 必填（HIG-RPT-04 的样本量 n）");
        Objects.requireNonNull(confidence, chartId + ": confidence 必填");
        Objects.requireNonNull(confidenceDetail, chartId + ": confidenceDetail 必填");
        Objects.requireNonNull(window, chartId + ": window 必填（HIG-RPT-04 的时间窗）");
        if (coverageRatio < 0.0d || coverageRatio > 1.0d) {
            throw new IllegalArgumentException(chartId + ": coverageRatio 必须 ∈ [0,1]");
        }
        if (missingPoints < 0) {
            throw new IllegalArgumentException(chartId + ": missingPoints 不得为负");
        }
        if (tableRef == null || tableRef.isEmpty()) {
            throw new IllegalArgumentException(chartId + ": tableRef 必填（HIG-A11Y-01）");
        }
        metricIds = List.copyOf(metricIds);
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        if ("C11".equals(form)) {
            if (data != null) {
                throw new IllegalArgumentException(chartId
                        + ": form=C11 时 data 必须物理缺失（禁止用全 0 数据代替）");
            }
            if (suppressionReason == null || suppressionReason.isEmpty()) {
                throw new IllegalArgumentException(chartId + ": form=C11 必须给出 suppressionReason（原因码 + 所需样本量）");
            }
            if (confidence != ConfidenceLevel.SUPPRESSED) {
                throw new IllegalArgumentException(chartId + ": form=C11 ⇒ confidence 必须为 suppressed");
            }
        } else {
            if (data == null || data.isEmpty()) {
                throw new IllegalArgumentException(chartId + ": form≠C11 时 data 必填");
            }
            if (suppressionReason != null) {
                throw new IllegalArgumentException(chartId + ": 非 C11 图表不得给出 suppressionReason");
            }
        }
        data = data == null ? null : List.copyOf(data);
    }

    public boolean suppressed() {
        return "C11".equals(form);
    }

    public Map<String, Object> asMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("chartId", chartId);
        m.put("form", form);
        m.put("metricIds", metricIds);
        m.put("titleKey", titleKey);
        m.put("unit", unit);
        if (axes != null && !axes.isEmpty()) {
            m.put("axes", axes);
        }
        m.put("sampleSize", sampleSize.asMap());
        m.put("confidence", confidence.wireName());
        m.put("confidenceDetail", confidenceDetail.asMap());
        m.put("window", window.asMap());
        m.put("coverageRatio", ConfidenceDetail.round2(coverageRatio));
        m.put("missingPoints", (long) missingPoints);
        m.put("tableRef", tableRef);
        if (data != null) {
            m.put("data", data);
        }
        if (suppressionReason != null) {
            m.put("suppressionReason", suppressionReason);
        }
        if (seriesKeys != null && !seriesKeys.isEmpty()) {
            m.put("seriesKeys", seriesKeys);
        }
        if (seriesLabel != null) {
            m.put("seriesLabel", seriesLabel);
        }
        if (readingKey != null) {
            m.put("readingKey", readingKey);
        }
        if (!evidenceIds.isEmpty()) {
            m.put("evidenceIds", evidenceIds);
        }
        return m;
    }

    public static List<Map<String, Object>> points(List<String> labels, List<Double> values) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < labels.size(); i++) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("x", labels.get(i));
            Double v = i < values.size() ? values.get(i) : null;
            if (v != null) {
                p.put("y", v);
            }
            out.add(p);
        }
        return out;
    }
}
