package com.octant.pipeline.analysis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplaySegmentsTest {

    private static ReplaySegments.Mark mark(String sid, long start, long end,
                                            ReplaySegments.Kind kind, String why) {
        return new ReplaySegments.Mark(sid, start, end, kind, why, List.of(sid + "/e001"));
    }

    @Test
    @DisplayName("修正①：同类合并受 180s 上限约束，跨类不合并")
    void sameKindMergeRespectsCapAndNeverCrossesKinds() {
        List<ReplaySegments.Mark> marks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            marks.add(mark("s1", i * 60_000L, i * 60_000L + 60_000L,
                    ReplaySegments.Kind.FIRST_CONTACT, "首次遇到地点#" + i));
        }
        marks.add(mark("s1", 400_000L, 430_000L, ReplaySegments.Kind.COMBAT_ANOMALY, "交战 timeout"));

        ReplaySegments.Selection sel = ReplaySegments.select(marks, 1);
        for (ReplaySegments.Segment s : sel.segments()) {
            assertTrue(s.lengthMs() <= ReplaySegments.MAX_SEGMENT_MS,
                    "任何段的长度都不得超过上限：" + s);
            assertTrue(s.kind() == ReplaySegments.Kind.FIRST_CONTACT
                            || s.kind() == ReplaySegments.Kind.COMBAT_ANOMALY,
                    "段内只能有一个类别：" + s);
        }
    }

    @Test
    @DisplayName("修正②：唯一的弱信号小段必须被丢弃（不硬凑片段）")
    void thinWeakOnlySessionYieldsNothing() {
        List<ReplaySegments.Mark> marks = new ArrayList<>();
        marks.add(mark("s9", 0L, 60_000L, ReplaySegments.Kind.FIRST_CONTACT, "首次遇到 plains"));
        ReplaySegments.Selection sel = ReplaySegments.select(marks, 1);
        assertTrue(sel.segments().isEmpty(), "两条事件级别的会话不该产出片段");
        assertEquals(0, sel.sessionsWithSegments());
    }

    @Test
    @DisplayName("强信号豁免段下限：单条死亡标记仍被选中")
    void strongSignalSurvivesMinimum() {
        List<ReplaySegments.Mark> marks = List.of(
                mark("s2", 60_000L, 135_000L, ReplaySegments.Kind.DEATH, "死亡前后"));
        ReplaySegments.Selection sel = ReplaySegments.select(marks, 1);
        assertEquals(1, sel.segments().size());
        assertTrue(sel.segments().get(0).kind().strong());
    }

    @Test
    @DisplayName("修正③：计数口径分开（会话数 ≠ 段数）")
    void countsAreSeparate() {
        List<ReplaySegments.Mark> marks = new ArrayList<>();
        marks.add(mark("a", 0L, 60_000L, ReplaySegments.Kind.DEATH, "死亡 A"));
        marks.add(mark("b", 0L, 60_000L, ReplaySegments.Kind.COMBAT_ANOMALY, "异常 B"));
        ReplaySegments.Selection sel = ReplaySegments.select(marks, 5);
        assertEquals(2, sel.sessionsWithSegments(), "两个会话有片段");
        assertEquals(2, sel.segments().size(), "两段");
        assertEquals(5, sel.sessionTotal(), "会话总数必须原样带出（不是段数）");
    }

    @Test
    @DisplayName("确定性：同一输入两遍选取结果相等")
    void deterministic() {
        List<ReplaySegments.Mark> marks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            marks.add(mark("s3", i * 20_000L, i * 20_000L + 15_000L,
                    ReplaySegments.Kind.STALL, "停滞候选 #" + i));
        }
        ReplaySegments.Selection a = ReplaySegments.select(marks, 1);
        ReplaySegments.Selection b = ReplaySegments.select(marks, 1);
        assertEquals(a.segments(), b.segments(), "同一输入必须得到同一批片段");
    }

    @Test
    @DisplayName("限量：每会话 ≤3 段、每报告 ≤12 段")
    void limitsHold() {
        List<ReplaySegments.Mark> marks = new ArrayList<>();
        for (String sid : List.of("p", "q", "r", "s", "t", "u")) {
            for (int i = 0; i < 5; i++) {
                marks.add(mark(sid, i * 400_000L, i * 400_000L + 30_000L,
                        ReplaySegments.Kind.COMBAT_ANOMALY, sid + " 异常 #" + i));
            }
        }
        ReplaySegments.Selection sel = ReplaySegments.select(marks, 6);
        assertTrue(sel.segments().size() <= ReplaySegments.MAX_PER_REPORT,
                "报告级上限：" + sel.segments().size());
        for (String sid : List.of("p", "q", "r", "s", "t", "u")) {
            long n = sel.segments().stream().filter(x -> x.sessionId().equals(sid)).count();
            assertTrue(n <= ReplaySegments.MAX_PER_SESSION, sid + " 超过每会话上限：" + n);
        }
        assertFalse(sel.segments().isEmpty());
    }
}
