package com.octant.pipeline.kite;

public enum KiteStatus {
    AVAILABLE("available"),
    SUPPRESSED("suppressed");

    private final String wireName;

    KiteStatus(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
