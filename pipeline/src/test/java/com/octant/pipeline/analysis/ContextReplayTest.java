package com.octant.pipeline.analysis;

import com.octant.pipeline.raw.EventType;
import com.octant.pipeline.raw.RawEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextReplayTest {

    private static RawEvent ev(String sid, String eid, EventType type, long tRel) {
        return new RawEvent(eid, sid, type, tRel, tRel / 50L, 0L, true, Map.of());
    }

    private static RawEvent evDur(String sid, String eid, EventType type, long tRel, long dur) {
        return new RawEvent(eid, sid, type, tRel, tRel / 50L, dur, true, Map.of());
    }

    @Test
    void lanesSumToSessionDuration() {
        List<RawEvent> evs = List.of(
                ev("s1", "e001", EventType.SESSION_START, 0),
                ev("s1", "e002", EventType.BIOME_VISITED, 1_000),
                ev("s1", "e003", EventType.ADVANCEMENT_GAINED, 30_000),
                ev("s1", "e004", EventType.SESSION_END, 90_000));
        ContextReplay.Timeline t = ContextReplay.replay(evs);
        assertEquals(1, t.sessions().size());
        ContextReplay.SessionContext sc = t.sessions().get(0);
        long sum = sc.lanes().values().stream().mapToLong(Long::longValue).sum();
        assertEquals(sc.durationMs(), sum,
                "赛道合计必须等于会话时长（否则有秒数被算两次或被吃掉）：" + sc.lanes());
    }

    @Test
    void stateSwitchesAtTheDeclaringEvent() {
        List<RawEvent> evs = List.of(
                ev("s1", "e001", EventType.SESSION_START, 0),
                ev("s1", "e002", EventType.BIOME_VISITED, 1_000),
                ev("s1", "e003", EventType.ADVANCEMENT_GAINED, 30_000),
                ev("s1", "e004", EventType.SESSION_END, 90_000));
        ContextReplay.SessionContext sc = ContextReplay.replay(evs).sessions().get(0);
        assertEquals(1_000L, sc.lanes().get("无观测"), "会话开头到第一次宣告之间才是无观测：" + sc.lanes());
        assertEquals(29_000L, sc.lanes().get("探索"), "1s–30s 应归探索：" + sc.lanes());
        assertEquals(60_000L, sc.lanes().get("推进"), "30s–90s 应归推进：" + sc.lanes());
    }

    @Test
    void combatIntervalForcesCombatLane() {
        List<RawEvent> evs = List.of(
                ev("s1", "e001", EventType.SESSION_START, 0),
                ev("s1", "e002", EventType.BIOME_VISITED, 1_000),
                evDur("s1", "e003", EventType.COMBAT_STARTED, 10_000, 20_000),
                evDur("s1", "e004", EventType.COMBAT_ENDED, 30_000, 20_000),
                ev("s1", "e005", EventType.ADVANCEMENT_GAINED, 60_000),
                ev("s1", "e006", EventType.SESSION_END, 80_000));
        ContextReplay.SessionContext sc = ContextReplay.replay(evs).sessions().get(0);
        assertEquals(20_000L, sc.lanes().get("战斗"), "10s–30s 必须整段算战斗：" + sc.lanes());
        assertEquals(31_000L, sc.lanes().get("无观测"), "战后那段不该继续算战斗：" + sc.lanes());
        assertEquals(20_000L, sc.lanes().get("推进"), "60s–80s 归推进：" + sc.lanes());
        long sum = sc.lanes().values().stream().mapToLong(Long::longValue).sum();
        assertEquals(sc.durationMs(), sum, "覆盖后合计仍须等于时长：" + sc.lanes());
    }

    @Test
    void neverReadsPayload() {
        String secret = "LEAK-CANARY-9f3a2b";
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("someUnregisteredField", secret);
        payload.put("chatLine", secret);
        RawEvent e1 = new RawEvent("e001", "s1", EventType.SESSION_START, 0, 0, 0, true, payload);
        RawEvent e2 = new RawEvent("e002", "s1", EventType.COMBAT_STARTED, 10_000, 200, 20_000, true, payload);
        RawEvent e3 = new RawEvent("e003", "s1", EventType.COMBAT_ENDED, 30_000, 600, 0, true, payload);
        RawEvent e4 = new RawEvent("e004", "s1", EventType.SESSION_END, 60_000, 1200, 0, true, payload);
        ContextReplay.Timeline t = ContextReplay.replay(List.of(e1, e2, e3, e4));
        String dump = t.toString() + ContextReplay.describe(t) + t.note() + t.signalSource();
        for (ReplaySegments.Segment s : t.segments()) {
            dump = dump + s.toString();
        }
        for (ContextReplay.SessionContext sc : t.sessions()) {
            dump = dump + sc.toString();
        }
        assertFalse(dump.contains(secret),
                "回放层把 payload 值带进了产物 ⇒ 它成了绕过脱敏的第二条通道：" + dump);
    }

    @Test
    void deterministic() {
        List<RawEvent> evs = new ArrayList<>();
        evs.add(ev("s2", "e001", EventType.SESSION_START, 0));
        evs.add(ev("s1", "e001", EventType.SESSION_START, 0));
        for (int i = 0; i < 6; i++) {
            evs.add(evDur("s2", "c" + i, EventType.COMBAT_STARTED, 10_000L * i + 1_000, 20_000));
            evs.add(ev("s2", "d" + i, EventType.COMBAT_ENDED, 10_000L * i + 25_000));
        }
        evs.add(ev("s1", "hb1", EventType.SESSION_HEARTBEAT, 60_000));
        evs.add(ev("s1", "hb2", EventType.SESSION_HEARTBEAT, 120_000));
        evs.add(ev("s2", "e999", EventType.SESSION_END, 200_000));
        ContextReplay.Timeline a = ContextReplay.replay(evs);
        ContextReplay.Timeline b = ContextReplay.replay(new ArrayList<>(evs));
        assertEquals(a.sessions().toString(), b.sessions().toString(), "赛道两遍不一致");
        assertEquals(a.segments().toString(), b.segments().toString(), "片段两遍不一致");
        assertEquals(ContextReplay.describe(a), ContextReplay.describe(b));
    }

    @Test
    void absenceIsNotZero() {
        List<RawEvent> evs = List.of(
                ev("s1", "e001", EventType.SESSION_START, 0),
                ev("s1", "e002", EventType.SESSION_HEARTBEAT, 60_000),
                ev("s1", "e003", EventType.ADVANCEMENT_GAINED, 90_000),
                ev("s1", "e004", EventType.SESSION_END, 120_000));
        ContextReplay.Timeline t = ContextReplay.replay(evs);
        assertTrue(t.segments().isEmpty(), "没有强信号时不该硬凑片段");
        assertNotNull(t.note());
        assertTrue(t.note().contains("没有信号"),
                "片段为空时必须说清是'没有信号'，而不是让读者以为'这段时间没发生什么'：" + t.note());
    }

    @Test
    void longCombatWindowComesFromDurMs() {
        List<RawEvent> evs = List.of(
                ev("s1", "e001", EventType.SESSION_START, 0),
                ev("s1", "e002", EventType.COMBAT_STARTED, 40_000),
                evDur("s1", "e003", EventType.COMBAT_ENDED, 40_000, 20_000),
                ev("s1", "e004", EventType.SESSION_END, 60_000));
        ContextReplay.Timeline t = ContextReplay.replay(evs);
        boolean found = false;
        for (ReplaySegments.Segment s : t.segments()) {
            if (s.kind() == ReplaySegments.Kind.COMBAT_ANOMALY) {
                found = true;
                assertEquals(20_000L, s.lengthMs(),
                        "窗口长度必须取自 durMs（时间戳差恒为 0）：" + s);
                assertEquals(20_000L, s.startMs(), "窗口起点应为 终点−durMs");
                assertEquals(40_000L, s.endMs());
            }
        }
        assertTrue(found, "20 秒的交战必须成为「战斗异常」片段：" + t.segments());
    }

    @Test
    void noZeroLengthSegments() {
        List<RawEvent> evs = new ArrayList<>();
        evs.add(ev("s1", "e001", EventType.SESSION_START, 0));
        for (int i = 0; i < 4; i++) {
            evs.add(ev("s1", "c" + i, EventType.COMBAT_STARTED, 10_000L * (i + 1)));
            evs.add(evDur("s1", "d" + i, EventType.COMBAT_ENDED, 10_000L * (i + 1), 0L));
        }
        evs.add(ev("s1", "e999", EventType.SESSION_END, 60_000));
        ContextReplay.Timeline t = ContextReplay.replay(evs);
        for (ReplaySegments.Segment s : t.segments()) {
            assertTrue(s.lengthMs() >= ReplaySegments.MIN_SEGMENT_MS,
                    "零长度/过短的片段不是能回看的窗口：" + s);
        }
    }

    @Test
    void collectorSignalsWinOverDerived() {
        List<RawEvent> evs = List.of(
                ev("s1", "e001", EventType.SESSION_START, 0),
                ev("s1", "e002", EventType.SESSION_HEARTBEAT, 10_000),
                ev("s1", "e003", EventType.SESSION_HEARTBEAT, 600_000),
                evDur("s1", "e004", EventType.STALL_SEGMENT, 600_000, 120_000),
                ev("s1", "e005", EventType.SESSION_END, 900_000));
        ContextReplay.Timeline t = ContextReplay.replay(evs);
        assertEquals("collector", t.signalSource(), "有采集侧信号时必须走采集侧");
        assertTrue(t.segments().stream().anyMatch(s -> s.kind() == ReplaySegments.Kind.STALL),
                "采集侧自报的停滞必须成为片段：" + t.segments());
    }
}
