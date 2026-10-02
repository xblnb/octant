package com.octant.pipeline.raw;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

public enum EventType {

    SESSION_START("session_start", "session", false, 0L),
    SESSION_END("session_end", "session", false, 0L),
    SESSION_HEARTBEAT("session_heartbeat", "session", false, 60_000L),
    SESSION_ENVIRONMENT("session_environment", "session", false, 0L),

    ADVANCEMENT_GAINED("advancement_gained", "progress", false, 0L),
    QUEST_COMPLETED("quest_completed", "progress", false, 0L),
    QUEST_PROGRESSED("quest_progressed", "progress", false, 0L),

    DIMENSION_ENTERED("dimension_entered", "explore", false, 0L),
    BIOME_VISITED("biome_visited", "explore", false, 0L),
    STRUCTURE_ENTERED("structure_entered", "explore", false, 0L),
    REGION_FIRST_VISIT("region_first_visit", "explore", false, 0L),

    COMBAT_STARTED("combat_started", "combat", true, 0L),
    COMBAT_ENDED("combat_ended", "combat", true, 0L),
    PLAYER_DEATH("player_death", "combat", false, 0L),

    ITEM_ACTION("item_action", "items", false, 0L),
    RECIPE_UNLOCKED("recipe_unlocked", "items", false, 0L),
    RECIPE_ATTEMPTED("recipe_attempted", "items", false, 0L),
    CONTAINER_SNAPSHOT("container_snapshot", "items", false, 300_000L),

    MACHINE_OBSERVED("machine_observed", "machine", false, 300_000L),
    MACHINE_INTERACTED("machine_interacted", "machine", true, 0L),
    AUTOMATION_CYCLE("automation_cycle", "machine", false, 0L),

    STALL_SEGMENT("stall_segment", "stall", true, 60_000L),
    REPEATED_FAILURE("repeated_failure", "stall", false, 0L),
    RESOURCE_BLOCKED("resource_blocked", "stall", false, 0L);

    private static final Map<String, EventType> BY_WIRE_NAME = new LinkedHashMap<>();
    private static final Map<String, EventType> BY_CATEGORY = new LinkedHashMap<>();

    static {
        for (EventType t : values()) {
            BY_WIRE_NAME.put(t.wireName, t);
            BY_CATEGORY.putIfAbsent(t.category, t);
        }
    }

    private final String wireName;
    private final String category;
    private final boolean interval;
    private final long minWriteGapMs;

    EventType(String wireName, String category, boolean interval, long minWriteGapMs) {
        this.wireName = wireName;
        this.category = category;
        this.interval = interval;
        this.minWriteGapMs = minWriteGapMs;
    }

    public String wireName() {
        return wireName;
    }

    public String category() {
        return category;
    }

    public boolean interval() {
        return interval;
    }

    public long minWriteGapMs() {
        return minWriteGapMs;
    }

    public static EventType fromWireName(String name) {
        EventType t = BY_WIRE_NAME.get(name);
        if (t == null) {
            throw new IllegalArgumentException("未知事件类型：" + name
                    + "（允许值：" + BY_WIRE_NAME.keySet() + "）");
        }
        return t;
    }

    public static boolean isKnown(String name) {
        return BY_WIRE_NAME.containsKey(name);
    }

    public static java.util.List<String> wireNames() {
        return Arrays.stream(values()).map(EventType::wireName).toList();
    }
}
