package com.octant.pipeline.kite;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class KiteVector {

    private final Map<KiteAxis, Double> values;
    private final Map<KiteAxis, KiteAxisValue> axisValues;

    private KiteVector(Map<KiteAxis, KiteAxisValue> axisValues) {
        this.axisValues = Collections.unmodifiableMap(axisValues);
        Map<KiteAxis, Double> v = new LinkedHashMap<>();
        for (KiteAxis axis : KiteAxis.values()) {
            KiteAxisValue av = axisValues.get(axis);
            if (av == null) {
                throw new IllegalArgumentException("特征向量必须三轴齐备，缺 " + axis);
            }
            if (av.available()) {
                v.put(axis, av.value());
            }
        }
        this.values = Collections.unmodifiableMap(v);
    }

    public static KiteVector of(KiteAxisValue... axisValues) {
        Map<KiteAxis, KiteAxisValue> m = new LinkedHashMap<>();
        for (KiteAxisValue av : axisValues) {
            if (m.put(av.axis(), av) != null) {
                throw new IllegalArgumentException("同一轴重复给出：" + av.axis());
            }
        }
        return new KiteVector(m);
    }

    public double[] requireValues() {
        if (!complete()) {
            throw new IllegalStateException("存在被抑制的轴，不得当作完整向量使用：" + suppressedAxes());
        }
        return new double[] {values.get(KiteAxis.PROG), values.get(KiteAxis.SPON), values.get(KiteAxis.GUID)};
    }

    public KiteAxisValue axisValue(KiteAxis axis) {
        return axisValues.get(axis);
    }

    public boolean complete() {
        return values.size() == KiteAxis.values().length;
    }

    public List<KiteAxis> suppressedAxes() {
        List<KiteAxis> out = new java.util.ArrayList<>();
        for (KiteAxis axis : KiteAxis.values()) {
            if (!axisValues.get(axis).available()) {
                out.add(axis);
            }
        }
        return out;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("X=(");
        for (int i = 0; i < KiteAxis.values().length; i++) {
            KiteAxis axis = KiteAxis.values()[i];
            KiteAxisValue av = axisValues.get(axis);
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(av.available() ? KiteConstants.trim(av.value()) : "<" + av.reasonCode() + ">");
        }
        return sb.append(')').toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof KiteVector other)) {
            return false;
        }
        for (KiteAxis axis : KiteAxis.values()) {
            KiteAxisValue a = axisValues.get(axis);
            KiteAxisValue b = other.axisValues.get(axis);
            if (a.available() != b.available()) {
                return false;
            }
            if (a.available() && Double.compare(a.value(), b.value()) != 0) {
                return false;
            }
            if (!Objects.equals(a.reasonCode(), b.reasonCode())) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        int h = 17;
        for (KiteAxis axis : KiteAxis.values()) {
            KiteAxisValue a = axisValues.get(axis);
            h = 31 * h + (a.available() ? Double.hashCode(a.value()) : Objects.hashCode(a.reasonCode()));
        }
        return h;
    }
}
