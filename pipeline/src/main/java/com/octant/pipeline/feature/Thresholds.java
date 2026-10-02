package com.octant.pipeline.feature;

import java.util.List;

public final class Thresholds {

    public static final long MIN_CONTENT_UNIT = 3L;
    public static final long MIN_CONTENT_VISIBLE_ITEMS = 3L;
    public static final long MIN_ACTIVE_S = 3600L;
    public static final long MIN_SESSION_N = 5L;
    public static final long MIN_EFFECTIVE_SESSION_S = 60L;
    public static final long MIN_SESSIONS_FOR_DISTRIBUTION = 5L;
    public static final long MIN_SESSIONS_FOR_TREND = 10L;
    public static final long MIN_SESSIONS_FOR_FATIGUE = 10L;
    public static final long MIN_ACTIVE_S_FOR_M5 = 10L * 3600L;
    public static final long MIN_CATEGORY_OCCURRENCES = 3L;
    public static final long MIN_CATEGORY_ACTIVE_S = 300L;
    public static final long MIN_REPEAT_SEGMENTS = 5L;
    public static final long MIN_ACTIVE_TICK_S = 50L;
    public static final long MIN_AUTOMATION_DEVICES = 3L;
    public static final long MIN_DEVICE_REUSED_SESSIONS = 2L;
    public static final double MAX_UNKNOWN_DEVICE_SHARE = 0.30d;
    public static final long MIN_MECHANISMS_STARTED = 3L;
    public static final long MIN_MECHANISM_OBSERVE_DAYS = 7L;
    public static final long MIN_COMBAT_ENCOUNTERS = 20L;
    public static final long MIN_COMBAT_ENCOUNTERS_PARTIAL = 5L;
    public static final long MIN_DEATH_CAUSE_OCCURRENCES = 3L;
    public static final long MIN_UNIT_ATTEMPTS = 3L;
    public static final long MIN_PROFILE_SESSIONS = 5L;
    public static final long MIN_PROFILE_EVIDENCE_ITEMS = 3L;
    public static final long MIN_PROFILE_EVIDENCE_SESSIONS = 2L;
    public static final long MIN_PLAYERS_FOR_GROUP_CONCLUSION = 5L;
    public static final long MIN_CLUSTER_N = 5L;
    public static final long MIN_BASELINE_PLAYERS = 30L;
    public static final long MIN_EVENTS_FOR_CONFIDENCE = 30L;
    public static final long SUPPRESSION_BREAKDOWN_FLOOR = 2L;

    public static final double W_SAMPLE_ADEQUACY = 0.40d;
    public static final double W_INPUT_COVERAGE = 0.25d;
    public static final double W_PROXY_QUALITY = 0.20d;
    public static final double W_AFK = 0.15d;

    public static final double HIGH_SCORE_FLOOR = 0.85d;
    public static final double MEDIUM_SCORE_FLOOR = 0.60d;

    public static final double M1B_T_LO = 8.0d;
    public static final double M1B_T_HI = 320.0d;

    public static final long AUTOMATION_NET_GROWTH_PER_10MIN = 8L;
    public static final double FARM_PATTERN_SIMILARITY = 0.90d;
    public static final double OVERTIER_POWER_RATIO = 2.0d;
    public static final double STALL_MIN_ACTIVE_DENSITY_PM = 0.5d;

    public static List<String[]> registry() {
        return List.of(
                new String[]{"MIN_CONTENT_UNIT", String.valueOf(MIN_CONTENT_UNIT)},
                new String[]{"MIN_CONTENT_VISIBLE_ITEMS", String.valueOf(MIN_CONTENT_VISIBLE_ITEMS)},
                new String[]{"MIN_ACTIVE_S", String.valueOf(MIN_ACTIVE_S)},
                new String[]{"MIN_SESSION_N", String.valueOf(MIN_SESSION_N)},
                new String[]{"MIN_EFFECTIVE_SESSION_S", String.valueOf(MIN_EFFECTIVE_SESSION_S)},
                new String[]{"MIN_SESSIONS_FOR_DISTRIBUTION", String.valueOf(MIN_SESSIONS_FOR_DISTRIBUTION)},
                new String[]{"MIN_SESSIONS_FOR_TREND", String.valueOf(MIN_SESSIONS_FOR_TREND)},
                new String[]{"MIN_ACTIVE_S_FOR_M5", String.valueOf(MIN_ACTIVE_S_FOR_M5)},
                new String[]{"MIN_CATEGORY_OCCURRENCES", String.valueOf(MIN_CATEGORY_OCCURRENCES)},
                new String[]{"MIN_REPEAT_SEGMENTS", String.valueOf(MIN_REPEAT_SEGMENTS)},
                new String[]{"MIN_AUTOMATION_DEVICES", String.valueOf(MIN_AUTOMATION_DEVICES)},
                new String[]{"MIN_COMBAT_ENCOUNTERS", String.valueOf(MIN_COMBAT_ENCOUNTERS)},
                new String[]{"MIN_COMBAT_ENCOUNTERS_PARTIAL", String.valueOf(MIN_COMBAT_ENCOUNTERS_PARTIAL)},
                new String[]{"MIN_DEATH_CAUSE_OCCURRENCES", String.valueOf(MIN_DEATH_CAUSE_OCCURRENCES)},
                new String[]{"MIN_UNIT_ATTEMPTS", String.valueOf(MIN_UNIT_ATTEMPTS)},
                new String[]{"MIN_PROFILE_SESSIONS", String.valueOf(MIN_PROFILE_SESSIONS)},
                new String[]{"MIN_PLAYERS_FOR_GROUP_CONCLUSION", String.valueOf(MIN_PLAYERS_FOR_GROUP_CONCLUSION)},
                new String[]{"MIN_EVENTS_FOR_CONFIDENCE", String.valueOf(MIN_EVENTS_FOR_CONFIDENCE)},
                new String[]{"IDLE_GAP_THRESHOLD", String.valueOf(com.octant.pipeline.raw.EventStream.IDLE_GAP_THRESHOLD_MS / 1000L)},
                new String[]{"TICK_MS", String.valueOf(com.octant.pipeline.raw.RawEvent.TICK_MS)});
    }

    private Thresholds() {
    }
}
