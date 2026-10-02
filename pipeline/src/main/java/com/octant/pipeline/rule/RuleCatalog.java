package com.octant.pipeline.rule;

import com.octant.pipeline.analysis.ReasonCodes;

import java.util.List;

public final class RuleCatalog {

    public static final String K_STATUS = "status";
    public static final String K_VALUE = "value";
    public static final String K_METRIC = "metricId";
    public static final String K_REASON = "gateReasonCode";

    private RuleCatalog() {
    }

    public static RuleSet standard() {
        RuleSet.Builder b = RuleSet.builder();

        b.rule(suppress("RS_GATE_01", "M1a", List.of("M1a"), List.of("M1A_PROGRESS_COMPLETION"),
                        "gate.m1a.units", ReasonCodes.SAMPLE_INSUFFICIENT_UNITS),
                f -> isSuppressed(f, "M1a"));
        b.rule(suppress("RS_GATE_02", "M2a", List.of("M2a"), List.of("M2A_STALL_UNITS"),
                        "gate.m2a.units", ReasonCodes.SAMPLE_INSUFFICIENT_UNITS),
                f -> isSuppressed(f, "M2a"));
        b.rule(suppress("RS_GATE_03", "M3b", List.of("M3b"), List.of("M3B_SESSION_MEDIAN"),
                        "gate.m3.sessions", ReasonCodes.SAMPLE_INSUFFICIENT_SESSIONS),
                f -> isSuppressed(f, "M3b"));
        b.rule(suppress("RS_GATE_04", "M4", List.of("M4"), List.of("M4_BREADTH"),
                        "gate.m4.axes", ReasonCodes.SAMPLE_INSUFFICIENT_UNITS),
                f -> isSuppressed(f, "M4"));
        b.rule(suppress("RS_GATE_05", "M5a", List.of("M5a"), List.of("M5A_AXIS_ENTROPY"),
                        "gate.m5.axes", ReasonCodes.SAMPLE_INSUFFICIENT_UNITS),
                f -> isSuppressed(f, "M5a"));
        b.rule(suppress("RS_GATE_06", "M6a", List.of("M6a"), List.of("M6A_CATEGORY_TIME_VECTOR"),
                        "gate.m6.categories", ReasonCodes.SAMPLE_INSUFFICIENT_OCCURRENCES),
                f -> isSuppressed(f, "M6a"));
        b.rule(suppress("RS_GATE_07", "M7a", List.of("M7a"), List.of("M7A_REPETITION_SHARE"),
                        "gate.m7a.repeats", ReasonCodes.SAMPLE_INSUFFICIENT_OCCURRENCES),
                f -> isSuppressed(f, "M7a"));
        b.rule(suppress("RS_GATE_08", "M8a", List.of("M8a"), List.of("M8A_COMBAT_TIME_SHARE"),
                        "gate.m8a.combat", ReasonCodes.SAMPLE_INSUFFICIENT_COMBAT),
                f -> isSuppressed(f, "M8a"));
        b.rule(suppress("RS_GATE_09", "M9", List.of("M9_SEGMENT"), List.of("M9_SEGMENT"),
                        "gate.m9.profile", ReasonCodes.SAMPLE_INSUFFICIENT_EVIDENCE_ITEMS),
                f -> isSuppressed(f, "M9_SEGMENT"));
        b.rule(suppress("RS_GATE_10", "M3e", List.of("M3e"), List.of("M3E_FATIGUE_INDEX"),
                        "gate.m3e.trend", ReasonCodes.SAMPLE_INSUFFICIENT_SESSIONS_FOR_TREND),
                f -> isSuppressed(f, "M3e"));
        b.rule(suppress("RS_GATE_11", "M7d", List.of("M7d", "M7e"),
                        List.of("M7D_COMPLEXITY_DEBT", "M7E_COMPLEXITY_DEBT_RATIO"),
                        "gate.m7d.window", ReasonCodes.WINDOW_NOT_ELAPSED),
                f -> isSuppressed(f, "M7d"));

        b.rule(conclusion("RS_M1_CONC", "M1_CONC", 40, List.of("M1a", "M1b"),
                        List.of("M1A_PROGRESS_COMPLETION", "M1B_CONTENT_CONVERSION"),
                        "conclusion.m1.progress_share", "conclusion.m1.progress_share"),
                f -> available(f, "M1a"));

        b.rule(conclusion("RS_M1_STALL_CONC", "M1_STALL_CONC", 35, List.of("M1c", "M2a", "M2b"),
                        List.of("M1C_STALL_RATE", "M2A_STALL_UNITS", "M2B_STALL_SHARE"),
                        "conclusion.m2.stall_units", "conclusion.m2.stall_units"),
                f -> available(f, "M2b"));

        b.rule(conclusion("RS_M3_CONC", "M3_CONC", 30, List.of("M3a", "M3b", "M3d"),
                        List.of("M3A_ACTIVE_TOTAL", "M3B_SESSION_MEDIAN", "M3D_SESSION_FREQUENCY"),
                        "conclusion.m3.playtime", "conclusion.m3.playtime"),
                f -> available(f, "M3b"));

        b.rule(conclusion("RS_M4_CONC", "M4_CONC", 25, List.of("M4"),
                        List.of("M4_BREADTH"),
                        "conclusion.m4.breadth", "conclusion.m4.breadth"),
                f -> available(f, "M4"));

        b.rule(conclusion("RS_M5_CONC", "M5_CONC", 25, List.of("M5a", "M5b", "M5c"),
                        List.of("M5A_AXIS_ENTROPY", "M5B_AXIS_GINI", "M5C_DOMINANT_AXIS"),
                        "conclusion.m5.dispersion", "conclusion.m5.dispersion"),
                f -> available(f, "M5a"));

        b.rule(conclusion("RS_M6_CONC", "M6_CONC", 25, List.of("M6a", "M6b", "M6c", "M6d"),
                        List.of("M6A_CATEGORY_TIME_VECTOR", "M6B_CATEGORY_ENTROPY",
                                "M6C_DOMINANT_CATEGORY", "M6D_SECONDARY_CATEGORY"),
                        "conclusion.m6.preference", "conclusion.m6.preference"),
                f -> available(f, "M6a"));

        b.rule(conclusion("RS_M7_CONC", "M7_CONC", 30, List.of("M7a", "M7c", "M7d"),
                        List.of("M7A_REPETITION_SHARE", "M7C_AUTOMATION_SUBSTITUTION", "M7D_COMPLEXITY_DEBT"),
                        "conclusion.m7.repetition_automation", "conclusion.m7.repetition_automation"),
                f -> available(f, "M7a"));

        b.rule(conclusion("RS_M8_CONC", "M8_CONC", 30, List.of("M8a", "M8b", "M8e"),
                        List.of("M8A_COMBAT_TIME_SHARE", "M8B_NON_LETHAL_RATE", "M8E_DAMAGE_EXCHANGE_RATIO"),
                        "conclusion.m8.combat", "conclusion.m8.combat"),
                f -> available(f, "M8a"));

        b.rule(conclusion("RS_M8_DEATH_CONC", "M8_DEATH_CONC", 35, List.of("M8g"),
                        List.of("M8G_DEATH_CAUSE_DIST"),
                        "conclusion.m8.death_causes", "conclusion.m8.death_causes"),
                f -> available(f, "M8g"));

        b.rule(conclusion("RS_M3E_CONC", "M3E_CONC", 45, List.of("M3e"),
                        List.of("M3E_FATIGUE_INDEX"),
                        "conclusion.m3e.fatigue", "conclusion.m3e.fatigue"),
                f -> available(f, "M3e"));

        b.rule(recommendation("RS_REC_01", "REC_PAIN", 30, List.of("M2b"),
                        List.of("M2B_STALL_SHARE"),
                        "action.check_stalled_unit", "action.check_stalled_unit"),
                f -> available(f, "M2b"));
        b.rule(recommendation("RS_REC_02", "REC_PREFERENCE", 20, List.of("M6c"),
                        List.of("M6C_DOMINANT_CATEGORY"),
                        "action.review_preference_spread", "action.review_preference_spread"),
                f -> available(f, "M6c"));
        b.rule(recommendation("RS_REC_03", "REC_COMBAT", 20, List.of("M8b"),
                        List.of("M8B_NON_LETHAL_RATE"),
                        "action.review_combat_difficulty", "action.review_combat_difficulty"),
                f -> available(f, "M8b"));

        b.rule(segment("RS_SEG_01", "SEG_EXPLORER", 50, "segment.broad_explorer"),
                f -> valueAtLeast(f, "M4", 0.5d) && valueAtMost(f, "M5a", 1.01d) && valueAtLeast(f, "M5a", 0.6d));
        b.rule(segment("RS_SEG_02", "SEG_FOCUSED", 45, "segment.focused_specialist"),
                f -> valueAtLeast(f, "M4", 0.0d) && valueAtMost(f, "M5a", 0.6d));
        b.rule(segment("RS_SEG_03", "SEG_AUTOMATOR", 40, "segment.automation_builder"),
                f -> valueAtLeast(f, "M7c", 0.3d));
        b.rule(segment("RS_SEG_04", "SEG_COMBATANT", 40, "segment.combat_oriented"),
                f -> valueAtLeast(f, "M8a", 0.15d));
        b.rule(segment("RS_SEG_05", "SEG_PERSISTENT", 40, "segment.persistent_progressor"),
                f -> valueAtLeast(f, "M3e", 0.8d) && valueAtLeast(f, "M3a", 5.0d));
        b.rule(segment("RS_SEG_06", "SEG_EARLY", 30, "segment.early_session"),
                f -> valueAtMost(f, "M3a", 2.0d));

        return b.build();
    }

