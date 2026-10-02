package com.octant.common;

public final class SuppressionPolicy {

    public static final long MIN_ACTIVE_S = 3_600L;

    public static final int MIN_SESSION_N = 5;

    public static final int MIN_CONTENT_UNIT = 3;

    public static final int MIN_CLUSTER_N = 5;

    public static final long MIN_DWELL_S = 600L;

    public static final long IDLE_GAP_THRESHOLD = 300L;

    public static final double AFK_DENSITY_PER_MIN = 0.5d;

    public static final double AFK_SUSPECT_RATIO = 0.30d;

    public static final long DISPERSION_PREMATURE_S = 36_000L;

    public static final int MIN_SESSION_N_FATIGUE = Math.max(6, MIN_SESSION_N);

    private SuppressionPolicy() {
    }
}
