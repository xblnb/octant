package com.octant.pipeline.raw;

import java.util.List;

public final class Sessionizer {

    public EventStream sessionize(List<RawEvent> events) {
        return EventStream.of(events);
    }
}
