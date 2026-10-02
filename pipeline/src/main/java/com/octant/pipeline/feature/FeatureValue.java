package com.octant.pipeline.feature;

import java.util.List;

public record FeatureValue(String metricId, String featureId, String expr, Object value) {

    public static FeatureValue of(String metricId, String featureId, String expr, Object value) {
        return new FeatureValue(metricId, featureId, expr, value);
    }

    public boolean isNumber() {
        return value instanceof Number;
    }

    public double asDouble() {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        throw new IllegalStateException(featureId + " 不是数值型特征（实际 " + typeName() + "）");
    }

    public long asLong() {
        return Math.round(asDouble());
    }

    public boolean asBool() {
        if (value instanceof Boolean b) {
            return b;
        }
        throw new IllegalStateException(featureId + " 不是布尔型特征");
    }

    @SuppressWarnings("unchecked")
    public List<Double> asSeries() {
        if (value instanceof List<?> l) {
            return (List<Double>) l;
        }
        throw new IllegalStateException(featureId + " 不是序列型特征");
    }

    public String typeName() {
        return value == null ? "null" : value.getClass().getSimpleName();
    }

    @Override
    public String toString() {
        return metricId + "(" + featureId + ") = " + value;
    }
}
