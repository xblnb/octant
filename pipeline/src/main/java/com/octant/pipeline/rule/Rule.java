package com.octant.pipeline.rule;

import java.util.Map;

public record Rule(
        String id,
        String group,
        int priority,
        java.util.List<String> metricIds,
        java.util.List<String> featureIds,
        String conditionKey,
        String actionKey,
        String statementKey,
        String suppressReason) {

    public Rule {
        metricIds = java.util.List.copyOf(metricIds);
        featureIds = java.util.List.copyOf(featureIds);
    }

    @FunctionalInterface
    public interface Condition {
        boolean test(Map<String, Object> features);
    }

    public record Hit(String ruleId, String group, int priority, boolean matched,
                      String detailKey, Map<String, Object> observed) {
    }
}
