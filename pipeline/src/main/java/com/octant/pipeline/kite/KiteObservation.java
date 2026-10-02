package com.octant.pipeline.kite;

import java.util.Objects;

public final class KiteObservation {

    private final KiteAxis axis;
    private final String key;
    private final String primitive;
    private final boolean available;
    private final double value;
    private final String reasonCode;

    private final int[] entityDiversityInput;

    private KiteObservation(KiteAxis axis, String key, String primitive, boolean available, double value,
                            String reasonCode) {
        this(axis, key, primitive, available, value, reasonCode, null);
    }

    private KiteObservation(KiteAxis axis, String key, String primitive, boolean available, double value,
                            String reasonCode, int[] entityDiversityInput) {
        this.axis = Objects.requireNonNull(axis, "axis");
        this.key = Objects.requireNonNull(key, "key");
        this.primitive = Objects.requireNonNull(primitive, "primitive");
        this.available = available;
        this.value = value;
        this.reasonCode = reasonCode;
        this.entityDiversityInput = entityDiversityInput == null ? null : entityDiversityInput.clone();
        if (available && entityDiversityInput == null && !Double.isFinite(value)) {
            throw new IllegalArgumentException("可用观测必须是有限数值：" + key + " = " + value);
        }
        if (!available && (reasonCode == null || reasonCode.isEmpty())) {
            throw new IllegalArgumentException("不可用观测必须带 reasonCode：" + key);
        }
    }

    public static KiteObservation of(KiteAxis axis, String key, String primitive, double value) {
        return new KiteObservation(axis, key, primitive, true, value, null);
    }

    public static KiteObservation ofEntityDiversity(KiteAxis axis, String key, String primitive,
                                                    int cumulativeEntities, int catalogSize) {
        return new KiteObservation(axis, key, primitive, true, Double.NaN, null,
                new int[] {cumulativeEntities, catalogSize});
    }

    public boolean hasEntityDiversityInput() {
        return entityDiversityInput != null;
    }

    public int[] entityDiversityInput() {
        if (entityDiversityInput == null) {
            throw new IllegalStateException("该观测未携带累计实体多样性输入：" + key);
        }
        return entityDiversityInput.clone();
    }

    public static KiteObservation unavailable(KiteAxis axis, String key, String primitive, String reasonCode) {
        return new KiteObservation(axis, key, primitive, false, Double.NaN, reasonCode);
    }

    public KiteAxis axis() {
        return axis;
    }

    public String key() {
        return key;
    }

    public String primitive() {
        return primitive;
    }

    public boolean available() {
        return available;
    }

    public double value() {
        if (!available) {
            throw new IllegalStateException("观测不可用，不得取值：" + key);
        }
        return value;
    }

    public String reasonCode() {
        return reasonCode;
    }

    public double displayValue() {
        if (entityDiversityInput != null) {
            int[] kc = entityDiversityInput;
            return KiteAxisMath.entityDiversity(kc[0], kc[1]);
        }
        return value;
    }

    @Override
    public String toString() {
        return key + "=" + (available ? KiteConstants.trim(value) : "<" + reasonCode + ">");
    }
}
