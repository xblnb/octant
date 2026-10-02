package com.octant.common.model;

import java.util.Optional;

public enum EventCategory {

    SESSION("session"),
    PROGRESS("progress"),
    EXPLORE("explore"),
    COMBAT("combat"),
    ITEMS("items"),
    MACHINE("machine"),
    STALL("stall");

    private final String code;

    EventCategory(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static Optional<EventCategory> byCode(String code) {
        for (EventCategory c : values()) {
            if (c.code.equals(code)) {
                return Optional.of(c);
            }
        }
        return Optional.empty();
    }

    @Override
    public String toString() {
        return code;
    }
}
