package com.octant.pipeline.analysis;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ReasonCodes {

    public static final String SAMPLE_BELOW_MIN = "SAMPLE_BELOW_MIN";
    public static final String SAMPLE_ZERO_DENOM = "SAMPLE_ZERO_DENOM";
    public static final String SAMPLE_INSUFFICIENT_ACTIVE_TIME = "SAMPLE_INSUFFICIENT_ACTIVE_TIME";
    public static final String SAMPLE_INSUFFICIENT_SESSIONS = "SAMPLE_INSUFFICIENT_SESSIONS";
    public static final String SAMPLE_INSUFFICIENT_SESSIONS_FOR_DISTRIBUTION =
            "SAMPLE_INSUFFICIENT_SESSIONS_FOR_DISTRIBUTION";
    public static final String SAMPLE_INSUFFICIENT_SESSIONS_FOR_TREND = "SAMPLE_INSUFFICIENT_SESSIONS_FOR_TREND";
    public static final String SAMPLE_INSUFFICIENT_UNITS = "SAMPLE_INSUFFICIENT_UNITS";
    public static final String SAMPLE_INSUFFICIENT_OCCURRENCES = "SAMPLE_INSUFFICIENT_OCCURRENCES";
    public static final String SAMPLE_INSUFFICIENT_COMBAT = "SAMPLE_INSUFFICIENT_COMBAT";
    public static final String SAMPLE_INSUFFICIENT_ATTEMPTS = "SAMPLE_INSUFFICIENT_ATTEMPTS";
    public static final String SAMPLE_BELOW_DEATH_CAUSE_OCCURRENCES = "SAMPLE_BELOW_DEATH_CAUSE_OCCURRENCES";
    public static final String SAMPLE_BELOW_PAIN_POINT_OCCURRENCES = "SAMPLE_BELOW_PAIN_POINT_OCCURRENCES";
    public static final String SAMPLE_BELOW_PREFERENCE_OCCURRENCES = "SAMPLE_BELOW_PREFERENCE_OCCURRENCES";
    public static final String SAMPLE_INSUFFICIENT_EVIDENCE_ITEMS = "SAMPLE_INSUFFICIENT_EVIDENCE_ITEMS";
    public static final String SAMPLE_INSUFFICIENT_GROUP_SIZE = "SAMPLE_INSUFFICIENT_GROUP_SIZE";

    public static final String SAMPLE_PROXY_UNAVAILABLE = "SAMPLE_PROXY_UNAVAILABLE";
    public static final String INPUT_MISSING = "INPUT_MISSING";
    public static final String INPUT_UNAVAILABLE = "INPUT_UNAVAILABLE";
    public static final String INPUT_UNRESOLVED = "INPUT_UNRESOLVED";
    public static final String INPUT_DEVICE_EVIDENCE_MISSING = "INPUT_DEVICE_EVIDENCE_MISSING";
    public static final String INPUT_SIGNAL_MISMATCH = "INPUT_SIGNAL_MISMATCH";
    public static final String INPUT_NON_MONOTONIC = "INPUT_NON_MONOTONIC";

    public static final String ACTION_CONSENT_SCOPE = "ACTION_CONSENT_SCOPE";
    public static final String ACTION_FARM_COMBAT_EXCLUDED = "ACTION_FARM_COMBAT_EXCLUDED";
    public static final String WINDOW_NOT_ELAPSED = "WINDOW_NOT_ELAPSED";
    public static final String CAP_DATA_TRUNCATED = "CAP_DATA_TRUNCATED";

    public static final String ZERO_NO_STALL_SEGMENT = "ZERO_NO_STALL_SEGMENT";
    public static final String ZERO_NO_COMBAT = "ZERO_NO_COMBAT";
    public static final String ZERO_NO_AUTOMATION_DEVICE = "ZERO_NO_AUTOMATION_DEVICE";
    public static final String ZERO_NO_REPEAT_SEGMENT = "ZERO_NO_REPEAT_SEGMENT";
    public static final String ZERO_NO_CONTENT_UNIT = "ZERO_NO_CONTENT_UNIT";

    private static final Set<String> REASON_SET = new LinkedHashSet<>(List.of(
            SAMPLE_BELOW_MIN, SAMPLE_ZERO_DENOM, SAMPLE_INSUFFICIENT_ACTIVE_TIME,
            SAMPLE_INSUFFICIENT_SESSIONS, SAMPLE_INSUFFICIENT_SESSIONS_FOR_DISTRIBUTION,
            SAMPLE_INSUFFICIENT_SESSIONS_FOR_TREND, SAMPLE_INSUFFICIENT_UNITS,
            SAMPLE_INSUFFICIENT_OCCURRENCES, SAMPLE_INSUFFICIENT_COMBAT, SAMPLE_INSUFFICIENT_ATTEMPTS,
            SAMPLE_BELOW_DEATH_CAUSE_OCCURRENCES, SAMPLE_BELOW_PAIN_POINT_OCCURRENCES,
            SAMPLE_BELOW_PREFERENCE_OCCURRENCES, SAMPLE_INSUFFICIENT_EVIDENCE_ITEMS,
            SAMPLE_INSUFFICIENT_GROUP_SIZE, SAMPLE_PROXY_UNAVAILABLE, INPUT_MISSING, INPUT_UNAVAILABLE,
            INPUT_UNRESOLVED, INPUT_DEVICE_EVIDENCE_MISSING, INPUT_SIGNAL_MISMATCH, INPUT_NON_MONOTONIC,
            ACTION_CONSENT_SCOPE, ACTION_FARM_COMBAT_EXCLUDED, WINDOW_NOT_ELAPSED, CAP_DATA_TRUNCATED));

    private static final Set<String> ZERO_SET = new LinkedHashSet<>(List.of(
            ZERO_NO_STALL_SEGMENT, ZERO_NO_COMBAT, ZERO_NO_AUTOMATION_DEVICE,
            ZERO_NO_REPEAT_SEGMENT, ZERO_NO_CONTENT_UNIT));

    private ReasonCodes() {
    }

    public static boolean isValid(String code) {
        return code != null && REASON_SET.contains(code);
    }

    public static boolean isValidZeroCode(String code) {
        return code != null && ZERO_SET.contains(code);
    }

    public static List<String> all() {
        return List.copyOf(REASON_SET);
    }

    public static List<String> allZeroCodes() {
        return List.copyOf(ZERO_SET);
    }

    public static String messageKey(String reasonCode) {
        require(reasonCode);
        String camel = toCamel(reasonCode);
        return "suppress." + camel;
    }

    public static void require(String reasonCode) {
        if (!isValid(reasonCode)) {
            throw new IllegalArgumentException("非法的 reasonCode（不得自造）：" + reasonCode
                    + "；允许值见契约 §5.6 的 26 项闭集");
        }
    }

    private static String toCamel(String code) {
        String[] parts = code.toLowerCase().split("_");
        StringBuilder sb = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            if (parts[i].isEmpty()) {
                continue;
            }
            sb.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1));
        }
        return sb.toString();
    }
}
