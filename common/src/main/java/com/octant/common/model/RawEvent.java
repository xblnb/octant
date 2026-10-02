package com.octant.common.model;

import java.util.Objects;

public final class RawEvent {

    private final EventType type;
    private final long tick;
    private final String subjectId;

    public RawEvent(EventType type, long tick, String subjectId) {
        this.type = Objects.requireNonNull(type, "type");
        this.tick = tick;
        this.subjectId = subjectId == null ? "" : subjectId;
    }

    public EventType type() {
        return type;
    }

    public long tick() {
        return tick;
    }

    public String subjectId() {
        return subjectId;
    }

    public long second() {
        return tick / 20L;
    }

    @Override
    public String toString() {
        return type + "@" + tick + (subjectId.isEmpty() ? "" : "(" + subjectId + ")");
    }
}
