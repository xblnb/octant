package com.octant.common.model;

import com.octant.common.AttributionPriority;
import com.octant.common.ContentAxis;

import java.util.Objects;

public final class ContentUnit {

    public static final long BIOME_TOUCH_S = 300L;

    public static final int BLOCK_TOUCH_COUNT = 16;

    private final ContentAxis axis;
    private final String key;
    private final AttributionPriority priority;
    private final long activeSeconds;
    private final int eventCount;
    private final int interactionCount;

    private ContentUnit(ContentAxis axis, String key, AttributionPriority priority,
                        long activeSeconds, int eventCount, int interactionCount) {
        this.axis = Objects.requireNonNull(axis, "axis");
        this.key = Objects.requireNonNull(key, "key");
        this.priority = Objects.requireNonNull(priority, "priority");
        this.activeSeconds = activeSeconds;
        this.eventCount = eventCount;
        this.interactionCount = interactionCount;
    }

    public static ContentUnit of(ContentAxis axis, String key, AttributionPriority priority,
                                long activeSeconds, int eventCount) {
        return new ContentUnit(axis, key, priority, activeSeconds, eventCount, eventCount);
    }

    public static ContentUnit withInteractions(ContentAxis axis, String key, AttributionPriority priority,
                                               long activeSeconds, int eventCount, int interactionCount) {
        return new ContentUnit(axis, key, priority, activeSeconds, eventCount, interactionCount);
    }

    public ContentAxis axis() {
        return axis;
    }

    public String key() {
        return key;
    }

    public AttributionPriority priority() {
        return priority;
    }

    public long activeSeconds() {
        return activeSeconds;
    }

    public int eventCount() {
        return eventCount;
    }

    public int interactionCount() {
        return interactionCount;
    }

    public boolean meetsTouchThreshold() {
        return switch (axis) {
            case DIMENSION -> activeSeconds >= com.octant.common.SuppressionPolicy.MIN_DWELL_S;
            case BIOME -> activeSeconds >= BIOME_TOUCH_S;
            case STRUCTURE -> eventCount >= 1 && interactionCount >= 1;
            case ITEM, MOB, QUEST -> eventCount >= 1;
            case RECIPE -> eventCount >= 1;
            case BLOCK -> interactionCount >= BLOCK_TOUCH_COUNT;
            case MACHINE -> activeSeconds >= com.octant.common.SuppressionPolicy.MIN_DWELL_S;
        };
    }

    public static String touchRule(ContentAxis axis) {
        return switch (axis) {
            case DIMENSION -> "在该维度活跃 ≥ 10 min";
            case BIOME -> "在该群系活跃 ≥ 5 min";
            case STRUCTURE -> "进入包围盒 ≥ 1 次且 ≥ 1 次交互";
            case ITEM -> "获得 ≥ 1 次且非创造给予";
            case RECIPE -> "成功合成 ≥ 1 次";
            case MOB -> "与其实战（进入交战）≥ 1 次";
            case BLOCK -> "放置或破坏 ≥ 16 个";
            case MACHINE -> "连续运行 ≥ 10 min";
            case QUEST -> "完成 ≥ 1 个该章节条目";
        };
    }

    @Override
    public String toString() {
        return axis + ":" + key + (meetsTouchThreshold() ? " [touched]" : " [below-threshold]");
    }
}
