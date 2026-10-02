package com.octant.common.session;

public record Session(long startSecond,
                      long endSecond,
                      long activeSeconds,
                      int eventCount,
                      boolean afkSuspect,
                      boolean openSession) {

    public Session {
        if (endSecond < startSecond) {
            throw new IllegalArgumentException("会话结束时间早于开始时间");
        }
        if (activeSeconds < 0) {
            throw new IllegalArgumentException("活跃时长不得为负");
        }
    }

    public long wallSeconds() {
        return endSecond - startSecond;
    }

    public double densityPerMinute() {
        long wall = wallSeconds();
        if (wall <= 0) {
            return eventCount;
        }
        return eventCount * 60.0d / wall;
    }

    public boolean isEffective() {
        return activeSeconds > 0 && !afkSuspect;
    }
}
