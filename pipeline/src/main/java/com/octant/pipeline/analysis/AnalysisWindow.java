package com.octant.pipeline.analysis;

import java.util.List;
import java.util.Objects;

public record AnalysisWindow(
        String windowId,
        String kind,
        long startTick,
        long endTick,
        int startDayIndex,
        int endDayIndex,
        long durationMs,
        List<String> sessionIds) {

    public static final List<String> KINDS =
            List.of("all", "session", "active", "game_day", "rolling", "prev_k", "phase");

    public AnalysisWindow {
        Objects.requireNonNull(windowId, "windowId 必填");
        Objects.requireNonNull(kind, "kind 必填");
        if (!KINDS.contains(kind)) {
            throw new IllegalArgumentException("非法 window.kind：" + kind + "；允许值 " + KINDS);
        }
        if (endTick < startTick) {
            throw new IllegalArgumentException("window.endTick 必须 ≥ startTick");
        }
        if (durationMs < 0L) {
            throw new IllegalArgumentException("window.durationMs 必须 ≥ 0");
        }
        sessionIds = sessionIds == null ? List.of() : List.copyOf(sessionIds);
    }

    public static AnalysisWindow all(long startTick, long endTick, int startDay, int endDay,
                                     long durationMs, List<String> sessionIds) {
        return new AnalysisWindow("W_ALL", "all", startTick, endTick, startDay, endDay, durationMs, sessionIds);
    }

    public static AnalysisWindow active(long startTick, long endTick, int startDay, int endDay,
                                        long durationMs, List<String> sessionIds) {
        return new AnalysisWindow("W_ACTIVE", "active", startTick, endTick, startDay, endDay, durationMs,
                sessionIds);
    }

    public java.util.Map<String, Object> asMap() {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("windowId", windowId);
        m.put("kind", kind);
        m.put("startTick", startTick);
        m.put("endTick", endTick);
        m.put("startDayIndex", (long) startDayIndex);
        m.put("endDayIndex", (long) endDayIndex);
        m.put("durationMs", durationMs);
        m.put("sessionIds", List.copyOf(sessionIds));
        return m;
    }
}
