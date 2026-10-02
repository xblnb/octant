package com.octant.pipeline.raw;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class EventStream {

    public static final long IDLE_GAP_THRESHOLD_MS = 300_000L;
    public static final long MIN_EFFECTIVE_SESSION_MS = 60_000L;

    private final List<RawEvent> events;
    private final List<Session> sessions;
    private final int deduplicatedCount;
    private final int discardedCount;

    private final long declaredPlaySeconds;

    private EventStream(List<RawEvent> events, List<Session> sessions, int deduplicatedCount,
                        int discardedCount) {
        this(events, sessions, deduplicatedCount, discardedCount, 0L);
    }

    private EventStream(List<RawEvent> events, List<Session> sessions, int deduplicatedCount,
                        int discardedCount, long declaredPlaySeconds) {
        this.events = Collections.unmodifiableList(events);
        this.sessions = Collections.unmodifiableList(sessions);
        this.deduplicatedCount = deduplicatedCount;
        this.discardedCount = discardedCount;
        this.declaredPlaySeconds = Math.max(0L, declaredPlaySeconds);
    }

    public EventStream withDeclaredPlaySeconds(long seconds) {
        return new EventStream(events, sessions, deduplicatedCount, discardedCount, seconds);
    }

    public long declaredPlaySeconds() {
        return declaredPlaySeconds;
    }

    public static EventStream of(List<RawEvent> input) {
        List<RawEvent> sorted = new ArrayList<>(input == null ? List.of() : input);
        sorted.sort(Comparator.comparing(RawEvent::sessionId)
                .thenComparingLong(RawEvent::tRelMs)
                .thenComparing(RawEvent::eventId));

        List<RawEvent> kept = new ArrayList<>(sorted.size());
        Set<String> seen = new HashSet<>();
        int dedup = 0;
        int discarded = 0;
        Map<String, Long> lastAccepted = new LinkedHashMap<>();
        for (RawEvent e : sorted) {
            long gap = e.type().minWriteGapMs();
            if (gap > 0L) {
                String throttleKey = e.sessionId() + "|" + e.type().wireName();
                Long last = lastAccepted.get(throttleKey);
                if (last != null && e.tRelMs() - last < gap) {
                    discarded++;
                    continue;
                }
            }
            String uniq = uniquenessKey(e);
            if (uniq != null && !seen.add(uniq)) {
                dedup++;
                continue;
            }
            if (gap > 0L) {
                lastAccepted.put(e.sessionId() + "|" + e.type().wireName(), e.tRelMs());
            }
            kept.add(e);
        }

        List<RawEvent> normalized = new ArrayList<>();
        List<Session> sessions = splitSessions(kept, normalized);
        return new EventStream(normalized, sessions, dedup, discarded);
    }

    private static String uniquenessKey(RawEvent e) {
        return switch (e.type()) {
            case ADVANCEMENT_GAINED -> e.sessionId() + "|adv|" + e.str("advancementId");
            case QUEST_COMPLETED -> "quest|" + e.str("questId");
            case BIOME_VISITED -> e.sessionId() + "|biome|" + e.str("biome");
            case STRUCTURE_ENTERED -> e.sessionId() + "|struct|" + e.str("structure") + "|" + e.str("regionKey");
            case REGION_FIRST_VISIT -> e.sessionId() + "|region|" + e.str("regionKey");
            case RECIPE_UNLOCKED -> e.sessionId() + "|recipe|" + e.str("recipeId");
            case STALL_SEGMENT -> e.sessionId() + "|stall|" + e.str("unitId");
            default -> null;
        };
    }

    private static List<Session> splitSessions(List<RawEvent> kept, List<RawEvent> normalized) {
        Map<String, List<RawEvent>> bySession = new LinkedHashMap<>();
        for (RawEvent e : kept) {
            bySession.computeIfAbsent(e.sessionId(), k -> new ArrayList<>()).add(e);
        }
        List<Session> sessions = new ArrayList<>();
        for (Map.Entry<String, List<RawEvent>> entry : bySession.entrySet()) {
            List<RawEvent> stream = entry.getValue();
            stream.sort(Comparator.comparingLong(RawEvent::tRelMs).thenComparing(RawEvent::eventId));
            int part = 0;
            int start = 0;
            for (int i = 1; i <= stream.size(); i++) {
                boolean cut = i == stream.size()
                        || stream.get(i).tRelMs() - stream.get(i - 1).tRelMs() > IDLE_GAP_THRESHOLD_MS;
                if (!cut) {
                    continue;
                }
                List<RawEvent> slice = stream.subList(start, i);
                if (!slice.isEmpty()) {
                    sessions.add(buildSession(entry.getKey(), part, slice, normalized));
                    part++;
                }
                start = i;
            }
        }
        sessions.sort(Comparator.comparingLong(Session::startTRelMs)
                .thenComparing(Session::sessionId)
                .thenComparingInt(Session::part));
        return sessions;
    }

    private static Session buildSession(String sessionId, int part, List<RawEvent> slice, List<RawEvent> out) {
        String sid = part == 0 ? sessionId : sessionId + "-p" + part;
        long start = slice.get(0).tRelMs();
        long end = slice.get(slice.size() - 1).tRelMs();
        for (RawEvent e : slice) {
            out.add(e);
        }
        long activeMs = 0L;
        int inputEvents = 0;
        boolean afkSuspect = false;
        RawEvent lastConfirmed = null;
        for (int i = 0; i < slice.size(); i++) {
            RawEvent e = slice.get(i);
            if (e.confirmed()) {
                inputEvents++;
                lastConfirmed = e;
            }
            if (e.type() == EventType.SESSION_HEARTBEAT) {
                String activity = e.str("activity");
                if ("idle".equals(activity)) {
                    afkSuspect = true;
                }
            }
        }
        if (lastConfirmed != null) {
            activeMs = Math.max(0L, lastConfirmed.tRelMs() - start);
            long span = Math.max(0L, end - start);
            if (afkSuspect) {
                activeMs = Math.min(activeMs, span);
            }
        }
        boolean openSession = slice.stream().noneMatch(e -> e.type() == EventType.SESSION_END);
        String closeCause = openSession ? "crash_recovery" : "logout";
        long wallMs = Math.max(0L, end - start);
        return new Session(sid, sessionId, start, end, wallMs, activeMs, wallMs - activeMs,
                afkSuspect, openSession, closeCause, inputEvents, slice.size(), part);
    }

    public List<RawEvent> events() {
        return events;
    }

    public List<Session> sessions() {
        return sessions;
    }

    public int sessionCount() {
        return sessions.size();
    }

    public int effectiveSessionCount() {
        return effectiveSessions().size();
    }

    public int deduplicatedCount() {
        return deduplicatedCount;
    }

    public int discardedCount() {
        return discardedCount;
    }

    public List<RawEvent> ofType(EventType type) {
        List<RawEvent> out = new ArrayList<>();
        for (RawEvent e : events) {
            if (e.type() == type) {
                out.add(e);
            }
        }
        return out;
    }

    public int count(EventType type) {
        int n = 0;
        for (RawEvent e : events) {
            if (e.type() == type) {
                n++;
            }
        }
        return n;
    }

    public long totalActiveSeconds() {
        if (declaredPlaySeconds > 0L) {
            return declaredPlaySeconds;
        }
        return derivedActiveSeconds();
    }

    public long derivedActiveSeconds() {
        return derivedActiveMs() / 1000L;
    }

    public long totalActiveMs() {
        if (declaredPlaySeconds > 0L) {
            return declaredPlaySeconds * 1000L;
        }
        return derivedActiveMs();
    }

    private long derivedActiveMs() {
        long ms = 0L;
        for (Session s : sessions) {
            ms += s.activeMs();
        }
        return ms;
    }

    public long totalWallMs() {
        long ms = 0L;
        for (Session s : sessions) {
            ms += s.wallMs();
        }
        return ms;
    }

    public List<Session> effectiveSessions() {
        List<Session> out = new ArrayList<>();
        for (Session s : sessions) {
            if (s.effective()) {
                out.add(s);
            }
        }
        return out;
    }

    public Map<Integer, List<Session>> byRelativeDay() {
        Map<Integer, List<Session>> out = new LinkedHashMap<>();
        int day = -1;
        long lastEnd = Long.MIN_VALUE;
        for (Session s : sessions) {
            if (lastEnd == Long.MIN_VALUE || s.startTRelMs() - lastEnd > IDLE_GAP_THRESHOLD_MS * 4L) {
                day++;
            } else if (day < 0) {
                day = 0;
            }
            out.computeIfAbsent(Math.max(day, 0), k -> new ArrayList<>()).add(s);
            lastEnd = s.endTRelMs();
        }
        return out;
    }

    public int relativeDayCount() {
        return byRelativeDay().size();
    }

    public List<RawEvent> eventsOf(Session session) {
        List<RawEvent> out = new ArrayList<>();
        for (RawEvent e : events) {
            if (e.sessionId().equals(session.sessionId())) {
                out.add(e);
            }
        }
        return out;
    }

    public double afkShare() {
        long total = totalActiveMs();
        if (total <= 0L) {
            return 0.0d;
        }
        long afk = 0L;
        for (Session s : sessions) {
            if (s.afkSuspect()) {
                afk += s.activeMs();
            }
        }
        return (double) afk / (double) total;
    }

    public int distinctSessionCount() {
        Set<String> ids = new LinkedHashSet<>();
        for (RawEvent e : events) {
            ids.add(e.sessionId());
        }
        return ids.size();
    }
}
