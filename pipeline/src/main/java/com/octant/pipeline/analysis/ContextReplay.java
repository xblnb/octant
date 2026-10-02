package com.octant.pipeline.analysis;

import com.octant.pipeline.raw.RawEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ContextReplay {

    private ContextReplay() {
    }

    public enum State {
        ADVANCE("推进"),
        COMBAT("战斗"),
        EXPLORE("探索"),
        TIDY("整理与自动化"),
        UNOBSERVED("无观测");

        private final String label;

        State(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public static final long LONG_COMBAT_MS = 15_000L;

    public record SessionContext(String sessionId, long durationMs, Map<String, Long> lanes,
                                 List<String> laneOrder, int eventCount) {
    }

    public record Timeline(List<SessionContext> sessions, List<ReplaySegments.Segment> segments,
                           int sessionsWithSegments, int sessionTotal, String signalSource, String note) {
    }

    private static State declares(String wire) {
        if (wire == null) {
            return null;
        }
        switch (wire) {
            case "dimension_entered":
            case "biome_visited":
            case "structure_entered":
            case "region_first_visit":
                return State.EXPLORE;
            case "advancement_gained":
            case "quest_completed":
            case "quest_progressed":
            case "recipe_unlocked":
                return State.ADVANCE;
            case "combat_started":
            case "player_death":
                return State.COMBAT;
            case "combat_ended":
                return State.UNOBSERVED;
            case "item_action":
            case "recipe_attempted":
            case "container_snapshot":
            case "machine_observed":
            case "machine_interacted":
            case "automation_cycle":
                return State.TIDY;
            default:
                return null;
        }
    }

    private static final String COMBAT_START = "combat_started";
    private static final String COMBAT_END = "combat_ended";
    private static final String DEATH = "player_death";
    private static final String HEARTBEAT = "session_heartbeat";
    private static final String COLLECTOR_STALL = "stall_segment";
    private static final String COLLECTOR_FAILURE = "repeated_failure";

    public static final double STALL_HEARTBEAT_MULT = 3.0;

    public static Timeline replay(List<RawEvent> events) {
        if (events == null || events.isEmpty()) {
            return new Timeline(List.of(), List.of(), 0, 0, "none",
                    "事件流为空，没有可回放的会话；这一条不等于「没有值得看的东西」");
        }
        Map<String, List<RawEvent>> bySession = new LinkedHashMap<>();
        for (RawEvent e : events) {
            bySession.computeIfAbsent(e.sessionId(), k -> new ArrayList<>()).add(e);
        }
        List<SessionContext> sessions = new ArrayList<>();
        for (Map.Entry<String, List<RawEvent>> en : bySession.entrySet()) {
            sessions.add(context(en.getKey(), sorted(en.getValue())));
        }
        sessions.sort(Comparator.comparing(SessionContext::sessionId));

        List<ReplaySegments.Mark> marks = marks(bySession);
        ReplaySegments.Selection sel = ReplaySegments.select(marks, sessions.size());
        String source = signalSource(events);
        String note;
        if (marks.isEmpty()) {
            note = "事件流里没有可用于回放的信号：这一批事件只有记账类与触达类，"
                    + "推不出可回看的片段。这不是「没有值得看的东西」，是没有信号。";
        } else if (sel.segments().isEmpty()) {
            note = "有 " + marks.size() + " 个候选标记，但没有任何一段达到选取下限"
                    + "（段内标记 < " + ReplaySegments.MIN_MARKS + " 且非强信号），如实留空。";
        } else {
            note = "候选标记 " + marks.size() + " 个，选中 " + sel.segments().size() + " 段，覆盖 "
                    + sel.sessionsWithSegments() + " / " + sel.sessionTotal() + " 个会话。";
        }
        return new Timeline(sessions, sel.segments(), sel.sessionsWithSegments(), sel.sessionTotal(),
                source, note);
    }

    private static String signalSource(List<RawEvent> events) {
        boolean collector = false;
        for (RawEvent e : events) {
            String w = e.type().wireName();
            if (COLLECTOR_STALL.equals(w) || COLLECTOR_FAILURE.equals(w)) {
                collector = true;
                break;
            }
        }
        if (collector) {
            return "collector";
        }
        for (RawEvent e : events) {
            String w = e.type().wireName();
            if (COMBAT_START.equals(w) || HEARTBEAT.equals(w) || DEATH.equals(w)) {
                return "derived";
            }
        }
        return "none";
    }

    private static List<RawEvent> sorted(List<RawEvent> in) {
        List<RawEvent> evs = new ArrayList<>(in);
        evs.sort(Comparator.comparingLong(RawEvent::tRelMs).thenComparing(RawEvent::eventId));
        return evs;
    }

    private static SessionContext context(String sessionId, List<RawEvent> evs) {
        long duration = 0;
        for (RawEvent e : evs) {
            duration = Math.max(duration, e.tRelMs());
        }
        Map<State, Long> acc = new LinkedHashMap<>();
        for (State s : State.values()) {
            acc.put(s, 0L);
        }
        State cur = State.UNOBSERVED;
        long cursor = 0;
        for (RawEvent e : evs) {
            long t = Math.min(e.tRelMs(), duration);
            if (t > cursor) {
                acc.merge(cur, t - cursor, Long::sum);
                cursor = t;
            }
            State d = declares(e.type().wireName());
            if (d != null) {
                cur = d;
            }
        }
        if (duration > cursor) {
            acc.merge(cur, duration - cursor, Long::sum);
        }
        for (long[] c : combatIntervals(evs, duration)) {
            moveIntoCombat(acc, evs, Math.max(0, c[0]), Math.min(duration, c[1]));
        }
        Map<String, Long> lanes = new LinkedHashMap<>();
        List<String> order = new ArrayList<>();
        for (State s : State.values()) {
            order.add(s.label());
            lanes.put(s.label(), Math.max(0L, acc.getOrDefault(s, 0L)));
        }
        return new SessionContext(sessionId, duration, lanes, order, evs.size());
    }

    private static List<long[]> combatIntervals(List<RawEvent> evs, long duration) {
        List<long[]> out = new ArrayList<>();
        long open = -1;
        for (RawEvent e : evs) {
            String w = e.type().wireName();
            if (COMBAT_START.equals(w)) {
                if (open < 0) {
                    open = e.tRelMs();
                }
            } else if (COMBAT_END.equals(w) && open >= 0) {
                out.add(new long[]{open, e.tRelMs()});
                open = -1;
            }
        }
        if (open >= 0) {
            out.add(new long[]{open, Math.max(duration, open)});
        }
        return merge(out);
    }

    private static void moveIntoCombat(Map<State, Long> acc, List<RawEvent> evs, long from, long to) {
        if (to <= from) {
            return;
        }
        State cur = State.UNOBSERVED;
        long cursor = 0;
        long end = evs.isEmpty() ? 0 : evs.get(evs.size() - 1).tRelMs();
        List<long[]> pieces = new ArrayList<>();
        for (RawEvent e : evs) {
            long t = e.tRelMs();
            if (t > cursor) {
                pieces.add(new long[]{cursor, t, cur.ordinal()});
                cursor = t;
            }
            State d = declares(e.type().wireName());
            if (d != null) {
                cur = d;
            }
        }
        if (end > cursor) {
            pieces.add(new long[]{cursor, end, cur.ordinal()});
        }
        State[] vals = State.values();
        for (long[] p : pieces) {
            long s = Math.max(p[0], from);
            long e2 = Math.min(p[1], to);
            if (e2 <= s) {
                continue;
            }
            State st = vals[(int) p[2]];
            if (st == State.COMBAT) {
                continue;
            }
            long len = e2 - s;
            acc.merge(st, -len, Long::sum);
            acc.merge(State.COMBAT, len, Long::sum);
        }
    }

    private static List<long[]> merge(List<long[]> in) {
        if (in.isEmpty()) {
            return in;
        }
        List<long[]> s = new ArrayList<>(in);
        s.sort(Comparator.comparingLong(a -> a[0]));
        List<long[]> out = new ArrayList<>();
        long[] cur = {s.get(0)[0], s.get(0)[1]};
        for (int i = 1; i < s.size(); i++) {
            if (s.get(i)[0] <= cur[1]) {
                cur[1] = Math.max(cur[1], s.get(i)[1]);
            } else {
                out.add(cur);
                cur = new long[]{s.get(i)[0], s.get(i)[1]};
            }
        }
        out.add(cur);
        return out;
    }

    static List<ReplaySegments.Mark> marks(Map<String, List<RawEvent>> bySession) {
        boolean collectorSignals = false;
        for (List<RawEvent> evs : bySession.values()) {
            for (RawEvent e : evs) {
                String w = e.type().wireName();
                if (COLLECTOR_STALL.equals(w) || COLLECTOR_FAILURE.equals(w)) {
                    collectorSignals = true;
                    break;
                }
            }
        }
        List<ReplaySegments.Mark> out = new ArrayList<>();
        for (Map.Entry<String, List<RawEvent>> en : bySession.entrySet()) {
            String sid = en.getKey();
            List<RawEvent> evs = sorted(en.getValue());
            List<Long> heartbeats = new ArrayList<>();
            int combatRun = 0;
            long open = -1;
            String openEvidence = null;
            for (RawEvent e : evs) {
                String type = e.type().wireName();
                String ev = sid + "/" + e.eventId();
                if (DEATH.equals(type)) {
                    out.add(new ReplaySegments.Mark(sid, e.tRelMs(), e.tRelMs(), ReplaySegments.Kind.DEATH,
                            "这一幕里有死亡事件：死亡前后的几十秒通常是玩家最想回看的一段",
                            List.of(ev)));
                } else if (COLLECTOR_STALL.equals(type)) {
                    long len = Math.max(e.durMs(), 0);
                    out.add(new ReplaySegments.Mark(sid, e.tRelMs(), e.tRelMs() + len,
                            ReplaySegments.Kind.STALL,
                            "采集侧在这一段判定为停滞（" + (len / 1000) + " 秒）：这是**当时**判的，不是事后反推的",
                            List.of(ev)));
                } else if (COLLECTOR_FAILURE.equals(type)) {
                    out.add(new ReplaySegments.Mark(sid, e.tRelMs(), e.tRelMs(),
                            ReplaySegments.Kind.REPEATED_FAILURE,
                            "采集侧把这一段判为反复失败：同一处试了不止一次", List.of(ev)));
                } else if (HEARTBEAT.equals(type)) {
                    heartbeats.add(e.tRelMs());
                } else if (COMBAT_START.equals(type)) {
                    if (open < 0) {
                        open = e.tRelMs();
                        openEvidence = ev;
                    }
                } else if (COMBAT_END.equals(type) && open >= 0) {
                    long len = e.durMs() > 0 ? e.durMs() : Math.max(0, e.tRelMs() - open);
                    long winStart = Math.max(0, e.tRelMs() - len);
                    if (len >= LONG_COMBAT_MS) {
                        out.add(new ReplaySegments.Mark(sid, winStart, e.tRelMs(),
                                ReplaySegments.Kind.COMBAT_ANOMALY,
                                "这一场打了 " + (len / 1000) + " 秒（≥ " + (LONG_COMBAT_MS / 1000)
                                        + " 秒，超过采集侧对「一次遭遇」的时长线）",
                                openEvidence == null ? List.of(ev) : List.of(openEvidence, ev)));
                    }
                    combatRun++;
                    if (combatRun >= 3) {
                        out.add(new ReplaySegments.Mark(sid, winStart, e.tRelMs(),
                                ReplaySegments.Kind.REPEATED_FAILURE,
                                "这一串里连着 " + combatRun + " 场交战、中间没有任何推进，像反复尝试同一处",
                                List.of(ev)));
                        combatRun = 0;
                    }
                    open = -1;
                    openEvidence = null;
                } else if (declares(type) == State.ADVANCE) {
                    combatRun = 0;
                }
            }
            if (open >= 0) {
                long last = evs.get(evs.size() - 1).tRelMs();
                long len = 0;
                for (RawEvent e : evs) {
                    if (COMBAT_START.equals(e.type().wireName()) && e.tRelMs() == open) {
                        len = e.durMs();
                        break;
                    }
                }
                if (len <= 0) {
                    len = last - open;
                }
                if (len >= LONG_COMBAT_MS) {
                    out.add(new ReplaySegments.Mark(sid, Math.max(0, last - len), last,
                            ReplaySegments.Kind.COMBAT_ANOMALY,
                            "会话结束时这场交战还没闭合（已持续 " + (len / 1000)
                                    + " 秒）：可能是被打断或掉线，值得确认", List.of(sid + "/-")));
                }
            }
            if (!collectorSignals) {
                out.addAll(stallMarks(sid, heartbeats));
            }
        }
        out.sort(Comparator.comparing(ReplaySegments.Mark::sessionId)
                .thenComparingLong(ReplaySegments.Mark::startMs)
                .thenComparing(m -> m.kind().name()));
        return out;
    }

    private static List<ReplaySegments.Mark> stallMarks(String sid, List<Long> hb) {
        List<ReplaySegments.Mark> out = new ArrayList<>();
        if (hb.size() < 4) {
            return out;
        }
        List<Long> gaps = new ArrayList<>();
        for (int i = 1; i < hb.size(); i++) {
            gaps.add(hb.get(i) - hb.get(i - 1));
        }
        List<Long> sortedGaps = new ArrayList<>(gaps);
        sortedGaps.sort(Comparator.naturalOrder());
        long median = sortedGaps.get(sortedGaps.size() / 2);
        if (median <= 0) {
            return out;
        }
        long threshold = (long) (median * STALL_HEARTBEAT_MULT);
        for (int i = 0; i < gaps.size(); i++) {
            if (gaps.get(i) > threshold) {
                out.add(new ReplaySegments.Mark(sid, hb.get(i), hb.get(i + 1),
                        ReplaySegments.Kind.STALL,
                        "这里的心跳间隔 " + (gaps.get(i) / 1000) + " 秒，是该会话常态（中位 "
                                + (median / 1000) + " 秒）的 " + Math.round(100.0 * gaps.get(i) / median)
                                + "%：可能是在思考、在整理，也可能卡住了",
                        List.of(sid + "/-")));
            }
        }
        return out;
    }

    public static List<RawEvent> readJsonl(java.nio.file.Path p) throws java.io.IOException {
        List<RawEvent> evs = new ArrayList<>();
        for (String line : java.nio.file.Files.readAllLines(p, java.nio.charset.StandardCharsets.UTF_8)) {
            String s = line.trim();
            if (s.isEmpty()) {
                continue;
            }
            String eid = field(s, "eventId");
            String sid = field(s, "sessionId");
            String type = field(s, "type");
            long t = longField(s, "tRelMs");
            long dur = longField(s, "durMs");
            com.octant.pipeline.raw.EventType et = com.octant.pipeline.raw.EventType.fromWireName(type);
            if (et == null) {
                continue;
            }
            evs.add(new RawEvent(eid, sid, et, t, t / 50L, dur, true, Map.of()));
        }
        return evs;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("用法：ContextReplay <events.anonymized.jsonl>");
            return;
        }
        List<RawEvent> evs = readJsonl(java.nio.file.Path.of(args[0]));
        Timeline tl = replay(evs);
        System.out.println("事件 " + evs.size() + " 条 → " + describe(tl));
        System.out.println("note: " + tl.note());
        System.out.println("── 逐会话赛道（分钟）──");
        for (SessionContext sc : tl.sessions()) {
            StringBuilder sb = new StringBuilder();
            for (String lane : sc.laneOrder()) {
                sb.append(lane).append('=').append(round1(sc.lanes().getOrDefault(lane, 0L) / 60_000.0))
                        .append(' ');
            }
            System.out.println("  " + sc.sessionId() + "  时长 " + round1(sc.durationMs() / 60_000.0)
                    + " 分钟 · 事件 " + sc.eventCount() + " 条  " + sb);
        }
        System.out.println("── 选中的片段 ──");
        if (tl.segments().isEmpty()) {
            System.out.println("  （无）");
        }
        for (ReplaySegments.Segment s : tl.segments()) {
            System.out.println("  " + s.sessionId() + " " + s.kind().label() + " "
                    + s.startMs() + "→" + s.endMs() + "（" + s.lengthMs() + " ms，标记 " + s.marks()
                    + "）理由：" + String.join("；", s.reasons()));
        }
    }

    private static String field(String line, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").matcher(line);
        return m.find() ? m.group(1) : "";
    }

    private static long longField(String line, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + key + "\"\\s*:\\s*(-?\\d+)").matcher(line);
        return m.find() ? Long.parseLong(m.group(1)) : 0L;
    }

    private static String round1(double v) {
        long tenths = Math.round(v * 10.0);
        return (tenths / 10) + "." + Math.abs(tenths % 10);
    }

    public static String describe(Timeline t) {
        StringBuilder sb = new StringBuilder();
        sb.append("会话 ").append(t.sessionTotal()).append(" 个 · 有片段的会话 ")
                .append(t.sessionsWithSegments()).append(" 个 · 片段 ").append(t.segments().size()).append(" 段");
        Set<String> kinds = new LinkedHashSet<>();
        for (ReplaySegments.Segment s : t.segments()) {
            kinds.add(s.kind().label());
        }
        if (!kinds.isEmpty()) {
            sb.append("（类别：").append(String.join("、", kinds)).append("）");
        }
        sb.append(" · 信号来源：").append(t.signalSource());
        return sb.toString();
    }
}
