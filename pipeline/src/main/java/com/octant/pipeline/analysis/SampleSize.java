package com.octant.pipeline.analysis;

import com.octant.pipeline.feature.SampleBasis;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record SampleSize(
        long eventCount,
        int sessionCount,
        int playerCount,
        Integer unitCount,
        List<String> metricIds,
        SampleBasis basis,
        long minRequired,
        String minRequiredUnit,
        long activeSeconds) {

    public SampleSize {
        if (playerCount < 1) {
            throw new IllegalArgumentException("sampleSize.playerCount 必须 ≥ 1（禁止用会话数替代，privacy §7.2）");
        }
        if (eventCount < 0L || sessionCount < 0 || activeSeconds < 0L) {
            throw new IllegalArgumentException("sampleSize 计数不得为负");
        }
        if (unitCount != null && unitCount < 0) {
            throw new IllegalArgumentException("sampleSize.unitCount 不得为负");
        }
        if (minRequired < 0L) {
            throw new IllegalArgumentException("sampleSize.minRequired 不得为负");
        }
        metricIds = metricIds == null ? List.of() : List.copyOf(metricIds);
        if (minRequiredUnit == null || minRequiredUnit.isEmpty()) {
            throw new IllegalArgumentException("sampleSize.minRequiredUnit 必填");
        }
    }

    public static SampleSize of(long eventCount, int sessionCount, Integer unitCount,
                                List<String> metricIds, SampleBasis basis, long minRequired,
                                long activeSeconds) {
        return new SampleSize(eventCount, sessionCount, 1, unitCount, metricIds, basis, minRequired,
                basis.unitLabel(), activeSeconds);
    }

    public long currentSample() {
        return switch (basis) {
            case EVENTS -> eventCount;
            case SESSIONS -> sessionCount;
            case ACTIVE_TIME -> activeSeconds;
            case UNITS -> unitCount == null ? 0L : unitCount;
        };
    }

    public boolean belowMinimum() {
        return currentSample() < minRequired;
    }

    public Map<String, Object> asMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("eventCount", eventCount);
        m.put("sessionCount", (long) sessionCount);
        m.put("playerCount", (long) playerCount);
        if (unitCount != null) {
            m.put("unitCount", (long) unitCount);
        }
        if (!metricIds.isEmpty()) {
            m.put("metricIds", List.copyOf(metricIds));
        }
        m.put("sampleBasis", basis.wireName());
        m.put("minRequired", minRequired);
        m.put("minRequiredUnit", minRequiredUnit);
        return Collections.unmodifiableMap(m);
    }
}
