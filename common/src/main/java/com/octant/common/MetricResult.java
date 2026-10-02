package com.octant.common;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;

public final class MetricResult {

    private final String id;
    private final Double value;
    private final Confidence confidence;
    private final boolean suppressed;
    private final SuppressionReason reason;
    private final String reasonDetail;
    private final Map<String, Object> extras;

    private MetricResult(String id, Double value, Confidence confidence,
                         boolean suppressed, SuppressionReason reason, String reasonDetail,
                         Map<String, Object> extras) {
        this.id = Objects.requireNonNull(id, "id");
        this.value = value;
        this.confidence = confidence;
        this.suppressed = suppressed;
        this.reason = reason;
        this.reasonDetail = reasonDetail;
        this.extras = extras == null ? Map.of() : com.octant.common.privacy.OrderedCollections.copyOf(extras);

        if (suppressed) {
            if (reason == null) {
                throw new IllegalArgumentException(
                        id + ": 抑制状态必须给出原因码（禁止无原因的抑制）");
            }
            if (value != null) {
                throw new IllegalArgumentException(
                        id + ": 抑制状态下 value 必须缺省（禁止用 0 或均值代填）");
            }
            if (confidence != Confidence.SUPPRESSED) {
                throw new IllegalArgumentException(
                        id + ": 抑制状态下 confidence 必须是 SUPPRESSED 且非空（dc §4.2），实际=" + confidence);
            }
        } else {
            if (value == null) {
                throw new IllegalArgumentException(id + ": 未抑制的指标必须有值");
            }
            if (confidence == null) {
                throw new IllegalArgumentException(id + ": 未抑制的指标必须标注置信度");
            }
            if (reason != null) {
                throw new IllegalArgumentException(id + ": 未抑制的指标不得带抑制原因码");
            }
        }
    }

    public static MetricResult of(String id, double value, Confidence confidence) {
        return new MetricResult(id, value, confidence, false, null, null, Map.of());
    }

    public static MetricResult of(String id, double value, Confidence confidence,
                                  Map<String, Object> extras) {
        return new MetricResult(id, value, confidence, false, null, null, extras);
    }

    public static MetricResult suppressed(String id, SuppressionReason reason, String detail) {
        return new MetricResult(id, null, Confidence.SUPPRESSED, true, reason, detail, Map.of());
    }

    public String id() {
        return id;
    }

    public OptionalDouble value() {
        return value == null ? OptionalDouble.empty() : OptionalDouble.of(value);
    }

    public Confidence confidence() {
        return confidence;
    }

    public boolean isSuppressed() {
        return suppressed;
    }

    public Optional<SuppressionReason> reason() {
        return Optional.ofNullable(reason);
    }

    public Optional<String> reasonDetail() {
        return Optional.ofNullable(reasonDetail);
    }

    public Map<String, Object> extras() {
        return extras;
    }

    @Override
    public String toString() {
        if (suppressed) {
            return id + "=SUPPRESSED(" + reasonDetail + ")";
        }
        return id + "=" + value + " [" + confidence + "]";
    }
}
