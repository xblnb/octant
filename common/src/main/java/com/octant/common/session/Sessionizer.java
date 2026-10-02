package com.octant.common.session;

import com.octant.common.SuppressionPolicy;
import com.octant.common.model.EventType;
import com.octant.common.model.RawEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class Sessionizer {

    private final long idleGapSeconds;
    private final double afkDensityPerMinute;

    public Sessionizer() {
        this(SuppressionPolicy.IDLE_GAP_THRESHOLD, SuppressionPolicy.AFK_DENSITY_PER_MIN);
    }

    public Sessionizer(long idleGapSeconds, double afkDensityPerMinute) {
        this.idleGapSeconds = idleGapSeconds;
        this.afkDensityPerMinute = afkDensityPerMinute;
    }

    public List<Session> sessionize(List<RawEvent> events) {
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        List<RawEvent> sorted = new ArrayList<>(events);
        sorted.sort(Comparator.comparingLong(RawEvent::tick));

        List<Session> sessions = new ArrayList<>();
        int i = 0;
        while (i < sorted.size()) {
            int start = i;
            long active = 0;
            long prev = sorted.get(i).second();
            boolean closed = sorted.get(i).type() == EventType.LOGOUT;
            int count = 1;
            int j = i + 1;
            while (j < sorted.size()) {
                long cur = sorted.get(j).second();
                long gap = cur - prev;
                if (gap > idleGapSeconds) {
                    break;
                }
                active += gap;
                prev = cur;
                count++;
                if (sorted.get(j).type() == EventType.LOGOUT) {
                    closed = true;
                    j++;
                    break;
                }
                j++;
            }
            long startSecond = sorted.get(start).second();
            long endSecond = prev;
            Session session = build(startSecond, endSecond, active, count, !closed);
            sessions.add(session);
            i = j;
        }
        return List.copyOf(sessions);
    }

    private Session build(long start, long end, long active, int count, boolean open) {
        long wall = end - start;
        double density = wall <= 0 ? count : count * 60.0d / wall;
        boolean afk = density < afkDensityPerMinute;
        return new Session(start, end, active, count, afk, open);
    }
}
