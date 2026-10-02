package com.octant.common;

public enum SuppressionReason {

    INSUFFICIENT_ACTIVE_TIME,

    INSUFFICIENT_SESSION_COUNT,

    INSUFFICIENT_SESSION_COUNT_FOR_FATIGUE,

    INSUFFICIENT_CONTENT_UNIT,

    INSUFFICIENT_AXIS_COUNT,

    PREMATURE_PROGRESSION,

    SIGNAL_UNAVAILABLE;

    public String describe(long actual, long required) {
        return name() + ": " + actual + "/" + required;
    }

    public String describeCount(long actual, long required) {
        return name() + ": n=" + actual + ", required=" + required;
    }
}
