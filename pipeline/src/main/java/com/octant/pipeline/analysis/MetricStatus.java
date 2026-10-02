package com.octant.pipeline.analysis;

public enum MetricStatus {
    AVAILABLE("available"),
    ZERO("zero"),
    SUPPRESSED("suppressed"),
    UNAVAILABLE("unavailable"),
    ERROR("error"),
    UNKNOWN("unknown");

    private final String wireName;

    MetricStatus(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public boolean valueMustBeAbsent() {
        return this == SUPPRESSED || this == UNAVAILABLE || this == ERROR;
    }

    public boolean requiresReasonCode() {
        return this == SUPPRESSED || this == UNAVAILABLE || this == ERROR;
    }

    public boolean isZero() {
        return this == ZERO;
    }

    public static MetricStatus fromWireName(String name) {
        for (MetricStatus s : values()) {
            if (s.wireName.equals(name)) {
                return s;
            }
        }
        throw new IllegalArgumentException("未知 status：" + name);
    }
}
