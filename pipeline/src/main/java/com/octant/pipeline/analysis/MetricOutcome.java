package com.octant.pipeline.analysis;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record MetricOutcome(
        String metricId,
        String featureId,
        String featureExpr,
        String outputType,
        String unit,
        MetricStatus status,
        Object value,
        Map<String, Double> quantiles,
        Map<String, Double> series,
        String namedKey,
        List<String> namedKeys,
        String enumValue,
        SampleSize sampleSize,
        ConfidenceLevel confidence,
        ConfidenceDetail confidenceDetail,
        AnalysisWindow window,
        String reasonCode,
        String reasonText,
        String zeroCode,
        Double numerator,
        Double denominator,
        Integer unresolvedCount,
        List<String> dataQualityFlags) {

    public static final List<String> OUTPUT_TYPES = List.of(
            "scalar", "count", "quantiles", "series", "named", "namedSet", "enum", "flag");

    public MetricOutcome {
        if (metricId == null || metricId.isEmpty()) {
            throw new IllegalArgumentException("metricId 必填");
        }
        if (featureId == null || featureId.isEmpty()) {
            throw new IllegalArgumentException(metricId + ": featureId 必填");
        }
        if (!OUTPUT_TYPES.contains(outputType)) {
            throw new IllegalArgumentException(metricId + ": outputType 非法：" + outputType);
        }
        java.util.Objects.requireNonNull(status, metricId + ": status 必填");
        java.util.Objects.requireNonNull(sampleSize, metricId + ": sampleSize 必填（强制条款）");
        java.util.Objects.requireNonNull(confidence, metricId + ": confidence 必填（强制条款）");
        java.util.Objects.requireNonNull(confidenceDetail, metricId + ": confidenceDetail 必填（强制条款）");
        java.util.Objects.requireNonNull(window, metricId + ": window 必填");

        if (status.valueMustBeAbsent()) {
            if (value != null || quantiles != null || series != null || namedKey != null
                    || namedKeys != null || enumValue != null) {
                throw new IllegalArgumentException(metricId
                        + ": 抑制/不可用状态下 value 类字段必须物理缺失（禁止 null/0/[] 占位）");
            }
        }
        if (status.requiresReasonCode()) {
            ReasonCodes.require(reasonCode);
            if (zeroCode != null) {
                throw new IllegalArgumentException(metricId + ": 同一项不得同时出现 reasonCode 与 zeroCode");
            }
        }
        if (status.isZero()) {
            if (!ReasonCodes.isValidZeroCode(zeroCode)) {
                throw new IllegalArgumentException(metricId + ": status=zero 必须给出闭集内的 zeroCode");
            }
            if (reasonCode != null) {
                throw new IllegalArgumentException(metricId + ": status=zero 禁止出现 reasonCode");
            }
        }
        if (status.valueMustBeAbsent() && confidence != ConfidenceLevel.SUPPRESSED) {
            throw new IllegalArgumentException(metricId + ": status=" + status.wireName()
                    + " ⇒ confidence 必须为 suppressed（双向断言）");
        }
        if (!status.valueMustBeAbsent() && confidence == ConfidenceLevel.SUPPRESSED) {
            throw new IllegalArgumentException(metricId + ": 未抑制项不得标 suppressed 置信度");
        }
        if (confidence == ConfidenceLevel.CERTAIN
                && sampleSize.eventCount() < com.octant.pipeline.feature.Thresholds.MIN_EVENTS_FOR_CONFIDENCE) {
            throw new IllegalArgumentException(metricId + ": confidence=certain ⇒ sampleSize.eventCount ≥ "
                    + com.octant.pipeline.feature.Thresholds.MIN_EVENTS_FOR_CONFIDENCE);
        }
        if (confidenceDetail.k() != sampleSize.minRequired()) {
            throw new IllegalArgumentException(metricId + ": confidenceDetail.k(" + confidenceDetail.k()
                    + ") 必须等于 sampleSize.minRequired(" + sampleSize.minRequired() + ")");
        }
        if (confidence.detailLevel() != null && confidenceDetail.level() != null
                && !confidence.detailLevel().equals(confidenceDetail.level().detailLevel())) {
            throw new IllegalArgumentException(metricId + ": confidence(" + confidence.wireName()
                    + ") 与 confidenceDetail.level(" + confidenceDetail.level() + ") 违反冻结映射表");
        }
        quantiles = quantiles == null ? null : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(quantiles));
        series = series == null ? null : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(series));
        namedKeys = namedKeys == null ? null : List.copyOf(namedKeys);
        dataQualityFlags = dataQualityFlags == null ? List.of() : List.copyOf(dataQualityFlags);
    }

    public boolean isSuppressedLike() {
        return status.valueMustBeAbsent();
    }

    public boolean hasValue() {
        return !status.valueMustBeAbsent();
    }

    public long currentSample() {
        return sampleSize.currentSample();
    }

    public Map<String, Object> summaryItem() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("metricId", metricId);
        m.put("status", status.wireName());
        m.put("confidence", confidence.wireName());
        if (reasonCode != null) {
            m.put("reasonCode", reasonCode);
        }
        if (status.requiresReasonCode()) {
            m.put("currentSample", currentSample());
            m.put("minRequired", sampleSize.minRequired());
            m.put("sampleBasis", sampleSize.basis().wireName());
        }
        if (zeroCode != null) {
            m.put("zeroCode", zeroCode);
        }
        return m;
    }
}
