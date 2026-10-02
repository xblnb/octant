package com.octant.common.model;

public final class RawEventSchema {

    public static final String VERSION = "raw-event-schema@1.0.0";

    public static final String ENV_SNAPSHOT_SCHEMA_VERSION = "env-snapshot@1.0.0";

    public static final int TICK_MS = CaptureEvent.TICK_MS;

    public static final long IDLE_GAP_THRESHOLD_MS = 300_000L;

    public static final long SESSION_SPLIT_GAP_MS = 1_800_000L;

    public static final long SESSION_MIN_MS = 60_000L;

    public static final long OPEN_SESSION_CLOSE_GAP_MS = 1_800_000L;

    public static final long SESSION_MAX_TREL_MS = CaptureEvent.SESSION_MAX_TREL_MS;

    public static final long HEARTBEAT_ACTIVE_MS = 60_000L;

    public static final long HEARTBEAT_IDLE_MS = 600_000L;

    public static final long APM_WINDOW_MS = 60_000L;

    public static final int SESSION_FILE_SPLIT_N = 20_000;

    public static final long MIN_INTERVAL_ITEM_ACTION_MS = 60_000L;

    public static final long MIN_INTERVAL_CONTAINER_OBSERVATION_MS = 300_000L;

    public static final long MIN_INTERVAL_STALL_SEGMENT_MS = 60_000L;

    public static final int MAX_EVENT_BYTES = 2_048;

    public static final long WORLD_STORAGE_CAP_BYTES = 268_435_456L;

    public static final double STORAGE_SOFT_RATIO = 0.90d;

    public static final int RETENTION_MAX_AGE_DAYS = 180;

    public static final long GROWTH_LIMIT_BYTES_PER_ACTIVE_HOUR = 2_097_152L;

    public static final long EVICTION_CHECK_INTERVAL_MS = 600_000L;

    public static final double EVICTION_MAX_FRACTION = 0.50d;

    public static final String IF_FULL_POLICY = "drop_oldest";

    public static final int SAMPLE_EVERY_NTH_OBSERVATION = 1;

    private RawEventSchema() {
    }
}
