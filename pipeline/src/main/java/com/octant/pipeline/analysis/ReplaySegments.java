package com.octant.pipeline.analysis;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ReplaySegments {

    public static final long MAX_SEGMENT_MS = 180_000L;
    public static final long MERGE_GAP_MS = 30_000L;
    public static final int MIN_MARKS = 4;

    public static final long MIN_SEGMENT_MS = 2_000L;
    public static final int MAX_PER_SESSION = 3;
    public static final int MAX_PER_REPORT = 12;

    private ReplaySegments() {
    }

    public enum Kind {
        DEATH("死亡", 100, true),
        COMBAT_ANOMALY("战斗异常", 80, true),
        REPEATED_FAILURE("反复失败", 60, true),
        STALL("停滞", 40, true),
        FIRST_CONTACT("首次触达", 20, false);

        private final String label;
        private final int strength;
        private final boolean strong;

        Kind(String label, int strength, boolean strong) {
            this.label = label;
            this.strength = strength;
            this.strong = strong;
        }

        public String label() {
            return label;
        }

        public int strength() {
            return strength;
        }

        public boolean strong() {
            return strong;
        }
    }

    public record Mark(String sessionId, long startMs, long endMs, Kind kind, String why,
                       List<String> evidence) {
    }

    public record Segment(String sessionId, long startMs, long endMs, Kind kind,
                          List<String> reasons, List<String> evidence, int marks) {

        public long lengthMs() {
            return endMs - startMs;
        }
    }

    public record Selection(List<Segment> segments, int sessionsWithSegments, int sessionTotal) {
    }

    public static Selection select(List<Mark> marks, int sessionTotal) {
        Map<String, List<Mark>> bySession = new LinkedHashMap<>();
        List<Mark> sorted = new ArrayList<>(marks);
        sorted.sort(Comparator.comparing(Mark::sessionId).thenComparing(Mark::startMs));
        for (Mark m : sorted) {
            bySession.computeIfAbsent(m.sessionId(), k -> new ArrayList<>()).add(m);
        }

        List<Segment> picked = new ArrayList<>();
        int withSegments = 0;
        for (Map.Entry<String, List<Mark>> en : bySession.entrySet()) {
            List<Segment> segs = selectForSession(en.getKey(), en.getValue());
            if (!segs.isEmpty()) {
                withSegments++;
            }
            picked.addAll(segs);
        }
        picked.sort(Comparator.comparingInt((Segment s) -> -s.kind().strength())
                .thenComparing(Segment::sessionId).thenComparingLong(Segment::startMs));
        if (picked.size() > MAX_PER_REPORT) {
            picked = new ArrayList<>(picked.subList(0, MAX_PER_REPORT));
        }
        return new Selection(List.copyOf(picked), withSegments, sessionTotal);
    }

    static List<Segment> selectForSession(String sessionId, List<Mark> marks) {
        List<Mark> byKind = new ArrayList<>(marks);
        byKind.sort(Comparator.comparing((Mark m) -> m.kind().ordinal()).thenComparingLong(Mark::startMs));

        List<Segment> out = new ArrayList<>();
        for (Mark m : byKind) {
            boolean merged = false;
            for (int i = out.size() - 1; i >= 0; i--) {
                Segment s = out.get(i);
                if (s.kind() != m.kind()) {
                    continue;
                }
                long ns = Math.min(s.startMs(), m.startMs());
                long ne = Math.max(s.endMs(), m.endMs());
                if (m.startMs() <= s.endMs() + MERGE_GAP_MS && ne - ns <= MAX_SEGMENT_MS) {
                    List<String> reasons = new ArrayList<>(s.reasons());
                    if (!reasons.contains(m.why())) {
                        reasons.add(m.why());
                    }
                    List<String> ev = new ArrayList<>(s.evidence());
                    for (String e : m.evidence()) {
                        if (!ev.contains(e)) {
                            ev.add(e);
                        }
                    }
                    out.set(i, new Segment(sessionId, ns, ne, s.kind(), reasons, ev, s.marks() + 1));
                    merged = true;
                    break;
                }
            }
            if (!merged) {
                out.add(new Segment(sessionId, m.startMs(), m.endMs(), m.kind(),
                        List.of(m.why()), List.copyOf(m.evidence()), 1));
            }
        }

        List<Segment> kept = new ArrayList<>();
        for (Segment s : out) {
            if ((s.marks() >= MIN_MARKS || s.kind().strong()) && s.lengthMs() >= MIN_SEGMENT_MS) {
                kept.add(s);
            }
        }
        kept.sort(Comparator.comparingInt((Segment s) -> -s.kind().strength())
                .thenComparingLong(Segment::startMs));
        return kept.size() > MAX_PER_SESSION ? new ArrayList<>(kept.subList(0, MAX_PER_SESSION)) : kept;
    }
}
