package com.octant.common.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public enum CaptureEventType {

    SESSION_START("session_start", EventCategory.SESSION),
    SESSION_END("session_end", EventCategory.SESSION),
    SESSION_HEARTBEAT("session_heartbeat", EventCategory.SESSION),
    SESSION_ENVIRONMENT("session_environment", EventCategory.SESSION),
    ADVANCEMENT_GAINED("advancement_gained", EventCategory.PROGRESS),
    QUEST_COMPLETED("quest_completed", EventCategory.PROGRESS),
    QUEST_PROGRESSED("quest_progressed", EventCategory.PROGRESS),

    DIMENSION_ENTERED("dimension_entered", EventCategory.EXPLORE),
    BIOME_VISITED("biome_visited", EventCategory.EXPLORE),
    STRUCTURE_ENTERED("structure_entered", EventCategory.EXPLORE),
    REGION_FIRST_VISIT("region_first_visit", EventCategory.EXPLORE),

    COMBAT_STARTED("combat_started", EventCategory.COMBAT),
    COMBAT_ENDED("combat_ended", EventCategory.COMBAT),
    PLAYER_DEATH("player_death", EventCategory.COMBAT),

    ITEM_ACTION("item_action", EventCategory.ITEMS),
    RECIPE_UNLOCKED("recipe_unlocked", EventCategory.ITEMS),
    RECIPE_ATTEMPTED("recipe_attempted", EventCategory.ITEMS),
    CONTAINER_SNAPSHOT("container_snapshot", EventCategory.ITEMS),

    MACHINE_OBSERVED("machine_observed", EventCategory.MACHINE),
    MACHINE_INTERACTED("machine_interacted", EventCategory.MACHINE),
    AUTOMATION_CYCLE("automation_cycle", EventCategory.MACHINE),

    STALL_SEGMENT("stall_segment", EventCategory.STALL),
    REPEATED_FAILURE("repeated_failure", EventCategory.STALL),
    RESOURCE_BLOCKED("resource_blocked", EventCategory.STALL);

    public static final int CONTRACT_TYPE_COUNT = 24;

    private static final Map<String, CaptureEventType> BY_ID;

    static {
        Map<String, CaptureEventType> byId = new LinkedHashMap<>();
        for (CaptureEventType t : values()) {
            if (byId.put(t.eventId, t) != null) {
                throw new IllegalStateException("事件类型 ID 重复：" + t.eventId);
            }
        }
        if (byId.size() != CONTRACT_TYPE_COUNT) {
            throw new IllegalStateException(
                    "事件类型数量与 dc §2.4 不符：期望 " + CONTRACT_TYPE_COUNT + "，实际 " + byId.size());
        }
        BY_ID = Collections.unmodifiableMap(byId);
    }

    private final String eventId;
    private final EventCategory category;

    CaptureEventType(String eventId, EventCategory category) {
        this.eventId = eventId;
        this.category = category;
    }

    public String eventId() {
        return eventId;
    }

    public EventCategory category() {
        return category;
    }

    public boolean isInterval() {
        return this == COMBAT_STARTED || this == COMBAT_ENDED
                || this == MACHINE_INTERACTED || this == STALL_SEGMENT;
    }

    public static Optional<CaptureEventType> byEventId(String eventId) {
        return Optional.ofNullable(BY_ID.get(eventId));
    }

    public static CaptureEventType require(String eventId) {
        CaptureEventType t = BY_ID.get(eventId);
        if (t == null) {
            throw new ContractException("未知事件类型（dc §2.2：必须丢弃并计入 unknownType）：" + eventId);
        }
        return t;
    }

    public static Map<String, CaptureEventType> all() {
        return BY_ID;
    }

    @Override
    public String toString() {
        return eventId;
    }
}
