package com.octant.pipeline.feature;

import com.octant.pipeline.raw.RawEvent;

import java.util.function.Predicate;

@FunctionalInterface
public interface EventFilter extends Predicate<RawEvent> {

    EventFilter ALL = e -> true;

    EventFilter CONFIRMED = RawEvent::confirmed;

    static EventFilter field(String key, String value) {
        return e -> value.equals(e.str(key));
    }

    static EventFilter flag(String key) {
        return e -> e.bool(key);
    }

    static EventFilter numberAtLeast(String key, double min) {
        return e -> e.has(key) && e.num(key) >= min;
    }

    default EventFilter and(EventFilter other) {
        return e -> this.test(e) && other.test(e);
    }

    default EventFilter or(EventFilter other) {
        return e -> this.test(e) || other.test(e);
    }
}