    private static Rule suppress(String id, String group, List<String> metricIds, List<String> featureIds,
                                 String conditionKey, String reasonCode) {
        return new Rule(id, "GATE_" + group, 100, metricIds, featureIds, conditionKey,
                "action.suppress", "suppress." + reasonCode, reasonCode);
    }

    private static Rule conclusion(String id, String group, int priority, List<String> metricIds,
                                   List<String> featureIds, String conditionKey, String statementKey) {
        return new Rule(id, group, priority, metricIds, featureIds, conditionKey,
                "action.emit_conclusion", statementKey, null);
    }

    private static Rule recommendation(String id, String group, int priority, List<String> metricIds,
                                       List<String> featureIds, String conditionKey, String actionKey) {
        return new Rule(id, group, priority, metricIds, featureIds, conditionKey, actionKey, null, null);
    }

    private static Rule segment(String id, String group, int priority, String statementKey) {
        return new Rule(id, group, priority, List.of("M9_SEGMENT"), List.of("M9_SEGMENT"),
                "segment.condition", "action.assign_segment", statementKey, null);
    }

    static boolean isSuppressed(java.util.Map<String, Object> f, String metricId) {
        return "suppressed".equals(f.get(K_STATUS + ":" + metricId));
    }

    static boolean available(java.util.Map<String, Object> f, String metricId) {
        String s = (String) f.get(K_STATUS + ":" + metricId);
        return "available".equals(s) || "zero".equals(s);
    }

    static boolean valueAtLeast(java.util.Map<String, Object> f, String metricId, double min) {
        Object v = f.get(K_VALUE + ":" + metricId);
        return v instanceof Number n && n.doubleValue() >= min;
    }

    static boolean valueAtMost(java.util.Map<String, Object> f, String metricId, double max) {
        Object v = f.get(K_VALUE + ":" + metricId);
        return v instanceof Number n && n.doubleValue() <= max;
    }
}
