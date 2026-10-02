package com.octant.pipeline.kite;

import java.util.Objects;

public final class KiteAxisValue {

    private final KiteAxis axis;
    private final boolean available;
    private final double value;
    private final String reasonCode;
    private final KiteSampleSize sampleSize;
    private final String observationDump;

    private KiteAxisValue(KiteAxis axis, boolean available, double value, String reasonCode,
                          KiteSampleSize sampleSize, String observationDump) {
        this.axis = Objects.requireNonNull(axis, "axis");
        this.available = available;
        this.value = value;
        this.reasonCode = reasonCode;
        this.sampleSize = sampleSize;
        this.observationDump = observationDump == null ? "" : observationDump;
        if (available) {
            if (!(value >= 0d && value <= 1d)) {
                throw new IllegalArgumentException(axis + " 取值必须 ∈ [0,1]（规格 §4.2 LV-4），实际 " + value);
            }
        } else {
            if (reasonCode == null || reasonCode.isEmpty()) {
                throw new IllegalArgumentException(axis + " 被抑制时必须带 reasonCode（规格 §6 SG-7）");
            }
            if (sampleSize == null) {
                throw new IllegalArgumentException(axis + " 被抑制时必须带结构化 sampleSize（规格 §6 SG-2）");
            }
        }
    }

    public static KiteAxisValue available(KiteAxis axis, double value, String observationDump) {
        return new KiteAxisValue(axis, true, value, null, null, observationDump);
    }

    public static KiteAxisValue suppressed(KiteAxis axis, String reasonCode, KiteSampleSize sampleSize,
                                          String observationDump) {
        return new KiteAxisValue(axis, false, Double.NaN, reasonCode, sampleSize, observationDump);
    }

    public KiteAxis axis() {
        return axis;
    }

    public boolean available() {
        return available;
    }

    public double value() {
        if (!available) {
            throw new IllegalStateException(axis + " 被抑制，不得取值（规格 §6 SG-8）");
        }
        return value;
    }

    public String reasonCode() {
        return reasonCode;
    }

    public KiteSampleSize sampleSize() {
        return sampleSize;
    }

    public String observationDump() {
        return observationDump;
    }

    public String toJson() {
        StringBuilder sb = new StringBuilder("{\"axis\":\"").append(axis).append("\",\"status\":\"")
                .append(available ? KiteStatus.AVAILABLE.wireName() : KiteStatus.SUPPRESSED.wireName()).append('"');
        if (available) {
            sb.append(",\"value\":").append(KiteConstants.trim(value));
        } else {
            sb.append(",\"reasonCode\":\"").append(reasonCode).append("\",\"sampleSize\":")
                    .append(sampleSize.toJson());
        }
        return sb.append('}').toString();
    }

    @Override
    public String toString() {
        return axis + (available ? "=" + KiteConstants.trim(value) : "<" + reasonCode + ">");
    }
}
