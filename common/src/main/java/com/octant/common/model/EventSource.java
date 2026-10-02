package com.octant.common.model;

import java.util.Optional;

public enum EventSource {

    FORGE("forge"),
    NEOFORGE("neoforge"),
    FABRIC("fabric");

    private final String code;

    EventSource(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static Optional<EventSource> byCode(String code) {
        for (EventSource s : values()) {
            if (s.code.equals(code)) {
                return Optional.of(s);
            }
        }
        return Optional.empty();
    }

    @Override
    public String toString() {
        return code;
    }
}
