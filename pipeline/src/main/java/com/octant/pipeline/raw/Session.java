package com.octant.pipeline.raw;

import java.util.Objects;

public record Session(
        String sessionId,
        String physicalSessionId,
        long startTRelMs,
        long endTRelMs,
        long wallMs,
        long activeMs,
        long afkMs,
        boolean afkSuspect,
        boolean openSession,
        String closeCause,
        int inputEvents,
        int totalEvents,
        int part) {

    public Session {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(physicalSessionId, "physicalSessionId");
        Objects.requireNonNull(closeCause, "closeCause");
        if (wallMs < 0L || activeMs < 0L) {
            throw new IllegalArgumentException("时长必须 ≥ 0");
        }
        if (activeMs > wallMs) {
            throw new IllegalArgumentException("activeMs 不得超过 wallMs：" + activeMs + " > " + wallMs);
        }
    }

    public double activeMinutes() {
        return activeMs / 60_000.0d;
    }

    public boolean effective() {
        return activeMs >= EventStream.MIN_EFFECTIVE_SESSION_MS;
    }

    public Session withClose(String cause, boolean open) {
        return new Session(sessionId, physicalSessionId, startTRelMs, endTRelMs, wallMs, activeMs, afkMs,
                afkSuspect, open, cause, inputEvents, totalEvents, part);
    }
}
