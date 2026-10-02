package com.octant.pipeline.kite;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class KiteSampleSize {

    private final long activeSeconds;
    private final int samplePoints;
    private final String basis;

    private KiteSampleSize(long activeSeconds, int samplePoints, String basis) {
        if (activeSeconds < 0L) {
            throw new IllegalArgumentException("sampleSize.activeSeconds 不得为负：" + activeSeconds);
        }
        if (samplePoints < 0) {
            throw new IllegalArgumentException("sampleSize.samplePoints 不得为负：" + samplePoints);
        }
        this.activeSeconds = activeSeconds;
        this.samplePoints = samplePoints;
        this.basis = Objects.requireNonNull(basis, "basis");
        if (!KiteConstants.BASIS_ACTIVE_SECONDS.equals(basis)
                && !KiteConstants.BASIS_SAMPLE_POINTS.equals(basis)) {
            throw new IllegalArgumentException("sampleSize.basis 必须 ∈ {"
                    + KiteConstants.BASIS_ACTIVE_SECONDS + ", " + KiteConstants.BASIS_SAMPLE_POINTS
                    + "}，实际 " + basis);
        }
    }

    public static KiteSampleSize ofActiveSeconds(long activeSeconds, int samplePoints) {
        return new KiteSampleSize(activeSeconds, samplePoints, KiteConstants.BASIS_ACTIVE_SECONDS);
    }

    public static KiteSampleSize ofSamplePoints(long activeSeconds, int samplePoints) {
        return new KiteSampleSize(activeSeconds, samplePoints, KiteConstants.BASIS_SAMPLE_POINTS);
    }

    public long basisValue() {
        return KiteConstants.BASIS_SAMPLE_POINTS.equals(basis) ? samplePoints : activeSeconds;
    }

    public long activeSeconds() {
        return activeSeconds;
    }

    public int samplePoints() {
        return samplePoints;
    }

    public String basis() {
        return basis;
    }

    public Map<String, Object> asMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("activeSeconds", activeSeconds);
        m.put("samplePoints", (long) samplePoints);
        m.put("basis", basis);
        return Collections.unmodifiableMap(m);
    }

    public String toJson() {
        return "{\"activeSeconds\":" + activeSeconds + ",\"basis\":\"" + basis
                + "\",\"samplePoints\":" + samplePoints + "}";
    }

    @Override
    public String toString() {
        return toJson();
    }
}
