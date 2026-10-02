package com.octant.pipeline.feature;

public enum SampleBasis {
    EVENTS("events", "次"),
    SESSIONS("sessions", "会话"),
    ACTIVE_TIME("active_time", "s"),
    UNITS("units", "个");

    private final String wireName;
    private final String unitLabel;

    SampleBasis(String wireName, String unitLabel) {
        this.wireName = wireName;
        this.unitLabel = unitLabel;
    }

    public String wireName() {
        return wireName;
    }

    public String unitLabel() {
        return unitLabel;
    }
}
